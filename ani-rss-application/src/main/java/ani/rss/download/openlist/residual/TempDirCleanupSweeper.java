package ani.rss.download.openlist.residual;

import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.TempDirCleanupRecord;
import ani.rss.util.other.TempDirCleanupStore;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 临时目录清理债务的巡检器。
 * <p>
 * 为什么需要独立巡检而不是"收尾时多试几次"：临时目录删除撞上的通常是网盘限流/熔断冷却，
 * 而冷却期恰恰<b>不能</b>再发请求（{@code fsListStrict} 会直接抛「网盘接口冷却中」）。
 * 在收尾线程里重试只会空转并占着离线槽位；正确做法是把债务落盘，等冷却过去再在后台补做。
 * <p>
 * 三条硬约束：
 * <ul>
 *   <li><b>单飞</b>：并发触发（登录后/每轮 RSS/按钮）只允许一份在跑；</li>
 *   <li><b>冷却期零请求</b>：{@code listingCooldownRemainingMs() > 0} 直接返回；</li>
 *   <li><b>每轮限额</b>：一次最多 {@link #BATCH} 条，避免清理风暴自己把限流喂大。</li>
 * </ul>
 * 删除前的安全门见 {@link #sweepOne}：查不清不删、同名是文件不删、成片不在顶层不删。
 */
@Slf4j
public final class TempDirCleanupSweeper {

    /**
     * 单次巡检最多处理几条债务。
     */
    private static final int BATCH = 3;

    private final OpenListResidualPort port;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public TempDirCleanupSweeper(OpenListResidualPort port) {
        this.port = port;
    }

    /**
     * 巡检结果（用于日志与界面提示）。
     */
    public record SweepResult(boolean ran, int deleted, int deferred, int needsHuman, String message) {
        static SweepResult skipped(String message) {
            return new SweepResult(false, 0, 0, 0, message);
        }
    }

    /**
     * 执行一次巡检。任何异常都不外抛——它是后台收尾，不能影响调用方。
     */
    public SweepResult sweep() {
        if (port == null) {
            return SweepResult.skipped("未接入网盘");
        }
        if (!port.loggedIn()) {
            return SweepResult.skipped("OpenList 未登录");
        }
        long cooldown = port.listingCooldownRemainingMs();
        if (cooldown > 0L) {
            return SweepResult.skipped("网盘接口冷却中（剩余 " + ((cooldown + 999L) / 1000L) + "s）");
        }
        if (!running.compareAndSet(false, true)) {
            return SweepResult.skipped("清理巡检进行中");
        }
        try {
            List<TempDirCleanupRecord> due = TempDirCleanupStore.due(System.currentTimeMillis(), BATCH);
            if (due.isEmpty()) {
                return new SweepResult(true, 0, 0, 0, "无待清理临时目录");
            }
            int deleted = 0;
            int deferred = 0;
            int human = 0;
            for (TempDirCleanupRecord record : due) {
                try {
                    switch (sweepOne(record)) {
                        case DELETED, ALREADY_ABSENT -> deleted++;
                        case MISMATCH_HUMAN -> human++;
                        default -> deferred++;
                    }
                } catch (Exception e) {
                    // 单条异常不拖垮整轮
                    deferred++;
                    TempDirCleanupStore.defer(record.getId(), "巡检异常: " + e.getMessage());
                    log.debug("清理临时目录异常 {}/{}: {}", record.getSavePath(), record.getTempDirName(),
                            e.getMessage());
                }
            }
            String msg = "临时目录清理巡检 deleted=%d deferred=%d human=%d。注意：deferred 与 human 都不是" +
                    "「删除完成」，只有 deleted 才代表已回读确认。";
            msg = String.format(msg, deleted, deferred, human);
            if (deleted > 0) {
                log.info("临时目录清理巡检完成 deleted={} deferred={} human={}", deleted, deferred, human);
            } else {
                log.debug("临时目录清理巡检完成 deleted={} deferred={} human={}", deleted, deferred, human);
            }
            return new SweepResult(true, deleted, deferred, human, msg);
        } catch (Exception e) {
            log.debug("临时目录清理巡检失败: {}", e.getMessage());
            return SweepResult.skipped("巡检失败: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /**
     * 单条债务：安全门 → 删除 → 销账/延期/转人工。
     */
    private TempDirDeleteResult sweepOne(TempDirCleanupRecord record) {
        String savePath = record.getSavePath();
        String tempDirName = record.getTempDirName();
        if (StrUtil.isBlank(savePath) || StrUtil.isBlank(tempDirName)) {
            TempDirCleanupStore.needsHuman(record.getId(), "债务记录缺少路径");
            return TempDirDeleteResult.MISMATCH_HUMAN;
        }

        // 门 1：正在下载/正在等待归位的临时目录绝不能动（并发离线时这里是唯一的保护）
        Set<String> active = port.activeTempDirNames();
        if (active != null && active.contains(tempDirName)) {
            return defer(record, "临时目录仍被在途任务占用");
        }
        List<String> waiting = port.offlineWaitNames();
        if (waiting != null && waiting.contains(tempDirName)) {
            return defer(record, "临时目录仍在离线等待列表中");
        }

        // 门 2：严格列举。查不清 ≠ 没有了，一律当"未确认"，保留债务下次再试
        List<OpenListFileInfo> top;
        try {
            top = port.listForCleanup(savePath);
        } catch (Exception e) {
            return defer(record, "列举最终目录失败，本次不删: " + e.getMessage());
        }

        boolean sameNameFile = false;
        boolean sameNameDir = false;
        for (OpenListFileInfo file : top) {
            if (file == null || !tempDirName.equals(file.getName())) {
                continue;
            }
            if (Boolean.TRUE.equals(file.getIsDir())) {
                sameNameDir = true;
            } else {
                sameNameFile = true;
            }
        }
        if (sameNameFile) {
            TempDirCleanupStore.needsHuman(record.getId(),
                    "最终目录顶层已存在同名文件（不是目录），拒绝整树删除");
            return TempDirDeleteResult.MISMATCH_HUMAN;
        }
        if (!sameNameDir) {
            // 已经不在盘上了：这就是确认态
            TempDirCleanupStore.confirm(record.getId());
            log.info("临时目录已不存在，销账 {}/{}", savePath, tempDirName);
            return TempDirDeleteResult.ALREADY_ABSENT;
        }

        // 门 3：自动登记（finalize）的债务要求"已归位成片逐个仍在顶层"。
        // 少了任何一件都说明现场与当初判定不符（可能被洗版/手工删/移动其实没落位），
        // 此时删临时目录可能把唯一一份产物删掉 → 转人工。
        if (TempDirCleanupRecord.SOURCE_FINALIZE.equals(record.getSource())
                && record.getFinalizedNames() != null && !record.getFinalizedNames().isEmpty()) {
            List<String> missing = record.getFinalizedNames().stream()
                    .filter(name -> top.stream().noneMatch(f -> f != null
                            && name.equals(f.getName())
                            && !Boolean.TRUE.equals(f.getIsDir())))
                    .toList();
            if (!missing.isEmpty()) {
                TempDirCleanupStore.needsHuman(record.getId(),
                        "最终目录顶层缺少已归位文件 " + StrUtil.maxLength(String.join(",", missing), 120));
                return TempDirDeleteResult.MISMATCH_HUMAN;
            }
        }

        TempDirDeleteResult result = port.deleteTempDir(savePath, tempDirName);
        if (result == null) {
            result = TempDirDeleteResult.FAILED_RETRYABLE;
        }
        if (result.confirmed()) {
            TempDirCleanupStore.confirm(record.getId());
            log.info("临时目录清理完成（已回读确认） {}/{}", savePath, tempDirName);
        } else if (result == TempDirDeleteResult.MISMATCH_HUMAN) {
            TempDirCleanupStore.needsHuman(record.getId(), "删除时现场与预期不符");
        } else {
            TempDirCleanupStore.defer(record.getId(), "删除未确认（限流/冷却/异步未落地）");
        }
        return result;
    }

    private TempDirDeleteResult defer(TempDirCleanupRecord record, String reason) {
        TempDirCleanupStore.defer(record.getId(), reason);
        log.debug("临时目录清理延期 {}/{}: {}", record.getSavePath(), record.getTempDirName(), reason);
        return TempDirDeleteResult.FAILED_RETRYABLE;
    }
}

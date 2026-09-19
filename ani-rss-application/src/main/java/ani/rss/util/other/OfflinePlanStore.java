package ani.rss.util.other;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Item;
import ani.rss.entity.TorrentPlanRecord;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 期望文件计划的落盘仓库（P4）。
 * <p>
 * 位置：{@code {config}/torrents/.pending/<infoHash>.plan.json}——与离线任务的待完成标记同目录，
 * 生命周期也一致（任务收尾即删）。启动时 {@code TorrentUtil.cleanupOrphanPending()} 会清掉
 * 待完成标记，但<b>刻意保留</b> {@code .plan.json}，交给
 * {@code OpenList.recoverOfflinePlans()} 做一次"文件其实早下完了"的补救。
 * <p>
 * 写入走「临时文件 + 原子移动」，读到半截 JSON 只会被当成"没有计划"，不会影响下载。
 */
@Slf4j
public final class OfflinePlanStore {

    public static final String SUFFIX = ".plan.json";

    /**
     * 过期时限：超过这个时间的快照不再参与启动恢复（一次重启没恢复成，说明该集还早，
     * 交回 RSS 正常轮次处理即可，避免历史快照无限堆积）
     */
    private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    private OfflinePlanStore() {
    }

    public static File planDir() {
        File dir = new File(ConfigUtil.getConfigDir(), "torrents" + File.separator + ".pending");
        FileUtil.mkdir(dir);
        return dir;
    }

    public static boolean isPlanFile(File file) {
        return file != null && file.getName().endsWith(SUFFIX);
    }

    public static File planFile(String infoHash) {
        return new File(planDir(), infoHash + SUFFIX);
    }

    /**
     * 保存/覆盖一份计划快照。失败只记日志：计划是加速与补救手段，不是下载的前提。
     */
    public static void save(String infoHash, String aniId, String downloadPath, String tempDirName,
                            String finalRenameBase, List<Item> items) {
        if (StrUtil.isBlank(infoHash) || items == null || items.isEmpty()) {
            return;
        }
        TorrentPlanRecord record = new TorrentPlanRecord()
                .setInfoHash(infoHash)
                .setAniId(aniId)
                .setDownloadPath(downloadPath)
                .setTempDirName(tempDirName)
                .setFinalRenameBase(finalRenameBase)
                .setItems(List.copyOf(items))
                .setCreatedAt(System.currentTimeMillis());
        File target = planFile(infoHash);
        File temp = new File(target.getParentFile(), target.getName() + ".temp");
        try {
            FileUtil.writeUtf8String(GsonStatic.toJson(record), temp);
            FileUtils.move(temp.toPath(), target.toPath());
        } catch (Exception e) {
            log.debug("保存离线计划快照失败 {}: {}", infoHash, e.getMessage());
        } finally {
            FileUtil.del(temp);
        }
    }

    public static void delete(String infoHash) {
        if (StrUtil.isBlank(infoHash)) {
            return;
        }
        FileUtil.del(planFile(infoHash));
    }

    /**
     * 列出全部快照（按落盘时间升序），并顺手清掉过期/损坏的。
     */
    public static List<TorrentPlanRecord> list() {
        File dir = planDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
        if (files == null || files.length == 0) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<TorrentPlanRecord> records = new ArrayList<>();
        for (File file : files) {
            TorrentPlanRecord record = null;
            try {
                record = GsonStatic.fromJson(FileUtil.readUtf8String(file), TorrentPlanRecord.class);
            } catch (Exception e) {
                log.debug("读取离线计划快照失败，按无计划处理 {}: {}", file.getName(), e.getMessage());
            }
            if (record == null || StrUtil.isBlank(record.getInfoHash())
                    || record.getItems() == null || record.getItems().isEmpty()) {
                FileUtil.del(file);
                continue;
            }
            long createdAt = record.getCreatedAt() == null ? 0L : record.getCreatedAt();
            if (createdAt > 0 && now - createdAt > MAX_AGE_MS) {
                log.info("离线计划快照已过期，清理 {}", file.getName());
                FileUtil.del(file);
                continue;
            }
            records.add(record);
        }
        records.sort(Comparator.comparing(r -> r.getCreatedAt() == null ? 0L : r.getCreatedAt()));
        return records;
    }
}

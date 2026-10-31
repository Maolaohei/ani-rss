package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.download.Offline115Janitor;
import ani.rss.entity.Config;
import ani.rss.entity.web.Result;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 115 离线任务管理。
 * <p>
 * 存在的理由：「任务已存在(10008)」死锁时，卡住的 115 任务在 AList 侧<b>不可见</b>
 * （{@code /api/task/offline_download/*} 返回空），用户唯一能做的就是去 115 网页手动删，
 * 而 115 的离线列表操作门槛很高（要翻、要认 hash）。这里把「看任务 / 看配额 / 删卡死」变成三个接口。
 * <p>
 * 本控制器只做透传 + 判定，真正的删除逻辑在 {@link Offline115Janitor}。
 */
@Slf4j
@RestController
public class Offline115Controller extends BaseController {

    /**
     * 任务与配额现状
     */
    @Auth
    @Operation(summary = "115 离线任务状态")
    @PostMapping("/offline115/status")
    public Result<Map<String, Object>> status() {
        Config config = ConfigUtil.CONFIG;
        Map<String, Object> data = new LinkedHashMap<>();
        boolean openList = Offline115Janitor.openListTool(config);
        data.put("openListTool", openList);
        data.put("enabled", Offline115Janitor.enabled(config));
        data.put("stuckMinutes", Offline115Janitor.stuckMinutes(config));
        data.put("quotaWarnPercent", Offline115Janitor.quotaWarnPercent(config));
        if (!openList) {
            // 看门狗只在 OpenList 下载链路下有意义：cookie 从 AList 反查，清理的对象是 115 离线任务
            data.put("message", "当前下载工具不是 OpenList，115 离线看门狗不生效");
            return Result.success(data);
        }
        String cookie = Offline115Janitor.resolveCookie(config);
        data.put("cookieConfigured", StrUtil.isNotBlank(cookie));
        if (StrUtil.isBlank(cookie)) {
            data.put("message", "未取到 115 cookie：可填 offline115Cookie，"
                    + "或让下载器指向 AList 并确认 offline115Mount 与 115 存储挂载路径一致");
            return Result.success(data);
        }
        Offline115Janitor.Snapshot snapshot = Offline115Janitor.snapshot(config);
        if (snapshot == null) {
            data.put("message", "115 离线任务接口调用失败（cookie 可能已过期）");
            return Result.success(data);
        }
        int stuckMinutes = Offline115Janitor.stuckMinutes(config);
        data.put("quota", snapshot.quota());
        data.put("total", snapshot.total());
        data.put("quotaPercent", snapshot.quotaPercent());
        data.put("taskCount", snapshot.tasks().size());
        data.put("runningCount", snapshot.runningCount());
        data.put("finishedCount", snapshot.finishedCount());
        data.put("tasks", toViews(snapshot.tasks(), stuckMinutes));
        data.put("stuckCount", snapshot.stuck(stuckMinutes).size());
        data.put("fetchedAt", snapshot.fetchedAt());
        return Result.success(data);
    }

    /**
     * 清理卡死任务（默认 dryRun，只列不删）
     */
    @Auth
    @Operation(summary = "清理 115 卡死离线任务")
    @PostMapping("/offline115/clean")
    public Result<Map<String, Object>> clean(@RequestBody(required = false) Map<String, Object> body) {
        Config config = ConfigUtil.CONFIG;
        boolean dryRun = true;
        if (body != null && body.get("dryRun") != null) {
            dryRun = Boolean.parseBoolean(String.valueOf(body.get("dryRun")));
        }
        int stuckMinutes = Offline115Janitor.stuckMinutes(config);
        List<Offline115Janitor.Task> hit = Offline115Janitor.cleanStuck(config, dryRun);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dryRun", dryRun);
        data.put("stuckMinutes", stuckMinutes);
        data.put("count", hit.size());
        data.put("tasks", toViews(hit, stuckMinutes));
        if (!dryRun) {
            log.info("115 离线任务清理完成，删除 {} 条", hit.size());
        }
        return Result.success(data);
    }

    private static List<Map<String, Object>> toViews(List<Offline115Janitor.Task> tasks, int stuckMinutes) {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> views = new ArrayList<>(tasks.size());
        for (Offline115Janitor.Task task : tasks) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("infoHash", task.infoHash());
            view.put("name", task.name());
            view.put("status", task.status());
            view.put("percentDone", task.percentDone());
            view.put("addTime", task.addTime());
            // 已存在的分钟数：卡死判定的直接依据，排查时不用自己算
            view.put("elapsedMinutes", task.addTime() > 0
                    ? (now - task.addTime() * 1000L) / 60_000L : 0L);
            view.put("stuck", Offline115Janitor.isStuck(task, now, stuckMinutes));
            views.add(view);
        }
        return views;
    }
}

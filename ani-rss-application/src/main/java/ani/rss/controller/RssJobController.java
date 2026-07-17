package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.download.OpenList;
import ani.rss.entity.vo.RssJobStatus;
import ani.rss.entity.web.Result;
import ani.rss.task.RssTask;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RSS 任务管理器
 */
@RestController
public class RssJobController extends BaseController {

    @Auth
    @Operation(summary = "RSS 任务状态")
    @PostMapping("/rssJobStatus")
    public Result<RssJobStatus> rssJobStatus() {
        return Result.success(RssTask.getJobStatus());
    }

    @Auth
    @Operation(summary = "取消当前 RSS 任务")
    @PostMapping("/rssJobCancel")
    public Result<RssJobStatus> rssJobCancel() {
        boolean accepted = RssTask.requestCancel();
        Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
        if (!accepted) {
            result.setMessage("当前没有可取消的任务");
            return result;
        }
        result.setMessage("已请求取消");
        return result;
    }

    @Auth
    @Operation(summary = "按条目取消 RSS/OpenList 任务")
    @PostMapping("/rssJobCancelItem")
    public Result<RssJobStatus> rssJobCancelItem(@RequestBody(required = false) Map<String, Object> body) {
        String id = null;
        if (body != null && body.get("id") != null) {
            id = String.valueOf(body.get("id"));
        }
        boolean accepted = RssTask.cancelItem(id);
        Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
        if (!accepted) {
            result.setMessage(StrUtil.isBlank(id) ? "当前没有可取消的任务" : ("条目不可取消或已不存在: " + id));
            return result;
        }
        if ("rss-pending".equals(id)) {
            result.setMessage("已清除待执行刷新");
        } else if ("openlist-current".equals(id)) {
            result.setMessage("已请求取消 OpenList 离线任务");
        } else {
            result.setMessage("已请求取消");
        }
        return result;
    }

    @Auth
    @Operation(summary = "扫描 OpenList 离线残留")
    @PostMapping("/rssJobResidualScan")
    public Result<RssJobStatus> rssJobResidualScan() {
        if (!RssTask.isOpenListTool()) {
            Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
            result.setMessage("当前下载工具不是 OpenList/Alist，无需扫描残留");
            return result;
        }
        try {
            OpenList.ResidualSnapshot snap = SpringUtil.getBean(OpenList.class).scanOfflineResiduals(true);
            Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
            int total = snap == null ? 0 : snap.getTotalCount();
            int active = snap == null ? 0 : snap.getActiveCount();
            int terminal = snap == null ? 0 : snap.getTerminalCount();
            result.setMessage(total == 0
                    ? "无离线残留"
                    : ("扫描完成: 进行中 " + active + " / 终态 " + terminal));
            return result;
        } catch (Exception e) {
            Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
            result.setMessage("扫描残留失败: " + e.getMessage());
            return result;
        }
    }

    @Auth
    @Operation(summary = "清理 OpenList 离线残留")
    @PostMapping("/rssJobResidualClean")
    public Result<RssJobStatus> rssJobResidualClean() {
        if (!RssTask.isOpenListTool()) {
            Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
            result.setMessage("当前下载工具不是 OpenList/Alist，无需清理残留");
            return result;
        }
        try {
            OpenList.CleanResult cleaned = SpringUtil.getBean(OpenList.class).cleanOfflineResiduals(true);
            Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
            result.setMessage(cleaned == null || cleaned.getMessage() == null
                    ? "清理完成"
                    : cleaned.getMessage());
            return result;
        } catch (Exception e) {
            Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
            result.setMessage("清理残留失败: " + e.getMessage());
            return result;
        }
    }
}

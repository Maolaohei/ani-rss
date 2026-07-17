package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.vo.RssJobStatus;
import ani.rss.entity.web.Result;
import ani.rss.task.RssTask;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

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
            result.setMessage("当前没有运行中的任务");
            return result;
        }
        result.setMessage("已请求取消，等待任务退出...");
        return result;
    }
}

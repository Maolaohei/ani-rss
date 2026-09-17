package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.TorrentsInfo;
import ani.rss.entity.web.Result;
import ani.rss.util.other.TorrentUtil;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TorrentsInfosController extends BaseController {

    @Auth
    @Operation(summary = "下载列表")
    @PostMapping("/torrentsInfos")
    public Result<List<TorrentsInfo>> torrentsInfos() {
        // P1 说明：本接口保持全量返回（ETag/增量裁剪太重，跳过）。
        // 渲染抖动由前端按 hash diff 消化（Dashboard mergeTorrents / TorrentsInfosView 同款），
        // 未变化的任务沿用旧引用，Vue 不会全量重渲染。
        List<TorrentsInfo> torrentsInfos = TorrentUtil.getTorrentsInfos();
        return Result.success(torrentsInfos);
    }

}

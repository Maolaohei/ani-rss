package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.download.OpenList;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.vo.RssJobStatus;
import ani.rss.entity.web.Result;
import ani.rss.service.DownloadService;
import ani.rss.task.RssTask;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.FailedDownloadQueue;
import ani.rss.util.other.ItemsUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * RSS 任务管理器
 */
@Slf4j
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
    @Operation(summary = "强制识别已下载(补回种子记录)")
    @PostMapping("/rssJobRecheckDownloaded")
    public Result<RssJobStatus> rssJobRecheckDownloaded() {
        // 对记录被误删的已下载集数: 重新检查文件真实存在并补回种子记录, 避免重新下载
        DownloadService downloadService = SpringUtil.getBean(DownloadService.class);
        int checked = 0;
        int downloaded = 0;
        int restored = 0;
        for (Ani ani : AniUtil.getAniList()) {
            try {
                List<Item> items = ItemsUtil.getItems(ani);
                for (Item item : items) {
                    checked++;
                    if (!downloadService.itemDownloaded(ani, item, false)) {
                        continue;
                    }
                    downloaded++;
                    // itemDownloaded 命中时内部已补写种子记录(DownloadService.saveTorrent)
                    File torrent = TorrentUtil.getTorrent(ani, item);
                    if (torrent.exists()) {
                        restored++;
                        // 文件已真实存在, 清除失败队列中的对应记录(已下载 ≠ 失败)
                        try {
                            FailedDownloadQueue.remove(
                                    FailedDownloadQueue.keyOf(ani.getId(), item.getInfoHash(), item.getReName()));
                        } catch (Exception e) {
                            log.debug("清除失败记录失败: {}", e.getMessage());
                        }
                    } else {
                        // 种子下载失败(如 404), 不重复请求, 仅告警
                        log.warn("补回种子记录失败(种子下载失败) {} - {}", ani.getTitle(), item.getReName());
                    }
                }
            } catch (Exception e) {
                // 单个订阅失败(如 RSS 拉取失败)不影响其他订阅
                log.debug("强制识别已下载失败: {} - {}", ani.getTitle(), e.getMessage());
            }
        }
        Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
        result.setMessage(StrFormatter.format("识别完成: 检查 {} 项, 已下载 {} 项, 补回记录 {} 项",
                checked, downloaded, restored));
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
            int preview = snap == null || snap.getItems() == null ? 0 : snap.getItems().size();
            result.setMessage(total == 0
                    ? "无离线残留"
                    : ("扫描完成: 进行中 " + active + " / 终态 " + terminal + "（预览 " + preview + " 条）"));
            return result;
        } catch (Exception e) {
            Result<RssJobStatus> result = Result.error(RssTask.getJobStatus());
            result.setMessage("扫描残留失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
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
            String message = cleaned == null || StrUtil.isBlank(cleaned.getMessage())
                    ? "清理完成"
                    : cleaned.getMessage();
            if (cleaned == null || !cleaned.isOk()) {
                Result<RssJobStatus> result = Result.error(RssTask.getJobStatus());
                result.setMessage(cleaned == null ? "清理残留失败: 未返回清理结果" : message);
                return result;
            }
            Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
            result.setMessage(message);
            return result;
        } catch (Exception e) {
            Result<RssJobStatus> result = Result.error(RssTask.getJobStatus());
            result.setMessage("清理残留失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return result;
        }
    }

    @Auth
    @Operation(summary = "扫描 OpenList 临时目录残留")
    @PostMapping("/rssJobTempDirResidualScan")
    public Result<RssJobStatus> rssJobTempDirResidualScan() {
        if (!RssTask.isOpenListTool()) {
            Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
            result.setMessage("当前下载工具不是 OpenList/Alist，无需扫描临时目录");
            return result;
        }
        try {
            OpenList.TempDirResidualSnapshot snap = SpringUtil.getBean(OpenList.class).scanTempDirResiduals(true);
            Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
            int total = snap == null ? 0 : snap.getTotalCount();
            int cleanable = snap == null ? 0 : snap.getCleanableCount();
            int preview = snap == null || snap.getItems() == null ? 0 : snap.getItems().size();
            result.setMessage(total == 0
                    ? "无临时目录残留"
                    : ("扫描完成: 可清理 " + cleanable + " / 合计 " + total + "（预览 " + preview + " 条）"));
            return result;
        } catch (Exception e) {
            Result<RssJobStatus> result = Result.error(RssTask.getJobStatus());
            result.setMessage("扫描临时目录失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return result;
        }
    }

    @Auth
    @Operation(summary = "清理 OpenList 临时目录残留（仅 FORCE/JUNK）")
    @PostMapping("/rssJobTempDirResidualClean")
    public Result<RssJobStatus> rssJobTempDirResidualClean() {
        if (!RssTask.isOpenListTool()) {
            Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
            result.setMessage("当前下载工具不是 OpenList/Alist，无需清理临时目录");
            return result;
        }
        try {
            OpenList.CleanResult cleaned = SpringUtil.getBean(OpenList.class).cleanTempDirResiduals();
            String message = cleaned == null || StrUtil.isBlank(cleaned.getMessage())
                    ? "临时目录清理完成"
                    : cleaned.getMessage();
            if (cleaned == null || !cleaned.isOk()) {
                Result<RssJobStatus> result = Result.error(RssTask.getJobStatus());
                result.setMessage(cleaned == null ? "清理临时目录失败: 未返回结果" : message);
                return result;
            }
            Result<RssJobStatus> result = Result.success(RssTask.getJobStatus());
            result.setMessage(message);
            return result;
        } catch (Exception e) {
            Result<RssJobStatus> result = Result.error(RssTask.getJobStatus());
            result.setMessage("清理临时目录失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return result;
        }
    }
}

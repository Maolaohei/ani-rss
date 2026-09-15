package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.web.Result;
import ani.rss.service.AniLocks;
import ani.rss.service.LocalStateCache;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
public class TorrentController extends BaseController {

    @Auth
    @Operation(summary = "删除缓存种子")
    @PostMapping("/deleteTorrent")
    public Result<Void> deleteTorrent(@RequestParam("id") String id, @RequestParam("hash") String hash) {
        Optional<Ani> first = AniUtil.getAniList().stream()
                .filter(ani -> id.equals(ani.getId()))
                .findFirst();
        if (first.isEmpty()) {
            return Result.error("此订阅不存在");
        }

        List<String> hashList = StrUtil.split(hash, ",", true, true);
        Ani ani = first.get();
        // F6-1：删除种子记录会改变本地状态判定口径，属写路径。
        // 与同一订阅的「下载 / 改名回写」互斥，避免"删记录"与"回写记录"互相覆盖。
        AniLocks.runWithWrite(ani, () -> {
            File torrentDir = TorrentUtil.getTorrentDir(ani);
            File[] files = FileUtils.listFiles(torrentDir);
            for (File file : files) {
                String name = FileUtil.mainName(file);
                if (hashList.contains(name)) {
                    log.info("删除种子 {}", file);
                    FileUtil.del(file);
                }
            }
            // 种子记录已删：该订阅的本地状态快照立即失效（F2-6）
            LocalStateCache.invalidate(ani);
        });
        return Result.success("删除完成");
    }
}
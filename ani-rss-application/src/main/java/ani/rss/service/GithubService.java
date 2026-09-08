package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.Github;
import ani.rss.entity.UpdateInfo;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.comparator.VersionComparator;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
public class GithubService {

    public Optional<Github.Release> getLatest(String owner, String repo) {
        Config config = ConfigUtil.CONFIG;
        String githubToken = config.getGithubToken();


        String latestUrl = "https://api.github.com/repos/{}/{}/releases/latest";

        latestUrl = StrUtil.format(latestUrl, owner, repo);

        HttpRequest request = HttpReq.get(latestUrl)
                .timeout(3000);

        if (StrUtil.isNotBlank(githubToken)) {
            request.header(Header.AUTHORIZATION, "Bearer " + githubToken);
        }

        return request.thenFunction(res -> {
            int status = res.getStatus();
            if (status == 404) {
                return Optional.empty();
            }
            HttpReq.assertStatus(res);
            Github.Release release = GsonStatic.fromJson(res.body(), Github.Release.class);
            return Optional.of(release);
        });
    }

    public UpdateInfo getUpdateInfo(String owner, String repo, String filename, String currentVersion) {
        UpdateInfo updateInfo = new UpdateInfo();
        updateInfo
                .setUpdate(false)
                .setAutoUpdate(false)
                .setLatest("")
                .setDownloadUrl("")
                .setSha256("")
                .setMarkdownBody("")
                .setSize(0L)
                .setFormatSize("0 MiB");

        Optional<Github.Release> releaseOpt = getLatest(owner, repo);
        if (releaseOpt.isEmpty()) {
            return updateInfo;
        }

        Github.Release release = releaseOpt.get();

        String message = release.getMessage();
        if (StrUtil.isNotBlank(message)) {
            log.error(message);
            return updateInfo;
        }

        String tagName = release.getTagName();
        Assert.notBlank(tagName, "release tagName 为空");

        String latest = tagName.replace("v", "");

        /*
        禁止非跨小版本的更新
        取前两位版本号判断是允许自动更新
        */
        String reg = "^[Vv]?(\\d+\\.\\d+)";
        String latestPrefix = ReUtil.get(reg, latest, 1);
        String currentPrefix = ReUtil.get(reg, StrUtil.nullToEmpty(currentVersion), 1);
        if (StrUtil.isBlank(latestPrefix) || StrUtil.isBlank(currentPrefix)) {
            // 版本号不符合 x.y 形态, 无法自动更新判断
            log.warn("版本号缺少 x.y 前缀, 跳过自动更新判断: latest={}, current={}", latest, currentVersion);
        }
        boolean autoUpdate = StrUtil.isNotBlank(latestPrefix) && latestPrefix.equals(currentPrefix);

        boolean update = VersionComparator.INSTANCE.compare(latest, currentVersion) > 0;

        updateInfo
                .setDate(release.getPublishedAt())
                .setAutoUpdate(autoUpdate)
                .setUpdate(false)
                .setLatest(latest)
                .setMarkdownBody(release.getBody());


        List<Github.Assets> assets = release.getAssets();
        if (Objects.isNull(assets)) {
            // assets 缺失, 视为空数组
            assets = List.of();
        }
        for (Github.Assets asset : assets) {
            if (Objects.isNull(asset)) {
                continue;
            }
            String name = asset.getName();
            if (!filename.equals(name)) {
                continue;
            }

            Long size = asset.getSize();
            String formatSize = FileUtils.formatSize(ObjectUtil.defaultIfNull(size, 0L), true);

            String digest = asset.getDigest();
            String sha256;
            if (StrUtil.isNotBlank(digest)) {
                sha256 = digest.replace("sha256:", "");
            } else {
                // digest 缺失, 跳过 sha256 校验
                log.debug("asset {} 缺失 digest, 跳过 sha256 校验", name);
                sha256 = "";
            }

            updateInfo
                    .setUpdate(update)
                    .setDownloadUrl(asset.getBrowserDownloadUrl())
                    .setSha256(sha256)
                    .setSize(ObjectUtil.defaultIfNull(size, 0L))
                    .setFormatSize(formatSize);
        }

        return updateInfo;
    }

}

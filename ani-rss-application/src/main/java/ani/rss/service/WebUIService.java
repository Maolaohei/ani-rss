package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.UpdateInfo;
import ani.rss.entity.WebUI;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.crypto.SecureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebUIService {
    private final GithubService githubService;

    public File getWebUIDir() {
        File configDir = ConfigUtil.getConfigDir();
        return new File(configDir, "webui");
    }

    public WebUI getWebUI() {
        File webuiJson = new File(getWebUIDir(), "webui.json");
        if (FileUtil.exist(webuiJson)) {
            return GsonStatic.fromJson(FileUtil.readUtf8String(webuiJson), WebUI.class);
        }
        return null;
    }

    public UpdateInfo getUpdate() {
        WebUI webUI = getWebUI();
        if (Objects.isNull(webUI)) {
            return null;
        }

        String owner = webUI.getOwner();
        String repo = webUI.getRepo();
        String version = webUI.getVersion();
        String filename = webUI.getFilename();

        return githubService.getUpdateInfo(owner, repo, filename, version);
    }

    public void update() {
        UpdateInfo updateInfo = getUpdate();
        if (Objects.isNull(updateInfo)) {
            return;
        }

        Boolean update = updateInfo.getUpdate();
        Assert.isTrue(Boolean.TRUE.equals(update), "无 WebUI 更新");

        log.info("更新 WebUI");

        File tempFile = FileUtil.createTempFile();
        File webuiDir = getWebUIDir();

        try {
            String downloadUrl = updateInfo.getDownloadUrl();
            String sha256 = updateInfo.getSha256();
            long size = updateInfo.getSize();

            HttpReq.thenClose(
                    HttpReq.get(downloadUrl),
                    res -> {
                        HttpReq.assertStatus(res);
                        FileUtil.writeFromStream(res.bodyStream(), tempFile, true);
                        Assert.isTrue(tempFile.length() == size, "WebUI 下载出现问题");
                        Assert.isTrue(SecureUtil.sha256(tempFile).equals(sha256), "WebUI 更新文件的 sha256 不匹配");
                    }
            );

            replaceWebUIDir(tempFile, webuiDir);
        } finally {
            // 无论下载或解压失败都清理临时文件
            FileUtil.del(tempFile);
        }

        log.info("WebUI 更新完成");
    }

    public void upload(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();

        String extName = FileUtil.extName(originalFilename);
        Assert.isTrue("zip".equals(extName), "文件格式错误");

        log.info("上传 WebUI {}", originalFilename);

        File tempFile = FileUtil.createTempFile();
        try {
            try (InputStream inputStream = file.getInputStream()) {
                FileUtil.writeFromStream(inputStream, tempFile);
            } catch (Exception e) {
                throw new IllegalArgumentException("上传 WebUI 失败");
            }

            try (ZipFile zipFile = new ZipFile(tempFile)) {
                ZipEntry entry = zipFile.getEntry("webui.json");
                Objects.requireNonNull(entry);
            } catch (Exception e) {
                throw new IllegalArgumentException("上传 WebUI 失败");
            }

            replaceWebUIDir(tempFile, getWebUIDir());
        } finally {
            // 无论校验或解压失败都清理临时文件
            FileUtil.del(tempFile);
        }

        log.info("WebUI 上传完成");
    }

    /**
     * 使用 zip 包原子替换 WebUI 目录:
     * 先解压到 staging 目录, 成功后旧目录改名 .bak → staging 原子改名 → 删除 .bak,
     * 避免先删后解压在半途失败时丢失可用 WebUI
     */
    private void replaceWebUIDir(File zipFile, File webuiDir) {
        File staging = new File(webuiDir.getParentFile(), ".staging-" + System.currentTimeMillis());
        FileUtil.del(staging);

        ZipUtil.unzip(zipFile, staging);

        File backup = new File(webuiDir.getParentFile(), ".webui-bak-" + System.currentTimeMillis());
        if (FileUtil.exist(webuiDir)) {
            if (!webuiDir.renameTo(backup)) {
                FileUtil.del(staging);
                throw new IllegalStateException("旧 WebUI 目录改名失败, 中止更新");
            }
        }
        if (!staging.renameTo(webuiDir)) {
            // staging 改名失败, 回滚旧目录
            if (FileUtil.exist(backup)) {
                if (!backup.renameTo(webuiDir)) {
                    log.error("WebUI 回滚失败, 请检查 {} 目录", webuiDir);
                }
            }
            FileUtil.del(staging);
            throw new IllegalStateException("WebUI 目录替换失败, 中止更新");
        }

        FileUtil.del(backup);
    }

    public void delete() {
        File webuiDir = getWebUIDir();
        FileUtil.del(webuiDir);

        log.info("已删除 WebUI");
    }
}

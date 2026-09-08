package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ClearService {
    /**
     * 清理父级空文件夹
     *
     * @param path
     */
    public void clearParentFile(String path) {
        clearParentFile(new File(path));
    }

    /**
     * 清理父级空文件夹
     *
     * @param file
     */
    public void clearParentFile(File file) {
        if (file.exists()) {
            return;
        }
        File parentFile = file.getParentFile();

        if (Objects.isNull(parentFile)) {
            return;
        }

        if (!hasDeletableContentOnly(parentFile)) {
            // 不为空则不进行清理
            return;
        }

        // 保护窗口: 目录最近 60 秒内有写入(如 rename 任务正在落新文件), 跳过删除
        long lastModified = parentFile.lastModified();
        if (lastModified > 0 && System.currentTimeMillis() - lastModified < 60_000L) {
            log.debug("目录 {} 最近 60 秒内有写入, 跳过本次清理", parentFile);
            return;
        }

        // 二次复查: 首次 list 与删除之间存在竞态窗口, 仍需"仅剩可清理内容"才删除
        if (!hasDeletableContentOnly(parentFile)) {
            log.debug("目录 {} 二次复查发现新文件, 跳过本次清理", parentFile);
            return;
        }

        log.info("清理空文件夹 {}", parentFile);
        FileUtil.del(parentFile);
        clearParentFile(parentFile);
    }

    /**
     * 目录剔除元数据/海报等可清理文件后是否已无有效内容
     */
    private boolean hasDeletableContentOnly(File dir) {
        List<String> list = Arrays.asList(ObjectUtil.defaultIfNull(dir.list(), new String[]{}));
        list = list.stream()
                .filter(f -> !f.endsWith(".nfo"))
                .filter(f -> !f.endsWith("-thumb.jpg"))
                .filter(f -> !f.equals("poster.jpg"))
                .filter(f -> !f.equals("clearlogo.png"))
                .filter(f -> !f.equals(".DS_Store"))
                .filter(f -> !f.equals("banner.jpg"))
                .filter(f -> !f.equals("season-specials-poster.jpg"))
                .filter(f -> !ReUtil.contains("^season\\d+-poster.jpg$", f))
                .filter(f -> !ReUtil.contains("^fanart\\d*.jpg$", f))
                .toList();
        return list.isEmpty();
    }

    public Long clearCover() {
        File configDir = ConfigUtil.getConfigDir();
        String configDirStr = FileUtils.getAbsolutePath(configDir);
        File filesDir = new File(configDirStr, "files");
        File imgDir = new File(configDirStr, "img");

        FileUtil.mkdir(filesDir);
        FileUtil.mkdir(imgDir);

        Set<String> covers = AniUtil.getAniList()
                .stream()
                .map(Ani::getCover)
                .filter(StrUtil::isNotBlank)
                .map(s -> FileUtils.getAbsolutePath(Path.of(configDirStr, "files", s).toFile()))
                .collect(Collectors.toSet());

        Set<File> files = FileUtil.loopFiles(filesDir)
                .stream()
                .filter(file -> {
                    String fileName = FileUtils.getAbsolutePath(file);
                    return !covers.contains(fileName);
                }).collect(Collectors.toSet());
        long filesSize = files.stream()
                .mapToLong(File::length)
                .sum();
        long imgSize = FileUtil.size(imgDir);

        for (File file : files) {
            FileUtil.del(file);
            clearParentFile(file);
        }

        return filesSize + imgSize;
    }

}

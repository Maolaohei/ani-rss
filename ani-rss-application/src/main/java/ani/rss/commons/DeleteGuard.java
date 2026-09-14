package ani.rss.commons;

import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 递归删除的安全闸门。
 * <p>
 * 删除订阅时可以勾选「同时删除本地文件」，而待删路径来自
 * {@code DownloadService#getDownloadPath}，其值最终取决于订阅的「自定义下载位置」或全局下载模板。
 * 这条链路有两个现实风险：
 * <ul>
 *   <li>模板为空时 {@link FileUtils#getAbsolutePath(File)} 会退回<b>进程工作目录</b>，
 *       也就是程序安装目录（内含 {@code config/}、{@code logs/}），一次递归删除即毁掉配置；</li>
 *   <li>模板被填成 {@code /}、{@code D:/} 这类文件系统根，递归删除波及整块磁盘。</li>
 * </ul>
 * 前端虽然有二次确认，但直接调用接口同样能触发删除，因此<b>必须在后端兜底</b>。
 * <p>
 * 判定方式：拒绝"过于宽泛"的路径，即
 * <b>等于受保护目录、或位于受保护目录上层</b>的路径。
 * 受保护目录包括：各文件系统根、进程工作目录、用户主目录、配置目录。
 * 受保护目录的<b>子孙</b>不受限制 —— 那正是正常的下载文件夹。
 */
@Slf4j
public final class DeleteGuard {

    private DeleteGuard() {
    }

    /**
     * 判断某路径是否可以被安全地递归删除
     *
     * @param file 待删除路径
     * @return {@code true} 表示可以删除
     */
    public static boolean isSafeToDeleteRecursively(File file) {
        if (file == null) {
            return false;
        }
        String absolute = FileUtils.getAbsolutePath(file);
        if (StrUtil.isBlank(absolute)) {
            log.warn("拒绝删除空路径");
            return false;
        }

        Path target;
        try {
            target = Paths.get(absolute).toAbsolutePath().normalize();
        } catch (Exception e) {
            // 路径含非法字符等, 一律拒绝而不是冒险
            log.warn("拒绝删除无法解析的路径 {}: {}", absolute, ExceptionUtils.getMessage(e));
            return false;
        }

        // 文件系统根 (/、D:/) 没有 parent
        if (target.getParent() == null) {
            log.warn("拒绝删除文件系统根目录 {}", target);
            return false;
        }

        for (Path guarded : guardedPaths()) {
            // 目标就是受保护目录, 或在其上层(删了会连带删掉它) → 拒绝
            if (target.equals(guarded) || guarded.startsWith(target)) {
                log.warn("拒绝删除 {}: 命中受保护目录 {}", target, guarded);
                return false;
            }
        }
        return true;
    }

    /**
     * 受保护目录集合
     */
    private static List<Path> guardedPaths() {
        List<Path> paths = new ArrayList<>();
        // 进程工作目录：模板为空时的兜底值, 通常是程序安装目录
        add(paths, System.getProperty("user.dir"));
        // 用户主目录
        add(paths, System.getProperty("user.home"));
        // 配置目录：默认在 CWD 或 ~/ani-rss 之下, 也可能被环境变量 CONFIG 指到别处
        try {
            add(paths, FileUtils.getAbsolutePath(ConfigUtil.getConfigDir()));
        } catch (Exception e) {
            log.debug("获取配置目录失败: {}", ExceptionUtils.getMessage(e));
        }
        // 各磁盘根
        for (File root : File.listRoots()) {
            add(paths, root.getAbsolutePath());
        }
        return paths;
    }

    private static void add(List<Path> paths, String raw) {
        if (StrUtil.isBlank(raw)) {
            return;
        }
        try {
            paths.add(Paths.get(raw).toAbsolutePath().normalize());
        } catch (Exception ignored) {
            // 解析失败的保护项直接跳过
        }
    }
}

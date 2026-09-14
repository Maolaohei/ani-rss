package ani.rss.commons;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 递归删除安全闸门。
 * <p>
 * 这是「删除订阅并删除本地文件」的最后一道防线，断言必须偏保守：
 * 宽泛路径一律拒绝，正常下载目录必须放行（否则功能会被误伤）。
 */
class DeleteGuardTest {

    private static final File CWD = new File(System.getProperty("user.dir"));

    @Test
    void rejects_null() {
        assertFalse(DeleteGuard.isSafeToDeleteRecursively(null));
    }

    @Test
    void rejects_process_working_directory() {
        // 模板为空时 getAbsolutePath 会退回进程工作目录(程序目录, 内含 config/、logs/)
        assertFalse(DeleteGuard.isSafeToDeleteRecursively(CWD));
    }

    @Test
    void rejects_ancestor_of_working_directory() {
        File parent = CWD.getParentFile();
        assertFalse(DeleteGuard.isSafeToDeleteRecursively(parent));
        assertFalse(DeleteGuard.isSafeToDeleteRecursively(parent.getParentFile()));
    }

    @Test
    void rejects_filesystem_roots() {
        for (File root : File.listRoots()) {
            assertFalse(DeleteGuard.isSafeToDeleteRecursively(root), root.toString());
        }
    }

    @Test
    void rejects_user_home() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return;
        }
        assertFalse(DeleteGuard.isSafeToDeleteRecursively(new File(home)));
    }

    @Test
    void allows_normal_download_folder() {
        // 常见配置：下载到程序目录下的子文件夹 / 主目录下的子文件夹
        assertTrue(DeleteGuard.isSafeToDeleteRecursively(new File(CWD, "番剧")));
        assertTrue(DeleteGuard.isSafeToDeleteRecursively(new File(CWD, "media/番剧/2024")));

        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            assertTrue(DeleteGuard.isSafeToDeleteRecursively(new File(home, "Videos/番剧")));
        }
    }

    @Test
    void allows_relative_path_that_resolves_under_cwd() {
        // 相对路径会先被解析成绝对路径再判定
        assertTrue(DeleteGuard.isSafeToDeleteRecursively(new File("番剧")));
    }
}

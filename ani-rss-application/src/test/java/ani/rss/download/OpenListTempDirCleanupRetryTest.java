package ani.rss.download;

import ani.rss.download.openlist.residual.TempDirDeleteResult;
import ani.rss.entity.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * “删除临时目录”必须是回读确认的动作，而不是发个请求就算完。
 * <p>
 * 线上形态：S1/S1E01.MKV 已归位，S1/S1E01/S1E01.MKV 的删除请求撞上网盘限制
 * （或 115 的 990009 异步删除没落地），旧实现照样打一条“已删除临时目录”、照样判成功、
 * 照样销掉所有落盘痕迹，于是残留永久留在盘上，媒体管理器把它数成第二集。
 * <p>
 * 这里用真实 HTTP mock 走 OpenList 的删除入口，钉住三件事：
 * 删除失败要报 FAILED_RETRYABLE（不许谎报已删除）、网盘恢复后重试能确认删除且不误伤成片、
 * 顶层同名项是文件时必须转人工而不是整树删。
 */
class OpenListTempDirCleanupRetryTest {

    private OpenListWorkflowSimulationTest.MockAlistServer server;
    private String savePath;
    private String tempDirName;
    private String previousConfigDir;

    @BeforeEach
    void setUp() throws IOException {
        server = new OpenListWorkflowSimulationTest.MockAlistServer();
        server.start();
        new OpenListApi().invalidateFindFilesCache();
        savePath = "/追番/Show/Season 1";
        tempDirName = "Show S01E03";
        previousConfigDir = System.getProperty("CONFIG");
        File dir = new File(System.getProperty("java.io.tmpdir"), "ani-rss-cleanup-retry-" + UUID.randomUUID());
        assertTrue(dir.mkdirs());
        System.setProperty("CONFIG", dir.getAbsolutePath());
    }

    @AfterEach
    void tearDown() {
        server.stop();
        OpenListApi.resetRateLimitState();
        if (previousConfigDir == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", previousConfigDir);
        }
    }

    private OpenList openList() {
        Config config = new Config()
                .setDownloadToolHost("http://127.0.0.1:" + server.port())
                .setDownloadToolPassword("mock-token")
                .setDownloadPathTemplate("/追番")
                .setProvider("115 Cloud")
                .setDelete(true)
                .setRename(true)
                .setStandbyRss(false)
                .setCoexist(false)
                .setAlistDownloadTimeout(1)
                .setAlistDownloadRetryNumber(3L)
                .setNotificationConfigList(List.of());
        OpenList openList = new OpenList();
        assertEquals(Boolean.TRUE, openList.login(true, config), "mock login 应成功");
        return openList;
    }

    /** 造出线上那个现场：顶层有成片，临时目录里还留着同一集的副本与字幕 */
    private void givenResidualTempDir() {
        server.putFile(savePath + "/Show S01E03.mkv", 1031L);
        server.putFile(savePath + "/" + tempDirName + "/Show S01E03.mkv", 1031L);
        server.putFile(savePath + "/" + tempDirName + "/Subs/Show S01E03.ass", 41L);
    }

    @Test
    void delete_failure_is_not_reported_as_deleted() {
        givenResidualTempDir();
        server.failRemovePathContains = tempDirName;
        server.failRemoveTimesLeft = 99;
        OpenList openList = openList();

        TempDirDeleteResult result = openList.cleanupTempDownloadDir(savePath, tempDirName, true);

        assertEquals(TempDirDeleteResult.FAILED_RETRYABLE, result,
                "限流/未落地只能说没删成，不能回报已删除");
        assertFalse(result.confirmed(), "没确认删掉，就不能算清理成功");
        assertTrue(server.exists(savePath + "/" + tempDirName),
                "删除失败时目录本就该还在，实际全树=" + server.allPaths());
        assertTrue(server.exists(savePath + "/" + tempDirName + "/Subs/Show S01E03.ass"),
                "整棵残留（含字幕）都还在，才叫没删掉");
    }

    @Test
    void retry_after_recovery_confirms_and_removes_whole_tree() {
        givenResidualTempDir();
        server.failRemovePathContains = tempDirName;
        server.failRemoveTimesLeft = 99;
        OpenList openList = openList();
        assertEquals(TempDirDeleteResult.FAILED_RETRYABLE,
                openList.cleanupTempDownloadDir(savePath, tempDirName, true), "第一次应失败");

        // 网盘恢复（真实场景是清理债务的退避时间到了再试）
        server.failRemoveTimesLeft = 0;
        TempDirDeleteResult result = openList.cleanupTempDownloadDir(savePath, tempDirName, true);

        assertEquals(TempDirDeleteResult.DELETED, result, "回读确认不存在才算删掉");
        assertTrue(result.confirmed());
        assertFalse(server.exists(savePath + "/" + tempDirName),
                "临时目录必须整棵消失，实际全树=" + server.allPaths());
        assertTrue(server.exists(savePath + "/Show S01E03.mkv"), "已归位的成片不许被误删");
    }

    @Test
    void same_name_file_blocks_tree_delete() {
        // 顶层那个位置是文件而不是目录：整树删除会伤到别的东西
        server.putFile(savePath + "/" + tempDirName, 1031L);
        server.putFile(savePath + "/Show S01E03.mkv", 1031L);
        OpenList openList = openList();

        TempDirDeleteResult result = openList.cleanupTempDownloadDir(savePath, tempDirName, true);

        assertEquals(TempDirDeleteResult.MISMATCH_HUMAN, result, "同名是文件时必须转人工，不能瞎删");
        assertFalse(result.confirmed());
        assertTrue(result.needsHuman());
        assertTrue(server.exists(savePath + "/" + tempDirName), "同名文件必须原样保留");
    }
}

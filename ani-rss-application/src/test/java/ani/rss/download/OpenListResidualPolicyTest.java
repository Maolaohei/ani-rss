package ani.rss.download;

import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.OpenListTaskInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenList residual policy / 10008 boundary tests
 */
class OpenListResidualPolicyTest {

    @Test
    void adopt_running_states() {
        for (OpenListTaskInfo.State state : new OpenListTaskInfo.State[]{
                OpenListTaskInfo.State.Pending,
                OpenListTaskInfo.State.Running,
                OpenListTaskInfo.State.Waiting_for_Retry,
                OpenListTaskInfo.State.Preparing_to_Retry
        }) {
            assertEquals(OpenList.ResidualAction.ADOPT,
                    OpenList.decideResidualAction(state, false),
                    "state=" + state);
        }
    }

    @Test
    void delete_duplicate_running_when_already_adopted() {
        assertEquals(OpenList.ResidualAction.DELETE_DUPLICATE_RUNNING,
                OpenList.decideResidualAction(OpenListTaskInfo.State.Running, true));
        assertEquals(OpenList.ResidualAction.DELETE_DUPLICATE_RUNNING,
                OpenList.decideResidualAction(OpenListTaskInfo.State.Pending, true));
    }

    @Test
    void delete_terminal_and_failed_states() {
        for (OpenListTaskInfo.State state : new OpenListTaskInfo.State[]{
                OpenListTaskInfo.State.Succeeded,
                OpenListTaskInfo.State.Error,
                OpenListTaskInfo.State.Failing,
                OpenListTaskInfo.State.Failed,
                OpenListTaskInfo.State.Canceling,
                OpenListTaskInfo.State.Canceled
        }) {
            assertEquals(OpenList.ResidualAction.DELETE,
                    OpenList.decideResidualAction(state, false),
                    "state=" + state);
            assertEquals(OpenList.ResidualAction.DELETE,
                    OpenList.decideResidualAction(state, true),
                    "state=" + state + " with adopted");
        }
    }

    @Test
    void delete_when_state_null() {
        assertEquals(OpenList.ResidualAction.DELETE,
                OpenList.decideResidualAction(null, false));
        assertEquals(OpenList.ResidualAction.DELETE,
                OpenList.decideResidualAction(null, true));
    }

    @Test
    void wash_season_key_null_safe_contract() {
        String seasonKey = null;
        String fileName = "Show.S01E05v2.mkv";
        boolean shouldDelete = seasonKey != null && !seasonKey.isBlank() && fileName.contains(seasonKey);
        assertFalse(shouldDelete);

        seasonKey = "S01E05";
        shouldDelete = seasonKey != null && !seasonKey.isBlank() && fileName.contains(seasonKey);
        assertTrue(shouldDelete);
    }

    @Test
    void isDuplicateOfflineError_detects_10008_and_cn_msg() {
        String cn = "任务已存在，请勿输入重复的链接地址";
        String embedded = "failed to add offline download task: {\"data\":\"xxx\",\"errcode\":10008,\"error_msg\":\"" + cn + "\",\"state\":false}: unexpected error";
        assertTrue(OpenList.isDuplicateOfflineError(embedded));
        assertTrue(OpenList.isDuplicateOfflineError("{\"errcode\":10008}"));
        assertTrue(OpenList.isDuplicateOfflineError(cn));
        assertTrue(OpenList.isDuplicateOfflineError("duplicate task link already exists"));
        assertFalse(OpenList.isDuplicateOfflineError(null));
        assertFalse(OpenList.isDuplicateOfflineError(""));
        assertFalse(OpenList.isDuplicateOfflineError("magnet parse failed"));
        assertFalse(OpenList.isDuplicateOfflineError("network timeout"));
    }

    @Test
    void isDuplicateMagnetCooling_window() {
        ConcurrentHashMap<String, Long> table = new ConcurrentHashMap<>();
        long now = 1_000_000L;
        assertFalse(OpenList.isDuplicateMagnetCooling("abc", now, table));
        table.put("abc", now + 10);
        assertTrue(OpenList.isDuplicateMagnetCooling("ABC", now, table));
        assertFalse(OpenList.isDuplicateMagnetCooling("abc", now + 10, table));
        assertFalse(OpenList.isDuplicateMagnetCooling("abc", now + 11, table));
        assertFalse(OpenList.isDuplicateMagnetCooling(null, now, table));
        assertFalse(OpenList.isDuplicateMagnetCooling("abc", now, null));
    }

    @Test
    void isDuplicateOfflineError_not_confused_with_other_codes() {
        assertFalse(OpenList.isDuplicateOfflineError("{\"errcode\":10007,\"error_msg\":\"other\"}"));
        assertFalse(OpenList.isDuplicateOfflineError("failed to upload"));
    }

    @Test
    void isEpisodeFileName_matches_season_token() {
        String re = "Show Title S01E03";
        assertTrue(OpenList.isEpisodeFileName(re + ".mkv", re));
        assertTrue(OpenList.isEpisodeFileName("foo.S01E03.1080p.mkv", re));
        assertTrue(OpenList.isEpisodeFileName("foo.s01e03.mkv", re));
        assertFalse(OpenList.isEpisodeFileName("foo.S01E01.mkv", re));
        assertFalse(OpenList.isEpisodeFileName("foo.S01E02.mkv", re));
        assertFalse(OpenList.isEpisodeFileName(null, re));
        assertFalse(OpenList.isEpisodeFileName("x.mkv", null));
    }

    @Test
    void nextPollInterval_stages_20s_1m_5m_10m() {
        assertEquals(20_000L, OpenList.nextPollIntervalMs(0));
        assertEquals(60_000L, OpenList.nextPollIntervalMs(1));
        assertEquals(300_000L, OpenList.nextPollIntervalMs(2));
        assertEquals(600_000L, OpenList.nextPollIntervalMs(3));
        assertEquals(600_000L, OpenList.nextPollIntervalMs(10));
        assertEquals(20_000L, OpenList.nextPollIntervalMs(-1));
    }

    @Test
    void isOpenListBusinessOk_requires_body_code_200() {
        assertTrue(OpenList.isOpenListBusinessOk(true, "{\"code\":200,\"message\":\"success\",\"data\":null}"));
        assertTrue(OpenList.isOpenListBusinessOk(true, ""));
        assertTrue(OpenList.isOpenListBusinessOk(true, null));
        // form cancel 假成功：HTTP 200 但 body code=404
        assertFalse(OpenList.isOpenListBusinessOk(true, "{\"code\":404,\"message\":\"task not found\",\"data\":null}"));
        // form delete_some 空 data 但 code=200：业务成功（是否真正删除靠 re-list 确认）
        assertTrue(OpenList.isOpenListBusinessOk(true, "{\"code\":200,\"message\":\"success\",\"data\":{}}"));
        assertFalse(OpenList.isOpenListBusinessOk(false, "{\"code\":200}"));
        assertFalse(OpenList.isOpenListBusinessOk(true, "not-json"));
    }


    @Test
    void classifyResidual_active_and_terminal() {
        assertEquals(OpenList.ResidualKind.ACTIVE, OpenList.classifyResidual(OpenListTaskInfo.State.Pending));
        assertEquals(OpenList.ResidualKind.ACTIVE, OpenList.classifyResidual(OpenListTaskInfo.State.Running));
        assertEquals(OpenList.ResidualKind.ACTIVE, OpenList.classifyResidual(OpenListTaskInfo.State.Waiting_for_Retry));
        assertEquals(OpenList.ResidualKind.ACTIVE, OpenList.classifyResidual(OpenListTaskInfo.State.Preparing_to_Retry));

        assertEquals(OpenList.ResidualKind.TERMINAL, OpenList.classifyResidual(OpenListTaskInfo.State.Succeeded));
        assertEquals(OpenList.ResidualKind.TERMINAL, OpenList.classifyResidual(OpenListTaskInfo.State.Error));
        assertEquals(OpenList.ResidualKind.TERMINAL, OpenList.classifyResidual(OpenListTaskInfo.State.Failing));
        assertEquals(OpenList.ResidualKind.TERMINAL, OpenList.classifyResidual(OpenListTaskInfo.State.Failed));
        assertEquals(OpenList.ResidualKind.TERMINAL, OpenList.classifyResidual(OpenListTaskInfo.State.Canceling));
        assertEquals(OpenList.ResidualKind.TERMINAL, OpenList.classifyResidual(OpenListTaskInfo.State.Canceled));
        assertEquals(OpenList.ResidualKind.TERMINAL, OpenList.classifyResidual(null));
    }

    @Test
    void taskNameContainsHash_case_insensitive() {
        assertTrue(OpenList.taskNameContainsHash("magnet:?xt=urn:btih:ABCDEF1234", "abcdef1234"));
        assertTrue(OpenList.taskNameContainsHash("offline-ABCDEF1234.task", "ABCDEF1234"));
        assertFalse(OpenList.taskNameContainsHash("offline-other", "abcdef1234"));
        assertFalse(OpenList.taskNameContainsHash(null, "abc"));
        assertFalse(OpenList.taskNameContainsHash("name", null));
        assertFalse(OpenList.taskNameContainsHash("", "abc"));
    }

    @Test
    void residualSnapshot_empty_defaults() {
        OpenList.ResidualSnapshot empty = OpenList.ResidualSnapshot.empty();
        assertEquals(0, empty.getActiveCount());
        assertEquals(0, empty.getTerminalCount());
        assertEquals(0, empty.getTotalCount());
        assertEquals(Boolean.FALSE, empty.getCleaning());
        assertNotNull(empty.getSamples());
        assertTrue(empty.getSamples().isEmpty());
        assertNotNull(empty.getItems());
        assertTrue(empty.getItems().isEmpty());
    }

    @Test
    void buildResidualSnapshot_classifies_and_marks_protected_preview() {
        String protectHash = "abcdef1234";
        List<OpenListTaskInfo> tasks = List.of(
                new OpenListTaskInfo()
                        .setId("t1")
                        .setName("magnet:?xt=urn:btih:ABCDEF1234")
                        .setState(OpenListTaskInfo.State.Running)
                        .setProgress(40),
                new OpenListTaskInfo()
                        .setId("t2")
                        .setName("old-done.task")
                        .setState(OpenListTaskInfo.State.Succeeded)
                        .setError(null),
                new OpenListTaskInfo()
                        .setId("t3")
                        .setName("failed-offline")
                        .setState(OpenListTaskInfo.State.Failed)
                        .setError("disk full"),
                // 无 id 应跳过
                new OpenListTaskInfo().setId("").setName("skip").setState(OpenListTaskInfo.State.Running),
                // 重复 id 应去重
                new OpenListTaskInfo().setId("t2").setName("dup").setState(OpenListTaskInfo.State.Failed)
        );

        OpenList.ResidualSnapshot snap = OpenList.buildResidualSnapshot(tasks, Set.of(protectHash), false, 123L);

        assertEquals(1, snap.getActiveCount());
        assertEquals(2, snap.getTerminalCount());
        assertEquals(3, snap.getTotalCount());
        assertEquals(123L, snap.getScannedAt());
        assertEquals(Boolean.FALSE, snap.getCleaning());
        assertEquals(3, snap.getItems().size());
        assertTrue(snap.getMessage().contains("进行中 1"));
        assertTrue(snap.getMessage().contains("终态 2"));

        OpenList.ResidualItem protectedItem = snap.getItems().stream()
                .filter(i -> "t1".equals(i.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("ACTIVE", protectedItem.getKind());
        assertEquals(Boolean.TRUE, protectedItem.getProtectedCurrent());
        assertTrue(protectedItem.getAction().contains("保护"));
        assertEquals(40, protectedItem.getProgress());

        OpenList.ResidualItem terminal = snap.getItems().stream()
                .filter(i -> "t2".equals(i.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("TERMINAL", terminal.getKind());
        assertEquals(Boolean.FALSE, terminal.getProtectedCurrent());
        assertEquals("删除记录", terminal.getAction());

        OpenList.ResidualItem failed = snap.getItems().stream()
                .filter(i -> "t3".equals(i.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("disk full", failed.getError());
        assertEquals("删除记录", failed.getAction());
    }

    @Test
    void buildResidualSnapshot_empty_tasks() {
        OpenList.ResidualSnapshot snap = OpenList.buildResidualSnapshot(List.of(), Set.of(), true, 1L);
        assertEquals(0, snap.getTotalCount());
        assertEquals("无离线残留", snap.getMessage());
        assertTrue(snap.getItems().isEmpty());
        assertEquals(Boolean.TRUE, snap.getCleaning());
    }

    @Test
    void residualActionLabel_matrix() {
        assertEquals("保护中（当前 RSS 等待，清理时跳过）",
                OpenList.residualActionLabel(OpenList.ResidualKind.ACTIVE, true));
        assertEquals("取消并删除记录",
                OpenList.residualActionLabel(OpenList.ResidualKind.ACTIVE, false));
        assertEquals("删除记录",
                OpenList.residualActionLabel(OpenList.ResidualKind.TERMINAL, false));
        assertEquals("保护中（当前 RSS 等待，清理时跳过）",
                OpenList.residualActionLabel(OpenList.ResidualKind.TERMINAL, true));
    }

    @Test
    void toResidualItem_null_safe() {
        OpenList.ResidualItem item = OpenList.toResidualItem(null, Set.of("abc"));
        assertEquals("", item.getId());
        assertEquals("TERMINAL", item.getKind());
        assertEquals("Unknown", item.getState());
        assertEquals(Boolean.FALSE, item.getProtectedCurrent());
    }

    @Test
    void junk_and_protected_temp_file_classifier() {
        assertTrue(OpenList.isJunkTempFile(new OpenListFileInfo().setName("a.aria2").setIsDir(false)));
        assertTrue(OpenList.isJunkTempFile(new OpenListFileInfo().setName("x.tmp").setIsDir(false)));
        assertFalse(OpenList.isJunkTempFile(new OpenListFileInfo().setName("a.mp4").setIsDir(false)));
        assertFalse(OpenList.isJunkTempFile(new OpenListFileInfo().setName("notes.txt").setIsDir(false)));

        assertTrue(OpenList.isProtectedTempFile(new OpenListFileInfo().setName("a.mp4").setIsDir(false)));
        assertTrue(OpenList.isProtectedTempFile(new OpenListFileInfo().setName("a.ass").setIsDir(false)));
        assertTrue(OpenList.isProtectedTempFile(new OpenListFileInfo().setName("notes.txt").setIsDir(false)));
        assertFalse(OpenList.isProtectedTempFile(new OpenListFileInfo().setName("a.aria2").setIsDir(false)));
        assertFalse(OpenList.isProtectedTempFile(new OpenListFileInfo().setName("dir").setIsDir(true)));
    }

    @Test
    void isUnderPath_matches_temp_prefix() {
        OpenListFileInfo nested = new OpenListFileInfo()
                .setPath("/media/Show/Season 1/[ANi] 盗墓王 - 03")
                .setName("ep.mp4");
        assertTrue(OpenList.isUnderPath(nested, "/media/Show/Season 1/[ANi] 盗墓王 - 03"));
        assertTrue(OpenList.isUnderPath(nested, "/media/Show/Season 1"));
        assertFalse(OpenList.isUnderPath(nested, "/media/Show/Season 1/Show S01E03.mp4"));
        assertFalse(OpenList.isUnderPath(nested, null));
        assertFalse(OpenList.isUnderPath(null, "/media"));
    }

    @Test
    void collection_uses_subscription_template_instead_of_source_title() {
        OpenList openList = new OpenList();

        // 正确路径：finalRenameBase 是订阅模板结果，而不是 RSS 源标题
        assertEquals("碧蓝之海 S03E03",
                openList.collectionEpisodeReName(
                        "[ANi] GRAND BLUE 碧藍之海 3 - 03 [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4",
                        "碧蓝之海 S03E01",
                        3));

        // 错误回归：源标题不应再作为最终命名基名
        assertNotEquals("[ANi] Grand Blue Dreaming S03E03",
                openList.collectionEpisodeReName(
                        "[ANi] GRAND BLUE 碧藍之海 3 - 03 [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4",
                        "碧蓝之海 S03E01",
                        3));
    }

    @Test
    void collection_episode_replaces_existing_template() {
        OpenList openList = new OpenList();

        assertEquals("Show S03E03",
                openList.collectionEpisodeReName("Show - 03.mkv", "Show S03E01", 3));
        assertEquals("Show.E03",
                openList.collectionEpisodeReName("Show - 03.mkv", "Show.E01", 3));
        assertEquals("Show S03E03.5",
                openList.collectionEpisodeReName("Show - 03.5.mkv", "Show S03E01", 3));
    }

    @Test
    void collection_episode_keeps_name_when_episode_cannot_be_extracted() {
        OpenList openList = new OpenList();

        // 无集数文件（特典/CM/SP 等）保留原文件名，避免与正片模板名（含 SxxExx）冲突覆盖；
        // 测试输入 reName="Show" 仅为模板基名示例，真实场景 reName 含集数
        assertEquals("Show movie", openList.collectionEpisodeReName("Show movie.mkv", "Show", 3));
    }

    @Test
    void shouldDeleteTidOnRelease_only_newly_submitted_when_delete_enabled() {
        // delete=true + 本次新提交 → 删除
        assertTrue(OpenList.shouldDeleteTidOnRelease("tid-1", true, true));
        // delete=true + 复用任务（10008 切换的 otherTid 或历史轮次）→ 不删除，避免中断他人下载
        assertFalse(OpenList.shouldDeleteTidOnRelease("tid-2", false, true));
        // delete=false → 不删除
        assertFalse(OpenList.shouldDeleteTidOnRelease("tid-3", true, false));
        // tid 为空 → 不删除
        assertFalse(OpenList.shouldDeleteTidOnRelease(null, true, true));
        // delete 未配置（null）→ 不删除
        assertFalse(OpenList.shouldDeleteTidOnRelease("tid-4", true, null));
    }

    @Test
    void collection_validation_counts_only_expected_episodes_and_preserves_half_episode() {
        OpenList openList = new OpenList();
        List<OpenListFileInfo> videos = List.of(
                new OpenListFileInfo().setName("Show S03E01.mkv"),
                new OpenListFileInfo().setName("Show S03E02.mkv"),
                new OpenListFileInfo().setName("Show S03E03.5.mkv"));

        OpenList.EpisodeValidation validation = openList.validateCollectionEpisodes(
                List.of(3.0, 3.5), videos);

        assertEquals(List.of(3.0, 3.5), validation.expected());
        assertEquals(List.of(3.5), validation.matched());
        assertEquals(List.of(3.0), validation.missing());
    }

    @Test
    void collection_validation_deduplicates_expected_and_downloaded_episodes() {
        OpenList openList = new OpenList();
        List<OpenListFileInfo> videos = List.of(
                new OpenListFileInfo().setName("Show - 03.mkv"),
                new OpenListFileInfo().setName("Show - 03.zh-CN.ass"),
                new OpenListFileInfo().setName("Show S03E03.mp4"));

        OpenList.EpisodeValidation validation = openList.validateCollectionEpisodes(
                List.of(3.0, 3.0), videos);

        assertEquals(List.of(3.0), validation.expected());
        assertEquals(List.of(3.0), validation.matched());
        assertTrue(validation.missing().isEmpty());
    }

    @Test
    void transient_openlist_failures_are_retried_but_business_errors_are_not() {
        AtomicInteger transientAttempts = new AtomicInteger();
        String result = OpenList.retryIdempotent("test", () -> {
            if (transientAttempts.incrementAndGet() < 3) {
                throw new IllegalStateException("Read timed out");
            }
            return "ok";
        }, new long[]{0L, 0L});

        assertEquals("ok", result);
        assertEquals(3, transientAttempts.get());

        AtomicInteger businessAttempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> OpenList.retryIdempotent("test", () -> {
            businessAttempts.incrementAndGet();
            throw new IllegalStateException("permission denied");
        }, new long[]{0L, 0L}));
        assertEquals(1, businessAttempts.get());
    }

    @Test
    void transient_openlist_failure_classifier_covers_timeouts_and_gateway_errors() {
        assertTrue(OpenList.isTransientOpenListFailure(new IllegalStateException("Read timed out")));
        assertTrue(OpenList.isTransientOpenListFailure(new IllegalStateException("url status: 503")));
        assertTrue(OpenList.isTransientOpenListFailure(
                new IllegalStateException("wrapper", new java.net.SocketTimeoutException("timeout"))));
        assertFalse(OpenList.isTransientOpenListFailure(new IllegalStateException("permission denied")));
    }

    @Test
    void timeout_file_snapshot_tracks_count_and_total_size() {
        OpenList.TimeoutFileSnapshot snapshot = OpenList.snapshotTimeoutFiles(Map.of(
                "/show/ep03.mkv", 1_000L,
                "/show/ep03-extra.mp4", 20L));

        assertEquals(2, snapshot.videoCount());
        assertEquals(1_020L, snapshot.totalBytes());
        assertEquals(2, snapshot.files().size());
    }

    @Test
    void timeout_snapshot_requires_every_expected_collection_episode() {
        OpenList openList = new OpenList();
        OpenList.TimeoutFileSnapshot snapshot = OpenList.snapshotTimeoutFiles(Map.of(
                "/show/Show S03E03.mkv", 1_000L));

        assertTrue(openList.snapshotCoversExpectedEpisodes(snapshot, List.of(3.0)));
        assertFalse(openList.snapshotCoversExpectedEpisodes(snapshot, List.of(3.0, 4.0)));
    }

}

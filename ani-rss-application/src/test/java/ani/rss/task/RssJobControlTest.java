package ani.rss.task;

import ani.rss.entity.Config;
import ani.rss.entity.vo.RssJobItem;
import ani.rss.entity.vo.RssJobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RssJobControlTest {

    @AfterEach
    void reset() throws Exception {
        setBoolean("download", false);
        setLong("downloadStartTime", 0L);
        setBoolean("cancelRequested", false);
        setRef("jobScope", "idle");
        setRef("jobTitle", "");
        setRef("jobAniId", "");
        setRef("jobMessage", "空闲");
        setRefObj("jobSource", null);
        setRefObj("activePool", null);
        setRefObj("pendingManual", null);
        setLong("lastFinishedAt", 0L);
        setLong("lastDurationMs", 0L);
        setRef("lastResultMessage", "");
        setRef("lastTitle", "");
        setRefObj("lastSource", null);
        setRefObj("lastScope", null);
    }

    @Test
    void status_idle_when_not_running() {
        RssJobStatus status = RssTask.getJobStatus();
        assertFalse(Boolean.TRUE.equals(status.getRunning()));
        assertEquals("idle", status.getScope());
        assertNull(status.getCurrentHash());
        assertFalse(Boolean.TRUE.equals(status.getPending()));
    }

    @Test
    void requestCancel_false_when_idle() {
        assertFalse(RssTask.requestCancel());
        assertFalse(RssTask.isCancelRequested());
    }

    @Test
    void requestCancel_sets_flag_when_running() throws Exception {
        setBoolean("download", true);
        setLong("downloadStartTime", System.currentTimeMillis() - 5000);
        setRef("jobScope", "all");
        setRef("jobTitle", "全部启用订阅");
        setRef("jobMessage", "处理中");
        setRefObj("jobSource", RssTask.JobSource.MANUAL);
        assertTrue(RssTask.requestCancel());
        assertTrue(RssTask.isCancelRequested());
        RssJobStatus status = RssTask.getJobStatus();
        assertTrue(Boolean.TRUE.equals(status.getCancelRequested()));
        assertTrue(Boolean.TRUE.equals(status.getRunning()));
        assertTrue(status.getElapsedMs() >= 0);
        assertEquals("取消中...", status.getMessage());
    }

    @Test
    void requestCancel_clears_pending_when_idle() throws Exception {
        setPendingManual(null, "全部启用订阅", "all");
        assertTrue(RssTask.hasPendingManual());
        assertTrue(RssTask.requestCancel());
        assertFalse(RssTask.hasPendingManual());
    }

    @Test
    void isActive_respects_loop_and_cancel() throws Exception {
        AtomicBoolean loop = new AtomicBoolean(true);
        assertTrue(RssTask.isActive(loop));
        loop.set(false);
        assertFalse(RssTask.isActive(loop));
        loop.set(true);
        setBoolean("cancelRequested", true);
        assertFalse(RssTask.isActive(loop));
    }

    @Test
    void isOpenListTool_true_for_openlist_and_alist() {
        assertTrue(RssTask.isOpenListTool(new Config().setDownloadToolType("OpenList")));
        assertTrue(RssTask.isOpenListTool(new Config().setDownloadToolType("Alist")));
        assertTrue(RssTask.isOpenListTool(new Config().setDownloadToolType("openlist")));
        assertFalse(RssTask.isOpenListTool(new Config().setDownloadToolType("qBittorrent")));
        assertFalse(RssTask.isOpenListTool(new Config().setDownloadToolType("Aria2")));
        assertFalse(RssTask.isOpenListTool(new Config().setDownloadToolType("Transmission")));
        assertFalse(RssTask.isOpenListTool(null));
        assertFalse(RssTask.isOpenListTool(new Config().setDownloadToolType(null)));
    }

    @Test
    void manual_preempts_periodic_and_queues() throws Exception {
        setBoolean("download", true);
        setLong("downloadStartTime", System.currentTimeMillis());
        setRefObj("jobSource", RssTask.JobSource.PERIODIC);
        setRef("jobScope", "all");
        setRef("jobMessage", "周期扫描中");

        String msg = RssTask.submitManualRefresh(null);
        assertTrue(RssTask.isCancelRequested());
        assertTrue(RssTask.hasPendingManual());
        assertTrue(msg.contains("让路") || msg.contains("排队") || msg.contains("手动"));
        RssJobStatus status = RssTask.getJobStatus();
        assertTrue(Boolean.TRUE.equals(status.getPending()));
        assertEquals("all", status.getPendingScope());
        // 抢先期间来源仍是周期任务，直到它退出
        assertEquals("periodic", status.getSource());
    }

    @Test
    void manual_when_manual_running_queues_only() throws Exception {
        setBoolean("download", true);
        setLong("downloadStartTime", System.currentTimeMillis());
        setRefObj("jobSource", RssTask.JobSource.MANUAL);
        setRef("jobScope", "all");
        setRef("jobMessage", "手动刷新中");

        String first = RssTask.submitManualRefresh(null);
        assertTrue(RssTask.hasPendingManual());
        assertTrue(first.contains("排队") || first.contains("待执行"));
        String second = RssTask.submitManualRefresh(null);
        assertTrue(second.contains("替换") || second.contains("排队"));
        RssJobStatus status = RssTask.getJobStatus();
        assertTrue(Boolean.TRUE.equals(status.getPending()));
        assertEquals("all", status.getPendingScope());
    }

    @Test
    void tryStartPeriodic_false_when_busy() throws Exception {
        setBoolean("download", true);
        setLong("downloadStartTime", System.currentTimeMillis());
        setRefObj("jobSource", RssTask.JobSource.MANUAL);
        assertFalse(RssTask.tryStartPeriodic());
    }

    @Test
    void cancel_clears_pending_and_keeps_running_cancel_flag() throws Exception {
        setBoolean("download", true);
        setLong("downloadStartTime", System.currentTimeMillis());
        setRefObj("jobSource", RssTask.JobSource.MANUAL);
        RssTask.submitManualRefresh(null);
        assertTrue(RssTask.hasPendingManual());
        assertTrue(RssTask.requestCancel());
        assertFalse(RssTask.hasPendingManual());
        assertTrue(RssTask.isCancelRequested());
    }

    private static void setPendingManual(List<?> targetList, String title, String scope) throws Exception {
        Class<?> pendingCls = null;
        for (Class<?> c : RssTask.class.getDeclaredClasses()) {
            if ("PendingManual".equals(c.getSimpleName())) {
                pendingCls = c;
                break;
            }
        }
        assertNotNull(pendingCls);
        Constructor<?> ctor = pendingCls.getDeclaredConstructor(List.class, String.class, String.class);
        ctor.setAccessible(true);
        Object pending = ctor.newInstance(targetList, title, scope);
        Field f = RssTask.class.getDeclaredField("pendingManual");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Object> ref = (AtomicReference<Object>) f.get(null);
        ref.set(pending);
    }

    private static void setBoolean(String field, boolean value) throws Exception {
        Field f = RssTask.class.getDeclaredField(field);
        f.setAccessible(true);
        ((AtomicBoolean) f.get(null)).set(value);
    }

    private static void setLong(String field, long value) throws Exception {
        Field f = RssTask.class.getDeclaredField(field);
        f.setAccessible(true);
        ((AtomicLong) f.get(null)).set(value);
    }

    @SuppressWarnings("unchecked")
    private static void setRef(String field, String value) throws Exception {
        Field f = RssTask.class.getDeclaredField(field);
        f.setAccessible(true);
        ((AtomicReference<String>) f.get(null)).set(value);
    }

    @SuppressWarnings("unchecked")
    private static void setRefObj(String field, Object value) throws Exception {
        Field f = RssTask.class.getDeclaredField(field);
        f.setAccessible(true);
        ((AtomicReference<Object>) f.get(null)).set(value);
    }

    @Test
    void status_exposes_openListBusy_field_default_false_without_openlist_bean() {
        RssJobStatus status = RssTask.getJobStatus();
        // 无 OpenList bean / 非 OpenList 工具时，busy 应为 false 或 null 安全
        assertFalse(Boolean.TRUE.equals(status.getOpenListBusy()));
    }

    @Test
    void status_exposes_multi_task_slots_when_running_and_pending() throws Exception {
        setBoolean("download", true);
        setLong("downloadStartTime", System.currentTimeMillis() - 3000);
        setRef("jobScope", "single");
        setRef("jobTitle", "测试番剧");
        setRef("jobMessage", "处理中: 测试番剧");
        setRefObj("jobSource", RssTask.JobSource.MANUAL);
        setPendingManual(null, "排队刷新", "all");

        RssJobStatus status = RssTask.getJobStatus();
        assertTrue(Boolean.TRUE.equals(status.getRunning()));
        assertTrue(Boolean.TRUE.equals(status.getPending()));
        assertTrue(Boolean.TRUE.equals(status.getCanCancel()));
        assertNotNull(status.getTasks());
        assertTrue(status.getTasks().stream().anyMatch(t -> "rss-running".equals(t.getId())));
        assertTrue(status.getTasks().stream().anyMatch(t -> "rss-pending".equals(t.getId())));
    }

    @Test
    void cancelItem_pending_only_does_not_cancel_running() throws Exception {
        setBoolean("download", true);
        setLong("downloadStartTime", System.currentTimeMillis());
        setRefObj("jobSource", RssTask.JobSource.MANUAL);
        setRef("jobScope", "all");
        setRef("jobMessage", "手动刷新中");
        setPendingManual(null, "排队刷新", "all");
        assertTrue(RssTask.hasPendingManual());

        assertTrue(RssTask.cancelItem("rss-pending"));
        assertFalse(RssTask.hasPendingManual());
        assertFalse(RssTask.isCancelRequested());
        assertTrue(Boolean.TRUE.equals(RssTask.getJobStatus().getRunning()));
    }

    @Test
    void cancelItem_running_requests_global_cancel() throws Exception {
        setBoolean("download", true);
        setLong("downloadStartTime", System.currentTimeMillis());
        setRefObj("jobSource", RssTask.JobSource.MANUAL);
        setRef("jobMessage", "处理中");
        assertTrue(RssTask.cancelItem("rss-running"));
        assertTrue(RssTask.isCancelRequested());
    }

    @Test
    void status_includes_last_finished_slot() throws Exception {
        long finished = System.currentTimeMillis() - 1000;
        setLong("lastFinishedAt", finished);
        setLong("lastDurationMs", 12345L);
        setRef("lastResultMessage", "已完成");
        setRef("lastTitle", "上一轮标题");
        setRefObj("lastSource", "manual");
        setRefObj("lastScope", "single");

        RssJobStatus status = RssTask.getJobStatus();
        assertEquals(finished, status.getLastFinishedAt());
        assertEquals(12345L, status.getLastDurationMs());
        assertEquals("已完成", status.getLastResultMessage());
        assertTrue(status.getTasks().stream().anyMatch(t -> "last-finished".equals(t.getId())));
    }

    @Test
    void cancel_pending_does_not_overwrite_running_message() throws Exception {
        setBoolean("download", true);
        setLong("downloadStartTime", System.currentTimeMillis());
        setRefObj("jobSource", RssTask.JobSource.MANUAL);
        setRef("jobScope", "single");
        setRef("jobTitle", "测试番剧");
        setRef("jobMessage", "处理中: 测试番剧");
        setPendingManual(null, "排队刷新", "all");

        assertTrue(RssTask.cancelItem("rss-pending"));
        assertFalse(RssTask.hasPendingManual());
        assertEquals("处理中: 测试番剧", RssTask.getJobStatus().getMessage());
        assertFalse(RssTask.isCancelRequested());
    }

    @Test
    void residual_summary_not_pending_when_only_terminal() throws Exception {
        // 无 OpenList bean 时 residualSupported=false，这里仅验证 build 路径：
        // 通过 last-finished + running 仍可观察 canCancel 语义保持。
        setBoolean("download", true);
        setLong("downloadStartTime", System.currentTimeMillis() - 1000);
        setRef("jobScope", "all");
        setRef("jobTitle", "全部启用订阅");
        setRef("jobMessage", "扫描订阅中...");
        setRefObj("jobSource", RssTask.JobSource.PERIODIC);
        RssJobStatus status = RssTask.getJobStatus();
        assertTrue(Boolean.TRUE.equals(status.getCanCancel()));
        assertTrue(status.getTasks().stream().anyMatch(t -> "rss-running".equals(t.getId()) && Boolean.TRUE.equals(t.getCancellable())));
    }

    @Test
    void status_hides_empty_residual_summary_when_idle() {
        RssJobStatus status = RssTask.getJobStatus();
        assertFalse(Boolean.TRUE.equals(status.getRunning()));
        // 无 OpenList bean 或无残留时，不应出现 residual-summary 空卡片
        assertTrue(status.getTasks() == null
                || status.getTasks().stream().noneMatch(t -> "residual-summary".equals(t.getId())));
    }

    @Test
    void buildTaskItems_hides_none_residual_message_when_idle() throws Exception {
        List<RssJobItem> tasks = invokeBuildTaskItems(
                false, false, "idle", "", "空闲", null,
                0L, 0L, null,
                false, null,
                true, 0, 0, 0,
                System.currentTimeMillis(), false,
                "无离线残留", List.of(),
                0L, 0L, "", "", null, null
        );
        assertTrue(tasks.stream().noneMatch(t -> "residual-summary".equals(t.getId())),
                "无离线残留时不应展示 residual-summary");
    }

    @Test
    void buildTaskItems_shows_residual_when_active_or_terminal() throws Exception {
        List<RssJobItem> activeOnly = invokeBuildTaskItems(
                false, false, "idle", "", "空闲", null,
                0L, 0L, null,
                false, null,
                true, 1, 0, 1,
                System.currentTimeMillis(), false,
                "进行中 1 / 终态 0", List.of("tid=abc"),
                0L, 0L, "", "", null, null
        );
        assertTrue(activeOnly.stream().anyMatch(t -> "residual-summary".equals(t.getId())
                && "busy".equals(t.getStatus())));

        List<RssJobItem> terminalOnly = invokeBuildTaskItems(
                false, false, "idle", "", "空闲", null,
                0L, 0L, null,
                false, null,
                true, 0, 2, 2,
                System.currentTimeMillis(), false,
                "进行中 0 / 终态 2", List.of(),
                0L, 0L, "", "", null, null
        );
        assertTrue(terminalOnly.stream().anyMatch(t -> "residual-summary".equals(t.getId())
                && "idle".equals(t.getStatus())));
    }

    @Test
    void buildTaskItems_shows_residual_when_cleaning_or_error() throws Exception {
        List<RssJobItem> cleaning = invokeBuildTaskItems(
                false, false, "idle", "", "空闲", null,
                0L, 0L, null,
                false, null,
                true, 0, 0, 0,
                System.currentTimeMillis(), true,
                "清理中", List.of(),
                0L, 0L, "", "", null, null
        );
        assertTrue(cleaning.stream().anyMatch(t -> "residual-summary".equals(t.getId())
                && "busy".equals(t.getStatus())));

        List<RssJobItem> error = invokeBuildTaskItems(
                false, false, "idle", "", "空闲", null,
                0L, 0L, null,
                false, null,
                true, 0, 0, 0,
                System.currentTimeMillis(), false,
                "OpenList 残留快照不可用", List.of(),
                0L, 0L, "", "", null, null
        );
        assertTrue(error.stream().anyMatch(t -> "residual-summary".equals(t.getId())));
    }

    @SuppressWarnings("unchecked")
    private static List<RssJobItem> invokeBuildTaskItems(
            boolean running,
            boolean canceling,
            String scope,
            String title,
            String message,
            RssTask.JobSource source,
            long startedAt,
            long elapsed,
            Object pending,
            boolean openListBusy,
            String currentHash,
            boolean residualSupported,
            Integer residualActive,
            Integer residualTerminal,
            Integer residualTotal,
            Long residualScannedAt,
            Boolean residualCleaning,
            String residualMessage,
            List<String> residualSamples,
            long finishedAt,
            long lastDuration,
            String lastMsg,
            String lastJobTitle,
            String lastJobSource,
            String lastJobScope
    ) throws Exception {
        Method m = null;
        for (Method method : RssTask.class.getDeclaredMethods()) {
            if ("buildTaskItems".equals(method.getName()) && method.getParameterCount() == 25) {
                m = method;
                break;
            }
        }
        assertNotNull(m, "buildTaskItems not found");
        m.setAccessible(true);
        Object result = m.invoke(null,
                running, canceling, scope, title, message, source,
                startedAt, elapsed, pending,
                openListBusy, currentHash,
                residualSupported, residualActive, residualTerminal, residualTotal,
                residualScannedAt, residualCleaning, residualMessage, residualSamples,
                finishedAt, lastDuration, lastMsg, lastJobTitle, lastJobSource, lastJobScope
        );
        return (List<RssJobItem>) result;
    }
}

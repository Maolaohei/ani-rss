package ani.rss.download.openlist.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenList 跨实例共享的运行时状态。
 * <p>
 * 当前批次只收敛提交去重、当前等待中的 hash 与 10008 冷却表；其它静态状态仍留在
 * {@code OpenList}，等 Phase 3 再迁移。
 */
public final class OpenListRuntimeState {
    public static final OpenListRuntimeState INSTANCE = new OpenListRuntimeState();

    private final Set<String> inFlightTasks = ConcurrentHashMap.newKeySet();
    private final Set<String> currentInfoHashes = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> duplicateMagnetUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> duplicateMagnetLongUntil = new ConcurrentHashMap<>();

    private OpenListRuntimeState() {
    }

    /**
     * 提交中的 infoHash 占位。
     */
    public Set<String> inFlightTasks() {
        return inFlightTasks;
    }

    /**
     * 当前正在等待的离线 infoHash；任务管理器展示、取消清理和残留保护共用。
     */
    public Set<String> currentInfoHashes() {
        return currentInfoHashes;
    }

    /**
     * 10008 短冷却表。
     */
    public ConcurrentHashMap<String, Long> duplicateMagnetUntil() {
        return duplicateMagnetUntil;
    }

    /**
     * 10008 去重死锁长冷却表。
     */
    public ConcurrentHashMap<String, Long> duplicateMagnetLongUntil() {
        return duplicateMagnetLongUntil;
    }

    /**
     * 仅用于测试隔离，清理本状态对象当前承载的全部表。
     */
    public void resetForTests() {
        inFlightTasks.clear();
        currentInfoHashes.clear();
        duplicateMagnetUntil.clear();
        duplicateMagnetLongUntil.clear();
    }
}

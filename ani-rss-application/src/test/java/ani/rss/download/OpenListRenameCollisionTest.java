package ani.rss.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归位重命名的目标名去重。
 * <p>
 * 背景（2026-09-18 线上日志）：同一集在网盘上有两份文件时（重复下单的产物），
 * 「保留原名」分支会把<b>已经在目标位置</b>的那份写成 {@code X -> X} 恒等映射，
 * 随后目标名去重把它当成"两个源抢同一个名字"抛 {@code IllegalStateException}。
 * 结果是文件其实已经落盘的集被判"离线失败"→ 清 pending → 下一轮重下 → 再撞名，
 * 无限循环且每轮往网盘多堆一份文件。
 * <p>
 * 现在改为"撞名则避让"：已在目标位置的条目优先占位，其余退回原名，原名也被占用才加序号。
 */
class OpenListRenameCollisionTest {

    private static final String TARGET = "虽然我是不完美恶女 S01E01.mkv";
    private static final String RAW = "[LoliHouse] Futsutsuka na Akujo dewa Gozaimasu ga - 01 [WebRip].mkv";

    /**
     * 恒等映射必须被识别为"已就位"并优先占位，原始名那份退回原名——不再有任何重复目标名。
     */
    @Test
    @DisplayName("已在目标位置的文件占位，另一份退回原名")
    void identity_entry_wins_and_other_falls_back() {
        Map<String, String> renameMap = new HashMap<>();
        renameMap.put(RAW, TARGET);
        renameMap.put(TARGET, TARGET);
        // 前置条件：输入确实存在重复目标名，否则本用例对"去重"毫无约束力
        assertHasDuplicateTargets(renameMap);

        OpenList.dedupeRenameTargets(renameMap);

        assertEquals(TARGET, renameMap.get(TARGET), "已就位的文件必须保留目标名");
        assertEquals(RAW, renameMap.get(RAW), "另一份应退回原名，避免覆盖");
        assertNoDuplicateTargets(renameMap);
    }

    /**
     * 处理顺序不应影响结果：HashMap 的迭代顺序不稳定，若结果依赖顺序，
     * 同一个场景会在不同轮次表现不同，排查时无法复现。
     */
    @Test
    @DisplayName("结果与插入顺序无关")
    void result_is_order_independent() {
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put(TARGET, TARGET);
        reversed.put(RAW, TARGET);

        OpenList.dedupeRenameTargets(reversed);

        assertEquals(TARGET, reversed.get(TARGET));
        assertEquals(RAW, reversed.get(RAW));
        assertNoDuplicateTargets(reversed);
    }

    /**
     * 两份都还没改名（都不是恒等映射）时，后处理的必须让位——否则其中一个会被覆盖掉。
     */
    @Test
    @DisplayName("两份都待改名时，撞名者加序号后缀")
    void both_pending_gets_suffix() {
        Map<String, String> renameMap = new HashMap<>();
        String a = "[A] Show - 01 [CHS].mkv";
        String b = "[B] Show - 01 [CHT].mkv";
        renameMap.put(a, TARGET);
        renameMap.put(b, TARGET);
        assertHasDuplicateTargets(renameMap);

        OpenList.dedupeRenameTargets(renameMap);

        assertNoDuplicateTargets(renameMap);
        assertTrue(renameMap.containsValue(TARGET), "其中一份应拿到目标名");
        long distinct = renameMap.values().stream().distinct().count();
        assertEquals(2, distinct, "两份都应拿到互不相同的名字");
    }

    /**
     * 退回原名时若原名已被别的条目认领，必须再避让一次，不能原地打转。
     * <p>
     * 场景：{@code AAA.mkv} 的目标名恰好就是 {@code RAW} 的原名，而 {@code RAW} 的目标名
     * 已被已就位的那份占用。处理顺序按源名排序，{@code AAA.mkv} 在前先拿走 {@code RAW}，
     * 于是 {@code RAW} 只能拿到带序号的新名字。
     */
    @Test
    @DisplayName("原名也被占用时加序号，不会原地打转")
    void fallback_name_also_taken() {
        String claimed = "AAA.mkv";
        Map<String, String> renameMap = new HashMap<>();
        renameMap.put(TARGET, TARGET);
        renameMap.put(RAW, TARGET);
        renameMap.put(claimed, RAW);

        OpenList.dedupeRenameTargets(renameMap);

        assertNoDuplicateTargets(renameMap);
        assertEquals(RAW, renameMap.get(claimed), "先处理的条目拿走原名");
        assertNotEquals(RAW, renameMap.get(RAW), "原名已被占用，必须再避让");
        assertTrue(renameMap.get(RAW).startsWith("["), "避让后仍应保留原名主体");
    }

    @Test
    @DisplayName("无冲突时不做任何改动")
    void no_collision_keeps_everything() {
        Map<String, String> renameMap = new HashMap<>();
        renameMap.put(RAW, TARGET);
        renameMap.put("other.mkv", "别的 S01E02.mkv");

        OpenList.dedupeRenameTargets(renameMap);

        assertEquals(TARGET, renameMap.get(RAW));
        assertEquals("别的 S01E02.mkv", renameMap.get("other.mkv"));
    }

    private static void assertNoDuplicateTargets(Map<String, String> renameMap) {
        Set<String> targets = renameMap.values().stream().collect(Collectors.toSet());
        assertEquals(renameMap.size(), targets.size(),
                "目标名必须互不相同，否则 fsBatchRename 会互相覆盖: " + renameMap);
    }

    /** 前置条件断言：确保用例喂进去的输入真的存在撞名，否则等于在测一个空壳。 */
    private static void assertHasDuplicateTargets(Map<String, String> renameMap) {
        Set<String> targets = renameMap.values().stream().collect(Collectors.toSet());
        assertTrue(targets.size() < renameMap.size(), "用例输入本身应存在重复目标名: " + renameMap);
    }
}

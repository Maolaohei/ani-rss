package ani.rss.util.other;

import ani.rss.entity.Ani;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订阅 id 索引（P2-14）。
 * <p>
 * 全项目有 50+ 处 {@code getAniList().stream().filter(...)}，其中"按 id 反查"最常见
 * （预览回写、批量操作、外部接口）。订阅数上三位数后这些 O(n) 过滤叠加起来是实打实的开销。
 * <p>
 * 索引是<b>惰性构建 + 结构变更时失效</b>，因此这里要固化两条不变量：
 * <ol>
 *   <li>查得到、且拿到的是<b>活对象</b>（调用方要往它上面回写运行时状态）；</li>
 *   <li>列表变化后<b>绝不能返回已删除的订阅</b>——这是索引类优化最容易踩的坑，
 *       一旦陈旧就会导致"对已删除订阅执行下载/改名"这类后果。</li>
 * </ol>
 */
class AniUtilIdIndexTest {

    private List<Ani> backup;

    @BeforeEach
    void setUp() {
        backup = new ArrayList<>(AniUtil.getAniList());
        AniUtil.getAniList().clear();
        AniUtil.invalidateIdIndex();
    }

    @AfterEach
    void tearDown() {
        List<Ani> live = AniUtil.getAniList();
        live.clear();
        live.addAll(backup);
        AniUtil.invalidateIdIndex();
    }

    private static Ani ani(String id, String title) {
        return new Ani().setId(id).setTitle(title).setSeason(1);
    }

    @Test
    void finds_subscription_by_id() {
        Ani ani = ani("i1", "番剧A");
        AniUtil.getAniList().add(ani);
        AniUtil.invalidateIdIndex();

        Optional<Ani> found = AniUtil.findById("i1");

        assertTrue(found.isPresent());
        assertSame(ani, found.get(), "必须返回列表里的活对象，调用方要往它上面回写状态");
    }

    @Test
    void returns_empty_for_unknown_blank_or_null_id() {
        AniUtil.getAniList().add(ani("i2", "番剧B"));
        AniUtil.invalidateIdIndex();

        assertTrue(AniUtil.findById("nope").isEmpty());
        assertTrue(AniUtil.findById("").isEmpty());
        assertTrue(AniUtil.findById("   ").isEmpty());
        assertTrue(AniUtil.findById(null).isEmpty());
    }

    @Test
    void lookup_works_on_an_empty_list() {
        assertTrue(AniUtil.findById("whatever").isEmpty());
    }

    /**
     * 反向验证：列表长度变化后必须立刻能查到新元素，无需显式失效。
     * （索引自带"列表引用 + 元素个数"校验，这是兜底防线。）
     */
    @Test
    void newly_added_subscription_is_visible_without_explicit_invalidation() {
        AniUtil.getAniList().add(ani("a1", "番剧A"));
        AniUtil.invalidateIdIndex();
        assertTrue(AniUtil.findById("a1").isPresent());

        // 只改列表、不调 invalidateIdIndex：个数变了，索引应自行重建
        AniUtil.getAniList().add(ani("a2", "番剧B"));

        assertTrue(AniUtil.findById("a2").isPresent(), "新增的订阅必须能被查到");
    }

    /**
     * 反向验证：显式失效后，已删除的订阅必须查不到。
     * <p>
     * 这条是索引优化的核心风险点：删除与新增个数相同时，仅靠"个数校验"是发现不了的，
     * 所以所有增删点都必须在改完列表后立刻调用 {@code invalidateIdIndex()}。
     */
    @Test
    void removed_subscription_is_not_visible_after_invalidation() {
        Ani a1 = ani("r1", "番剧A");
        Ani a2 = ani("r2", "番剧B");
        AniUtil.getAniList().add(a1);
        AniUtil.getAniList().add(a2);
        AniUtil.invalidateIdIndex();
        assertTrue(AniUtil.findById("r1").isPresent());

        // 删一个、加一个：个数不变，只有显式失效才能保证正确
        AniUtil.getAniList().remove(a1);
        AniUtil.getAniList().add(ani("r3", "番剧C"));
        AniUtil.invalidateIdIndex();

        assertTrue(AniUtil.findById("r1").isEmpty(), "已删除的订阅绝不能被索引残留暴露出来");
        assertTrue(AniUtil.findById("r3").isPresent());
    }

    @Test
    void invalidate_is_idempotent_and_cheap() {
        AniUtil.getAniList().add(ani("d1", "番剧A"));
        assertTrue(AniUtil.findById("d1").isPresent());

        AniUtil.invalidateIdIndex();
        AniUtil.invalidateIdIndex();
        AniUtil.invalidateIdIndex();

        assertTrue(AniUtil.findById("d1").isPresent(), "失效只影响缓存，不影响列表内容");
    }

    @Test
    void entries_without_id_are_skipped() {
        Ani noId = new Ani().setTitle("没有 id 的订阅").setSeason(1);
        AniUtil.getAniList().add(noId);
        AniUtil.getAniList().add(ani("has-id", "有 id 的订阅"));
        AniUtil.invalidateIdIndex();

        assertTrue(AniUtil.findById("has-id").isPresent());
        // 不该因为存在无 id 的条目就崩掉或把索引整体作废
        assertTrue(AniUtil.findById("").isEmpty());
    }

    @Test
    void duplicate_ids_resolve_to_one_of_the_entries() {
        Ani first = ani("dup", "番剧A");
        Ani second = ani("dup", "番剧B");
        AniUtil.getAniList().add(first);
        AniUtil.getAniList().add(second);
        AniUtil.invalidateIdIndex();

        Optional<Ani> found = AniUtil.findById("dup");
        assertTrue(found.isPresent());
        assertTrue(found.get() == first || found.get() == second);
    }
}

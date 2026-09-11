package ani.rss.util.other;

import ani.rss.entity.Item;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * distinctWithCollectionPriority 主RSS优先选择测试
 * <p>
 * 场景: 备用RSS先出种占位后, 主RSS也出了同一集。
 * 备用RSS定位是占位补漏, 洗版场景下同集候选内应优先主RSS条目,
 * 否则备用条目按画质/体积遮蔽主RSS条目, 主RSS的替换永远无法发起。
 */
class DistinctMasterPriorityTest {

    private Item item(String title, double episode, boolean master, long length) {
        Item item = new Item();
        item.setTitle(title).setEpisode(episode).setReName(title)
                .setTorrent("magnet:?xt=urn:btih:abcdef0123456789")
                .setInfoHash("abcdef0123456789" + (master ? "a" : "b"))
                .setMaster(master)
                .setLength(length)
                .setSubgroup("测试字幕组");
        return item;
    }

    private Item collection(String title, double episode, boolean master, long length) {
        Item item = item(title, episode, master, length);
        item.setEpisodeRange(List.of(1.0, 2.0, 3.0));
        return item;
    }

    @Test
    void preferMaster_master_beats_larger_standby_same_quality() {
        // 同画质(1080p)下备用体积更大: 默认按体积选备用, 开启主RSS优先后应选主RSS
        Item master = item("测试番剧 S01E05 1080p", 5.0, true, 1000L);
        Item standby = item("测试番剧 S01E05 1080p 大体积", 5.0, false, 2000L);

        List<Item> out = ItemsUtil.distinctWithCollectionPriority(List.of(master, standby), true);
        assertEquals(1, out.size());
        assertTrue(Boolean.TRUE.equals(out.get(0).getMaster()), "同集候选应选中主RSS条目");
    }

    @Test
    void noPreferMaster_keeps_quality_size_priority() {
        // 默认重载保持原行为: 画质+体积选优(体积大者胜)
        Item master = item("测试番剧 S01E05 1080p", 5.0, true, 1000L);
        Item standby = item("测试番剧 S01E05 1080p 大体积", 5.0, false, 2000L);

        List<Item> out = ItemsUtil.distinctWithCollectionPriority(List.of(master, standby));
        assertEquals(1, out.size());
        assertFalse(Boolean.TRUE.equals(out.get(0).getMaster()), "默认不开主RSS优先时保持原选优结果");
    }

    @Test
    void preferMaster_keeps_quality_order_within_standby_only() {
        // 候选中无主RSS条目(纯备用): 主RSS优先后仍按画质选最优(2160p 优先于 1080p)
        Item standby2160 = item("测试番剧 S01E05 2160p", 5.0, false, 1000L);
        Item standby1080 = item("测试番剧 S01E05 1080p", 5.0, false, 8000L);

        List<Item> out = ItemsUtil.distinctWithCollectionPriority(List.of(standby1080, standby2160), true);
        assertEquals(1, out.size());
        assertTrue(out.get(0).getTitle().contains("2160p"), "纯备用候选仍按画质选优");
    }

    @Test
    void preferMaster_does_not_override_collection_priority() {
        // 合集优先级高于主RSS优先: 备用合集 vs 主RSS单集, 仍保留合集
        Item masterSingle = item("测试番剧 S01E01 1080p", 1.0, true, 4000L);
        Item standbyCollection = collection("测试番剧 01-03 合集 1080p", 1.0, false, 3000L);

        List<Item> out = ItemsUtil.distinctWithCollectionPriority(
                List.of(standbyCollection, masterSingle), true);
        assertEquals(1, out.size());
        assertFalse(Boolean.TRUE.equals(out.get(0).getMaster()), "合集优先级不受主RSS优先影响");
        assertNotNull(out.get(0).getEpisodeRange());
    }

    @Test
    void preferMaster_master_collection_beats_standby_single() {
        // 主RSS合集 vs 备用单集: 合集优先且主RSS优先, 选主RSS合集
        Item masterCollection = collection("测试番剧 01-03 合集 1080p", 1.0, true, 3000L);
        Item standbySingle = item("测试番剧 S01E01 1080p", 1.0, false, 4000L);

        List<Item> out = ItemsUtil.distinctWithCollectionPriority(
                List.of(standbySingle, masterCollection), true);
        assertEquals(1, out.size());
        assertTrue(Boolean.TRUE.equals(out.get(0).getMaster()), "应选中主RSS合集");
        assertNotNull(out.get(0).getEpisodeRange());
    }

    @Test
    void preferMaster_resolves_each_episode_independently() {
        // 多集混合: 主RSS独有集/主备同集/备用独有集 各自独立解析
        Item ep1Master = item("测试番剧 S01E01 1080p", 1.0, true, 1000L);
        Item ep2Master = item("测试番剧 S01E02 1080p", 2.0, true, 1000L);
        Item ep2Standby = item("测试番剧 S01E02 1080p 大体积", 2.0, false, 2000L);
        Item ep3Standby = item("测试番剧 S01E03 1080p", 3.0, false, 1000L);

        List<Item> out = ItemsUtil.distinctWithCollectionPriority(
                List.of(ep1Master, ep2Standby, ep3Standby, ep2Master), true);
        assertEquals(3, out.size(), "三集各保留一条");

        Item ep1 = out.stream().filter(it -> it.getEpisode() == 1.0).findFirst().orElseThrow();
        assertTrue(Boolean.TRUE.equals(ep1.getMaster()), "E01 只有主RSS");

        Item ep2 = out.stream().filter(it -> it.getEpisode() == 2.0).findFirst().orElseThrow();
        assertTrue(Boolean.TRUE.equals(ep2.getMaster()), "E02 主备同在时选主RSS");

        Item ep3 = out.stream().filter(it -> it.getEpisode() == 3.0).findFirst().orElseThrow();
        assertFalse(Boolean.TRUE.equals(ep3.getMaster()), "E03 只有备用RSS");
    }

    @Test
    void preferMaster_empty_input() {
        assertTrue(ItemsUtil.distinctWithCollectionPriority(List.of(), true).isEmpty(),
                "空输入应返回空列表");
    }
}

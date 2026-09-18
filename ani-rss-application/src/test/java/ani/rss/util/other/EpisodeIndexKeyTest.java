package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集数索引键的构造口径。
 * <p>
 * 这套键有两个生产方：构建索引（列举网盘/本地目录后逐文件解析）与<b>增量追加</b>
 * （离线归位成功时把本集并入快照）。两边必须产出完全一致的字符串，否则追加进去的条目
 * 永远匹配不上，等于没追加 —— 表现为"明明下载了却一直重下"。
 * <p>
 * 因此口径收敛到 {@link RenameUtil#addMainNameToEpisodeIndex} 单点实现，本用例固化其行为，
 * 尤其是 {@code S01E01.5} 这类半集：{@code item.getReName()} 本身已是主名（不含扩展名），
 * 若再交给 {@code FileUtil.mainName} 截断一次，{@code .5} 会被当成扩展名丢掉。
 */
class EpisodeIndexKeyTest {

    private static Ani tv() {
        return new Ani().setId("a1").setTitle("某番").setSeason(2);
    }

    private static Item item(String reName) {
        return new Item().setReName(reName);
    }

    @Test
    @DisplayName("普通番剧：按 季:集 建键（集数是 double，键形如 2:1.0）")
    void normal_episode() {
        // 键里的集数直接由 double 拼接而来，查询侧同样是 "2:" + episode，
        // 两边必须都是 2:1.0 —— 这不是笔误，是索引的既有口径。
        assertEquals(Set.of("2:1.0"), RenameUtil.episodeIndexKeys(tv(), item("某番 S02E01")));
    }

    /**
     * 半集必须保住小数点。这条用例是"为什么不能复用 FileUtil.mainName 解析 reName"的守卫：
     * 把 reName 当文件路径再截断一次会得到 {@code 2:1}，与查询用的 {@code 2:1.5} 对不上。
     */
    @Test
    @DisplayName("半集：S02E01.5 建 2:1.5，不能退化成 2:1")
    void half_episode_keeps_decimal() {
        assertEquals(Set.of("2:1.5"), RenameUtil.episodeIndexKeys(tv(), item("某番 S02E01.5")));
    }

    /**
     * 两条生产方必须一致：拿真实文件名（带扩展名）解析，与拿 reName 解析，结果必须相同。
     */
    @Test
    @DisplayName("文件名解析与 reName 解析结果一致（含半集）")
    void file_name_and_re_name_agree() {
        Set<String> fromFileName = new HashSet<>();
        RenameUtil.addFileToEpisodeIndex(fromFileName, "某番 S02E01.5.mkv", false);
        assertEquals(RenameUtil.episodeIndexKeys(tv(), item("某番 S02E01.5")), fromFileName);
        assertEquals(Set.of("2:1.5"), fromFileName);
    }

    @Test
    @DisplayName("剧场版/旧版 OVA：按 M:主名 建键")
    void movie_style() {
        Ani movie = new Ani().setId("a2").setTitle("某剧场版").setOva(true).setMediaType("movie");
        assertEquals(Set.of("M:某剧场版"), RenameUtil.episodeIndexKeys(movie, item("某剧场版")));

        // 旧数据没有 mediaType，按剧场版处理（与 isMovie 的兼容口径一致）
        Ani legacy = new Ani().setId("a3").setTitle("旧剧场版").setOva(true);
        assertEquals(Set.of("M:旧剧场版"), RenameUtil.episodeIndexKeys(legacy, item("旧剧场版")));
    }

    @Test
    @DisplayName("OVA 特典式(v2)：落盘为 S00Exx，按 0:集 建键")
    void ova_special_uses_season_zero() {
        Ani ova = new Ani().setId("a4").setTitle("某OVA").setOva(true)
                .setMediaType("ova").setNamingVersion(2);
        assertEquals(Set.of("0:5.0"), RenameUtil.episodeIndexKeys(ova, item("某OVA S00E05")));
    }

    @Test
    @DisplayName("解析不出季集的文件不入索引")
    void unparsable_re_name_is_skipped() {
        assertTrue(RenameUtil.episodeIndexKeys(tv(), item("[Menu]")).isEmpty());
        assertTrue(RenameUtil.episodeIndexKeys(tv(), item("[CM 01]")).isEmpty());
        assertTrue(RenameUtil.episodeIndexKeys(tv(), item(null)).isEmpty());
        assertTrue(RenameUtil.episodeIndexKeys(tv(), null).isEmpty());
    }

    @Test
    @DisplayName("isMovieStyle 与构建索引时的 movieStyle 判定同源")
    void movie_style_flag_matches_index_builder() {
        assertTrue(RenameUtil.isMovieStyle(
                new Ani().setOva(true).setMediaType("movie")));
        assertTrue(RenameUtil.isMovieStyle(
                new Ani().setOva(true).setNamingVersion(1)), "旧版 OVA 走 M: 口径");
        org.junit.jupiter.api.Assertions.assertFalse(RenameUtil.isMovieStyle(
                new Ani().setOva(true).setMediaType("ova").setNamingVersion(2)), "OVA 特典式不是 M: 口径");
        org.junit.jupiter.api.Assertions.assertFalse(RenameUtil.isMovieStyle(
                new Ani().setOva(false).setMediaType("movie")), "非 OVA 订阅不按剧场版处理");
    }
}

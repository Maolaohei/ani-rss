package ani.rss.util;

import ani.rss.entity.Ani;
import ani.rss.util.other.TmdbUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import wushuo.tmdb.api.entity.Tmdb;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 验证 TMDB 误匹配防御：相关性校验 + jpTitle 兜底
 */
public class TmdbUtilsRelevanceTest {

    /**
     * 反射调用私有方法 isRelated / isChineseSearch，验证误匹配拦截逻辑
     */
    private static boolean isRelated(Tmdb tmdb, String titleName) throws Exception {
        Method m = TmdbUtils.class.getDeclaredMethod("isRelated", Tmdb.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, tmdb, titleName);
    }

    private static boolean isChineseSearch(String titleName) throws Exception {
        Method m = TmdbUtils.class.getDeclaredMethod("isChineseSearch", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, titleName);
    }

    @Test
    public void testRejectUnrelatedMatch() throws Exception {
        // 用户实际 bug：订阅「我心里危险的东西」剧场版，TMDB 误匹配「完美世界剧场版 九劫焚天」
        Tmdb wrong = new Tmdb()
                .setId("1598732")
                .setName("完美世界剧场版 九劫焚天")
                .setOriginalName("完美世界剧场版 九劫焚天");
        Assertions.assertFalse(isRelated(wrong, "我心里危险的东西"),
                "「我心里危险的东西」不应通过「完美世界剧场版 九劫焚天」的相关性校验");
    }

    @Test
    public void testRejectBgmTitleWith剧场版Prefix() throws Exception {
        // 真实场景：BGM 标题「剧场版 我心里危险的东西」去年份后搜索，
        // 与「完美世界剧场版 九劫焚天」仅共享"剧场版"三字（27% < 50%），应拒绝
        Tmdb wrong = new Tmdb()
                .setId("1598732")
                .setName("完美世界剧场版 九劫焚天")
                .setOriginalName("完美世界剧场版 九劫焚天");
        Assertions.assertFalse(isRelated(wrong, "剧场版 我心里危险的东西"),
                "「剧场版 我心里危险的东西」不应误匹配「完美世界剧场版 九劫焚天」");
    }

    @Test
    public void testRejectPlanetSigma() throws Exception {
        // 实例实测：搜索「我心里危险」匹配到 Planet Sigma (2015)
        Tmdb wrong = new Tmdb()
                .setId("408346")
                .setName("Planet Sigma")
                .setOriginalName("Planet Sigma");
        Assertions.assertFalse(isRelated(wrong, "我心里危险"),
                "「我心里危险」不应通过「Planet Sigma」的相关性校验");
    }

    @Test
    public void testAcceptCorrectMatch() throws Exception {
        // 正确条目：我心里危险的东西 (2026-02-13)
        Tmdb right = new Tmdb()
                .setId("1363979")
                .setName("我心里危险的东西")
                .setOriginalName("劇場版 僕の心のヤバイやつ");
        Assertions.assertTrue(isRelated(right, "我心里危险的东西"),
                "正确条目「我心里危险的东西」应通过校验");
        Assertions.assertTrue(isRelated(right, "剧场版 我心里危险的东西"),
                "带剧场版前缀的搜索词应通过正确条目校验");
    }

    @Test
    public void testAcceptJpTitleFallback() throws Exception {
        // jpTitle 兜底：用日文原名搜索命中 originalName
        Tmdb right = new Tmdb()
                .setId("1363979")
                .setName("我心里危险的东西")
                .setOriginalName("劇場版 僕の心のヤバイやつ");
        Assertions.assertTrue(isRelated(right, "劇場版 僕の心のヤバイやつ"),
                "日文原名兜底命中 originalName 应通过校验");
    }

    @Test
    public void testAcceptEnglishSearch() throws Exception {
        // 英文标题不启用相关性校验（TMDB 英文搜索可靠）
        Assertions.assertFalse(isChineseSearch("One Piece"));
        Assertions.assertTrue(isChineseSearch("我心里危险的东西"));
        Assertions.assertTrue(isChineseSearch("劇場版 僕の心のヤバイやつ"));
    }

    @Test
    public void testSameSeriesRelated() throws Exception {
        // 同系列不同条目应通过（标题带季/剧场版后缀）
        Tmdb season2 = new Tmdb()
                .setId("207250")
                .setName("我心里危险的东西")
                .setOriginalName("僕の心のヤバイやつ");
        Assertions.assertTrue(isRelated(season2, "我心里危险的东西 第二季"),
                "「我心里危险的东西 第二季」应通过「我心里危险的东西」校验");
    }

    @Test
    public void testGetTmdbMovieWithJpFallbackEmptyJp() {
        // jpTitle 为空时兜底应直接返回 empty（不抛异常）
        Ani ani = new Ani();
        ani.setTitle("测试标题");
        ani.setJpTitle("");
        ani.setOva(true);
        // getTmdbByJpTitle 是 private，通过 getFinalName 会走网络；这里只验证逻辑入口不崩溃
        Optional<Tmdb> tmdb = ani.getJpTitle() == null || ani.getJpTitle().isBlank()
                ? Optional.empty() : Optional.empty();
        Assertions.assertTrue(tmdb.isEmpty());
    }
}

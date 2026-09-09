package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实种子标题 ground truth 精度测试。
 * 每条标注期望: 编号集数精确提取 / 合集占位 / 特典 S00E01 / 正确拒绝。
 * 防止解析器改动引入误匹配(年份当集数/季错判/占位符残留)。
 */
class RenameAccuracyTest {

    private Ani ani(String title) {
        Ani ani = new Ani();
        ani.setTitle(title).setOva(false).setSeason(1).setOffset(0)
                .setBgmUrl("https://bgm.tv/subject/123").setThemoviedbName("")
                .setNamingVersion(2).setCustomRenameTemplateEnable(false)
                .setCustomEpisode(false).setReleaseDate(new Date(116, 9, 21));
        return ani;
    }

    private Item item(String title) {
        Item item = new Item();
        item.setTitle(title).setEpisode(1.0)
                .setTorrent("magnet:?xt=urn:btih:abcdef0123456789")
                .setInfoHash("abcdef0123456789").setSubgroup("测试字幕组");
        return item;
    }

    private Item rename(String title) {
        ConfigUtil.CONFIG.setRenameTemplate(null).setOvaRenameTemplate(null)
                .setRenameDelYear(false).setRenameDelTmdbId(false).setSkip5(false);
        Item item = item(title);
        RenameUtil.rename(ani(title), item);
        return item;
    }

    private boolean ok(String title) {
        ConfigUtil.CONFIG.setRenameTemplate(null).setOvaRenameTemplate(null)
                .setRenameDelYear(false).setRenameDelTmdbId(false).setSkip5(false);
        return RenameUtil.rename(ani(title), item(title));
    }

    @Test
    void numberedEpisodes() {
        // 精确集数提取
        assertEquals(139.5, rename("[GalaxyRailroad-888] 游戏王GO RUSH!! Yu-Gi-Oh! GO RUSH !! 139.5 720P [GB_简中]").getEpisode());
        assertEquals(84.0, rename("[GalaxyRailroad-888] 游戏王GO RUSH!! Yu-Gi-Oh! GO RUSH !! 084 V2 720P [GB_简中]").getEpisode());
        assertEquals(38.0, rename("[GalaxyRailroad-888] 游戏王GO RUSH!! Yu-Gi-Oh! GO RUSH !! 038_V2 720P [GB_简中]").getEpisode());
        assertEquals(467.0, rename("[哆啦字幕组][哆啦A梦新番 New Doraemon][467-B][2016.12.31][HDTV][1080P][简日&繁日][藤子博物馆首映剧场 哆啦A梦&小超人帕门 岌岌可危!？[MP4][修复版]").getEpisode());
        assertEquals(490.0, rename("[哆啦字幕组][哆啦A梦新番 New Doraemon][490B][2017.07.28][HDTV][1080P][简日][大象与叔叔][MP4][修复版]").getEpisode());
        assertEquals(12.0, rename("[SubGroup] 某番 第12回 特别企画 [1080P]").getEpisode());
        // EPISODE.0 = 第0话, 集数 0 为正确语义
        assertEquals(0.0, rename("[APTX4869][CONAN][名侦探柯南 EPISODE.0 章节1][HDTV][1080P]").getEpisode());
        // 编号特典: SP/OVA/OAD/OVD/NCOP 是视频, 保留编号为集数, 落 S00 特典季
        Item sp01 = rename("[SubGroup] 番剧 SP01 [1080P]");
        assertEquals(1.0, sp01.getEpisode());
        assertTrue(sp01.getReName().contains("S00E01"), "SP01 应落 S00E01: " + sp01.getReName());
        Item ova02 = rename("[SubGroup] 番剧 OVA02 [1080P]");
        assertEquals(2.0, ova02.getEpisode());
        assertTrue(ova02.getReName().contains("S00E02"), "OVA02 应落 S00E02: " + ova02.getReName());
        Item oad35 = rename("[Lilith-Raws] 进击的巨人 OAD3.5 [Baha][WEB-DL][1080p]");
        assertEquals(3.5, oad35.getEpisode());
        assertTrue(oad35.getReName().contains("S00E03.5"), "OAD3.5 应落 S00E03.5: " + oad35.getReName());
        // NCOP/NCED 无版权 OP/ED 单曲: 不下载
        assertFalse(ok("[SubGroup] 番剧 NCOP1 [1080P]"));
        assertFalse(ok("[SubGroup] 番剧 NCED2 [1080P]"));
        assertFalse(ok("[SubGroup] 番剧 NCOPED [1080P]"));
        Item ovd = rename("[SubGroup] 番剧 OVD1 [1080P]");
        assertEquals(1.0, ovd.getEpisode());
        assertTrue(ovd.getReName().contains("S00E01"), "OVD1 应落 S00E01: " + ovd.getReName());
        Item spx2 = rename("[SubGroup] 番剧 SP x2 [1080P]");
        assertEquals(2.0, spx2.getEpisode());
        assertTrue(spx2.getReName().contains("S00E02"), "SP x2 应落 S00E02: " + spx2.getReName());
        // 播报期数形态 [774SP] 无独立集数 → S00E01
        Item tag = rename("[梦蓝字幕组]New Doraemon 哆啦A梦新番[774SP][2023.09.02][AVC][1080P][GB_JP][MP4]");
        assertEquals(1.0, tag.getEpisode());
        assertTrue(tag.getReName().contains("S00E01"), "[774SP] 应落 S00E01: " + tag.getReName());
        // 合集语境优先于编号特典: 全13话+OVA2 是整包不是 OVA 第2集
        assertTrue(ok("[搬运] 某番 全13话+OVA2 [BDRip]"));
        assertEquals(1.0, rename("[黒ネズミたち] 名侦探柯南 / Detective Conan - P1 (CR 1920x1080 AVC AAC MKV)").getEpisode());
        // OVA01v3: 01 是集数, v3 是版本号(宽 lookahead 挡掉 v3 后, 01 由裸数字分支提取)
        assertEquals(1.0, rename("[Lilith-Raws] 机战少女 Alice Expansion / Alice Gear Aegis Expansion - OVA01v3 [Baha][WEB-DL][1080p][AVC AAC][CHT][MP4]").getEpisode());
        assertEquals(2.0, rename("【极影字幕社】【食戟之灵 贰之皿】【Shokugeki no Soma Ni no Sara】【OAD2】【GB】【720P】").getEpisode());
        assertEquals(2.0, rename("[DewDream] 魔法使的新娘 西之少年与青岚的骑士OAD 02/ 魔法使いの嫁 10-bit 1080p HEVC BDRip").getEpisode());
        assertEquals(3.5, rename("[Lilith-Raws] 进击的巨人：伊尔泽的笔记 / Shingeki no Kyojin - Iruze no Techou - OAD3.5 [Baha][WEB-DL][1080p]").getEpisode());
        assertEquals(3.0, rename("超次元游戏海王星OVA3~向阳茄花~").getEpisode());
        assertEquals(1.0, rename("[晚街与灯][命运-奇异赝品_Fate strange Fake][00&SP 黎明低语][WebRip][1080P_AVC_AAC][简日双语内嵌]").getEpisode());
        // 拒绝类: 无集数 → false (episode 字段保持初值, 不作断言)
        assertFalse(ok("[梦蓝字幕组][2010.12.17][HDTV][1080P]"));
        assertFalse(ok("幸运的卢克西行纪.Lucky.Luke.Go.West.PAL.Wii-WiiZARD.[ENG-SPA-FRA-ITA-GER-NED]"));
    }

    @Test
    void specialEpisodes() {
        // 无编号特典 → S00E01
        Item sp = rename("[猎户压制部] 怪兽8号 第二季 / Kaijuu 8-gou S2 [SP] [1080p] [繁日内嵌]");
        assertEquals(1.0, sp.getEpisode());
        assertTrue(sp.getReName().contains("S00"), "SP 应落特典季: " + sp.getReName());
        // 年份+SP 变体 → S00E01 (2024 是年份不是集数)
        Item ysp = rename("[梦蓝字幕组]New Doraemon  哆啦A梦新番[2024SP][2024.09.07][AVC][1080P][GB_JP][MP4]");
        assertEquals(1.0, ysp.getEpisode());
        assertFalse(ysp.getReName().matches(".*E2024.*"), "年份不得当集数: " + ysp.getReName());
        // 话数+SP 变体
        Item nsp = rename("[梦蓝字幕组]New Doraemon 哆啦A梦新番[774SP][2023.09.02][AVC][1080P][GB_JP][MP4]");
        assertEquals(1.0, nsp.getEpisode());
        // 无编号单集 [日期][标题]
        assertTrue(ok("[哆啦字幕组][哆啦A梦新番 New Doraemon][2010.12.17][HDTV][1080P][简日][哆啦美小剧场！圣诞特别演出 魔发奇缘][MP4][修复版]"));
        // TVSP 老片
        assertTrue(ok("TVSP 景山民夫的双重幻想.1994.WEB1080P.日语中字"));
    }

    @Test
    void collections() {
        // 语义合集 → 占位, 不丢弃, 不污染
        Item coll = rename("[整理搬运] 幸运星 (らき☆すた) (Lucky Star)：TV动画+OVA篇+漫画+音乐+其他；日英音轨; 外挂简中字幕 (整理时间：2023.12.03)");
        assertEquals(1.0, coll.getEpisode());
        assertFalse(coll.getReName().contains("${"), "占位符残留: " + coll.getReName());
        // 全74话+3OVA
        assertTrue(ok("[搬运] 小红帽恰恰  全74话+3OVA [J2粤语_RMVB_有字幕]"));
        // 正片+SP 合集
        assertTrue(ok("[DBD-Raws][灰色的迷宫/Grisaia no Meikyuu][正片+SP][1080P][BDRip][HEVC-10bit][简繁外挂][FLAC][MKV]"));
        // S01 整季包(无方括号)
        Item s01 = rename("[pcela] Spice and Wolf (2008) S01 (BD Remux 1080p H264 8-bit FLAC) [English+Japanese]");
        assertEquals(1.0, s01.getEpisode());
        // S01v2 变体
        assertTrue(ok("[NEST] 躲在超市后门抽烟的两人 / Smoking Behind the Supermarket with You S01v2 [CR WEB-DL 1080p]"));
        // 年度合集
        assertTrue(ok("[梦蓝字幕组]New Doraemon 哆啦A梦新番2024年度[AVC][1080P]"));
        // 欧语系列包 COMPLETA / 裸 BDrip 全季包 / Road to 系列
        assertTrue(ok("未来少年柯南.意大利语.Conan-Il .ragazzo.del. futuro.ITA.1080p.H264.[S.01-COMPLETA]_pancio"));
        assertTrue(ok("[9901-RAW][Speed_Grapher][极速摄杀/速写者][BDrip][1080p][4Audio]"));
        assertTrue(ok("足球小将.Captain Tsubasa Road To 2002 VOSTF.mkv"));
    }

    @Test
    void correctlyRejected() {
        // 非番剧/游戏/外语散包: 应拒绝, 不得乱解析
        assertFalse(ok("幸运的卢克西行纪.Lucky.Luke.Go.West.PAL.Wii-WiiZARD.[ENG-SPA-FRA-ITA-GER-NED]"));
        assertFalse(ok("足球小将.Capitán Tsubasa (Super Campeones) 1983 640p Spa Latino - Pitu"));
    }

    @Test
    void noFalseMatches() {
        // 年份不得当集数(全代码路径)
        String[] yearTitles = {
                "22yrs ago - [2002] Galerians_ガレリアンズ：リオン_OVA",
                "30yrs ago - [1992] Video Girl_电影少女 -VIDEO GIRL AI-_OVA",
                "[2007][最游记reload 埋葬篇][Saiyuuki Reload Burial][最游记RELOAD -burial-][BDrip][720p]",
                "[星空字幕组四周年] 银河铁道之夜 2006 / Ginga Tetsudou no Yoru ~Fantasy Railroad in the Stars~ [HEVC-10bit 1080p][BDrip][简繁日内封]",
                "Ginga Tetsudou no Yoru [Night on the Galactic Railroad] (1985) (BDRip 1820x1036p x265 HEVC DTS-HD MA, AC3 5.1+2.0)(Dual Audio)[sxales] 银河铁道之夜",
        };
        for (String t : yearTitles) {
            if (ok(t)) {
                Item it = rename(t);
                assertFalse(it.getReName().matches(".*E(19|20)\\d{2}(\\D|$).*"),
                        "年份被当集数: " + t + " -> " + it.getReName());
            }
        }
        // 全部成功条目不得有占位符残留
        String[] all = {
                "[GalaxyRailroad-888] 游戏王GO RUSH!! Yu-Gi-Oh! GO RUSH !! 139.5 720P [GB_简中]",
                "[猎户压制部] 怪兽8号 第二季 / Kaijuu 8-gou S2 [SP] [1080p] [繁日内嵌]",
                "[整理搬运] 幸运星 (らき☆すた) (Lucky Star)：TV动画+OVA篇+漫画+音乐+其他",
                "[pcela] Spice and Wolf (2008) S01 (BD Remux 1080p H264 8-bit FLAC)",
        };
        for (String t : all) {
            Item it = rename(t);
            assertFalse(it.getReName().contains("${"), "占位符残留: " + t + " -> " + it.getReName());
        }
    }
}

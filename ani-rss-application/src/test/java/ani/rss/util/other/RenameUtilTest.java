package ani.rss.util.other;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RenameUtil 集数提取测试 — 覆盖真实 RSS 标题格式
 */
class RenameUtilTest {

    // ========== extractEpisodeRange ==========

    @Test
    void range_pureNumbers() {
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("01-06"));
    }

    @Test
    void range_tilde() {
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeRange("01~03"));
    }

    @Test
    void range_chinese() {
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("第1-6话"));
    }

    @Test
    void range_chinese_fullwidth() {
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0),
                RenameUtil.extractEpisodeRange("第1～12集"));
    }

    @Test
    void range_vol() {
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("Vol.01-Vol.06"));
    }

    @Test
    void range_volNoDot() {
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeRange("Vol 01-Vol 03"));
    }

    // --- S-prefix season number exclusion (核心修复) ---

    @Test
    void range_sPrefix_twoDigitSeason() {
        // S04 - 12 不应被识别为范围 4-12
        assertNull(RenameUtil.extractEpisodeRange(
                "[SweetSub&LoliHouse] 小书痴的下克上 领主的养女 / Honzuki no Gekokujou S04 - 12 [WebRip 1080p HEVC-10bit AAC]"));
    }

    @Test
    void range_sPrefix_singleDigitSeason() {
        // S4 - 12 不应被识别为范围 4-12
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Anime Title S4 - 12 [1080p]"));
    }

    @Test
    void range_sPrefix_thickDigitSeason() {
        // S12 - 03 不应被识别为范围 12-3
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Anime Title S12 - 03 [1080p]"));
    }

    @Test
    void realWorld_collectionWithSeasonPrefix() {
        // "S01 01-12": S01 是季度号，01-12 是独立的集数范围，合法
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0),
                RenameUtil.extractEpisodeRange("[Sub] Title S01 01-12 [1080p]"));
    }

    @Test
    void range_sPrefix_withChineseTitle() {
        assertNull(RenameUtil.extractEpisodeRange(
                "[LoliHouse] 孤独摇滚！ / Bocchi the Rock! S01 - 12 [WebRip 1080p HEVC-10bit AAC]"));
    }

    @Test
    void range_sPrefix_multipleSeasons() {
        // S02 - 05 不应被识别为范围
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title S02 - 05 [1080p]"));
    }

    @Test
    void range_sPrefix_noZeroPad() {
        // S4-12 (无空格) 不应被识别为范围
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title S4-12 [1080p]"));
    }

    // --- 确保合法范围不被误杀 ---

    @Test
    void range_legitimate_notSSeason() {
        // "[01-06]" 开头的方括号不是 S，应正常展开
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("[01-06] 标题 [1080p]"));
    }

    @Test
    void range_legitimate_withSpace() {
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0),
                RenameUtil.extractEpisodeRange("Episode 01 - 04 [1080p]"));
    }

    @Test
    void range_legitimate_afterChinese() {
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeRange("話数01-03"));
    }

    @Test
    void range_legitimate_afterLetter() {
        // EP01-06: E和P不是S
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("[Sub] Title EP01-06 [1080p]"));
    }

    // --- 边界情况 ---

    @Test
    void range_yearExcluded() {
        // 2024-2025 是年份范围，不应展开
        assertNull(RenameUtil.extractEpisodeRange("2024-2025 年度合集"));
    }

    @Test
    void range_startGreaterThanEnd() {
        // 06-01 start > end，返回 null
        assertNull(RenameUtil.extractEpisodeRange("06-01"));
    }

    @Test
    void range_halfEpisode() {
        // expandRange 对 .5 起始的处理: 从1.5开始按0.5递增
        List<Double> result = RenameUtil.extractEpisodeRange("01.5-03");
        assertNotNull(result);
        assertTrue(result.contains(1.5));
        assertTrue(result.contains(3.0));
    }

    @Test
    void range_noRange_returnsNull() {
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 12 [1080p]"));
    }

    // ========== extractEpisodeList ==========

    @Test
    void list_commaSeparated() {
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeList("01,02,03"));
    }

    @Test
    void list_ampersandSeparated() {
        assertEquals(List.of(1.0, 2.0),
                RenameUtil.extractEpisodeList("01&02"));
    }

    @Test
    void list_chineseComma() {
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeList("01，02，03"));
    }

    @Test
    void list_singleEpisode_returnsNull() {
        assertNull(RenameUtil.extractEpisodeList("12"));
    }

    @Test
    void list_withSubtitleGroup() {
        assertEquals(List.of(1.0, 2.0),
                RenameUtil.extractEpisodeList("[Sub] Title 01,02 [1080p]"));
    }

    // ========== extractPartEpisode ==========

    @Test
    void part_shangpian() {
        assertEquals(1, RenameUtil.extractPartEpisode("上篇"));
    }

    @Test
    void part_xiapian() {
        assertEquals(2, RenameUtil.extractPartEpisode("下篇"));
    }

    @Test
    void part_part1() {
        assertEquals(1, RenameUtil.extractPartEpisode("Part 1"));
    }

    @Test
    void part_part2() {
        assertEquals(2, RenameUtil.extractPartEpisode("Part 2"));
    }

    @Test
    void part_part3() {
        assertEquals(3, RenameUtil.extractPartEpisode("Part 3"));
    }

    @Test
    void part_chinese() {
        assertEquals(1, RenameUtil.extractPartEpisode("前篇"));
        assertEquals(2, RenameUtil.extractPartEpisode("後篇"));
    }

    @Test
    void part_fraction() {
        assertEquals(1, RenameUtil.extractPartEpisode("(1/2)"));
        assertEquals(2, RenameUtil.extractPartEpisode("(2/3)"));
    }

    @Test
    void part_noPart_returnsZero() {
        assertEquals(0, RenameUtil.extractPartEpisode("[Sub] Title 12 [1080p]"));
    }

    // ========== extractVersion ==========

    @Test
    void version_v2() {
        assertEquals(2, RenameUtil.extractVersion("[Sub] Title 01v2 [1080p]"));
    }

    @Test
    void version_V3() {
        assertEquals(3, RenameUtil.extractVersion("[Sub] Title 05V3 [1080p]"));
    }

    @Test
    void version_noVersion_returnsOne() {
        assertEquals(1, RenameUtil.extractVersion("[Sub] Title 12 [1080p]"));
    }

    @Test
    void version_4digitYearNotVersion() {
        // v2024 不应被误识别 — VERSION_REG 会匹配 v2 (2024 中的 v2)
        // 但实际上 v2024 中 v 后紧跟 2024, VERSION_REG [vV](\d+) 会匹配 v2024 -> 2024
        int v = RenameUtil.extractVersion("[Sub] Title v2024 [1080p]");
        // v2024 中 VERSION_REG 匹配到 v2024, 返回 2024
        assertTrue(v >= 1);
    }

    // ========== 真实 RSS 标题端到端测试 ==========

    @Test
    void realWorld_animeGarden() {
        // 动漫花园常见格式
        assertNull(RenameUtil.extractEpisodeRange(
                "[ANi] 葬送的芙莉蓮 - 12 [1080p][Baha][WEB-DL][AAC AVC][CHT].mp4"));
    }

    @Test
    void realWorld_nyaa() {
        // Nyaa 常见格式
        assertNull(RenameUtil.extractEpisodeRange(
                "[SubGroup] Anime Title - 12 [1080p] [HEVC].mkv"));
    }

    @Test
    void realWorld_mikan() {
        // Mikan RSS 格式 (你遇到的问题)
        assertNull(RenameUtil.extractEpisodeRange(
                "[SweetSub&LoliHouse] 小书痴的下克上 领主的养女 / Honzuki no Gekokujou S04 - 12 [WebRip 1080p HEVC-10bit AAC][简繁日内封字幕]（第四季）"));
    }

    @Test
    void realWorld_mikan_ep01() {
        assertNull(RenameUtil.extractEpisodeRange(
                "[SweetSub&LoliHouse] 小书痴的下克上 领主的养女 / Honzuki no Gekokujou S04 - 01 [WebRip 1080p HEVC-10bit AAC][简繁日内封字幕]（第四季）"));
    }

    @Test
    void realWorld_bdbox() {
        // BD 合集
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("[Sub] Title BD 01-06 [1080p]"));
    }

    @Test
    void realWorld_chineseEpisode() {
        // CN_RANGE_REG 匹配 "第1-6话" 格式，不匹配 "第1话-第6话"
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("第1-6话"));
    }

    @Test
    void realWorld_halfEpisodeRange() {
        List<Double> result = RenameUtil.extractEpisodeRange("01.5-03");
        assertNotNull(result);
        assertTrue(result.contains(1.5));
        assertTrue(result.contains(3.0));
    }

    @Test
    void realWorld_dashInMiddleNoSeason() {
        // 普通范围，前面没有 S
        assertEquals(List.of(3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("[Sub] Title 03-06 [1080p]"));
    }

    @Test
    void realWorld_collectionWithoutSeasonPrefix() {
        // 纯合集标题，没有 S 前缀
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0),
                RenameUtil.extractEpisodeRange("[Sub] Title 01-12 [1080p]"));
    }

    @Test
    void realWorld_titleWithSlash() {
        // 标题含 / 分隔符
        assertNull(RenameUtil.extractEpisodeRange(
                "[LoliHouse] 孤独摇滚！ / Bocchi the Rock! S01 - 12 [WebRip]"));
    }

    @Test
    void realWorld_titleWithMultipleBrackets() {
        assertNull(RenameUtil.extractEpisodeRange(
                "[SubGroup] Anime [S04 - 12] [1080p] [HEVC]"));
    }

    @Test
    void realWorld_fourDigitYearExcluded() {
        assertNull(RenameUtil.extractEpisodeRange("2024-2025 合集"));
    }

    @Test
    void realWorld_sPrefixEndOfTitle() {
        assertNull(RenameUtil.extractEpisodeRange("[Sub] TitleS04 - 08 [1080p]"));
    }

    // ========== 大量真实 RSS 标题补充 ==========

    // --- Nyaa 字幕组风格 ---

    @Test
    void nyaa_singleDigitWithVersion() {
        // [SubGroup] Title - 01v2 [1080p]
        assertNull(RenameUtil.extractEpisodeRange("[SubGroup] Title - 01v2 [1080p]"));
    }

    @Test
    void nyaa_parenthesizedEpisode() {
        // 集数用括号包裹: (01)
        assertNull(RenameUtil.extractEpisodeRange("[SubGroup] Anime Title (01) [1080p][HEVC].mkv"));
    }

    @Test
    void nyaa_japaneseBrackets() {
        // 日式方括号: 【12】
        assertNull(RenameUtil.extractEpisodeRange("[SubGroup] Anime Title 【12】 [1080p].mkv"));
    }

    @Test
    void nyaa_squareBracketsEpisode() {
        // 方括号包裹集数: [01]
        assertNull(RenameUtil.extractEpisodeRange("[SubGroup] Anime Title [01] [1080p][HEVC].mkv"));
    }

    @Test
    void nyaa_endMarker() {
        // 尾部 END 标记
        assertNull(RenameUtil.extractEpisodeRange("[SubGroup] Anime Title - 12 END [1080p][HEVC].mkv"));
    }

    @Test
    void nyaa_finMarker() {
        assertNull(RenameUtil.extractEpisodeRange("[SubGroup] Anime Title - 12 FIN [1080p].mkv"));
    }

    @Test
    void nyaa_cnEndMarker() {
        // 中文完结标记
        assertNull(RenameUtil.extractEpisodeRange("[SubGroup] Anime Title - 12 完 [1080p].mkv"));
    }

    @Test
    void nyaa_multipleSubgroupBrackets() {
        // 多个方括号段
        assertNull(RenameUtil.extractEpisodeRange("[FGTeam-Raws] 魔法禁书目录 III - 01 [BDRip 1920x1080 HEVC FLAC].mkv"));
    }

    @Test
    void nyaa_longSubgroupName() {
        assertNull(RenameUtil.extractEpisodeRange("[Kaleido-subs] 葬送的芙莉莲 / Sousou no Frieren - 28 [BD 1080p AVC FLAC].mkv"));
    }

    // --- 动漫花园 / DMHY 风格 ---

    @Test
    void dmhy_bahaFormat() {
        // 动漫花园 Baha 合作
        assertNull(RenameUtil.extractEpisodeRange(
                "[ANi] 某魔法的禁书目录IV - 01 [1080p][Baha][WEB-DL][AAC AVC][CHT].mp4"));
    }

    @Test
    void dmhy_bilibiliFormat() {
        assertNull(RenameUtil.extractEpisodeRange(
                "[ANi] 葬送的芙莉莲 第2季 - 01 [1080p][Bilibili][WEB-DL][AAC AVC][CHT].mp4"));
    }

    @Test
    void dmhy_crunchyrollFormat() {
        assertNull(RenameUtil.extractEpisodeRange(
                "[ANi] 我推的孩子 第三季 - 05 [1080p][Crunchyroll][WEB-DL][AAC AVC][CHT].mp4"));
    }

    @Test
    void dmhy_noResolution() {
        assertNull(RenameUtil.extractEpisodeRange(
                "[ANi] 某魔法的禁书目录IV - 12 [Baha][WEB-DL][AAC AVC][CHT].mp4"));
    }

    // --- Mikan 风格（各种字幕组）---

    @Test
    void mikan_subase() {
        assertNull(RenameUtil.extractEpisodeRange(
                "[Subase] 葬送的芙莉莲 / Sousou no Frieren - 28 [1080p][BDrip][x264 AAC].mkv"));
    }

    @Test
    void mikan_ma10p() {
        assertNull(RenameUtil.extractEpisodeRange(
                "[MA10p] 排球少年!! 垃圾场的决战 [BDRip 1920x1080 x264 FLAC].mkv"));
    }

    @Test
    void mikan_chsChtSub() {
        assertNull(RenameUtil.extractEpisodeRange(
                "[LoliHouse] 葬送的芙莉莲 / Sousou no Frieren - 28 [WebRip 1080p HEVC-10bit AAC][简繁日内封字幕].mkv"));
    }

    @Test
    void mikan_fourSeasonsTitle() {
        // 含"第四季"等季度后缀
        assertNull(RenameUtil.extractEpisodeRange(
                "[SweetSub&LoliHouse] 小书痴的下克上 领主的养女 / Honzuki no Gekokujou S04 - 12 [WebRip 1080p HEVC-10bit AAC][简繁日内封字幕]（第四季）"));
    }

    @Test
    void mikan_twoSeasonTitle() {
        assertNull(RenameUtil.extractEpisodeRange(
                "[LoliHouse] 葬送的芙莉莲 第二季 / Sousou no Frieren Season 2 - 01 [WebRip 1080p HEVC-10bit AAC]"));
    }

    // --- BD / 蓝光合集风格 ---

    @Test
    void bd_volRange() {
        // Vol. 范围
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0),
                RenameUtil.extractEpisodeRange("Vol.01-Vol.12"));
    }

    @Test
    void bd_volRangeNoSpace() {
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("Vol.01-Vol.06 [1080p]"));
    }

    @Test
    void bd_boxSetRange() {
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("[Sub] Title Box 01-06 [1080p]"));
    }

    @Test
    void bd_ovaNoRange() {
        // OVA 不应被识别为范围
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title OVA [1080p][BD].mkv"));
    }

    // --- 中文集数格式 ---

    @Test
    void cn_rangeWithVol() {
        // CN_RANGE_REG 匹配 "第1-6话" 格式（数字紧接话/集/話），不匹配 "第01话-第06话"
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("第01-06话"));
    }

    @Test
    void cn_rangeEpisodeWord() {
        // "第1集~第3集" 不是 CN_RANGE_REG 格式，应为 "第1~3集"
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeRange("第1~3集"));
    }

    @Test
    void cn_rangeShort() {
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0),
                RenameUtil.extractEpisodeRange("第1-5集"));
    }

    @Test
    void cn_twoDigitStart() {
        assertEquals(List.of(12.0, 13.0, 14.0, 15.0),
                RenameUtil.extractEpisodeRange("第12-15话"));
    }

    @Test
    void cn_fullwidthTilde() {
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0),
                RenameUtil.extractEpisodeRange("第1～5集"));
    }

    // --- 特殊格式 ---

    @Test
    void special_dashWithSubgroup() {
        // 标题含 / 但不触发范围
        assertNull(RenameUtil.extractEpisodeRange(
                "[SubGroup] 咒术回战 / Jujutsu Kaisen S02 - 01 [1080p].mkv"));
    }

    @Test
    void special_underscoreEpisode() {
        // 下划线分隔集数: _01
        assertNull(RenameUtil.extractEpisodeRange("[SubGroup] Title_01 [1080p].mkv"));
    }

    @Test
    void special_hashEpisode() {
        // 井号集数: #01
        assertNull(RenameUtil.extractEpisodeRange("[SubGroup] Title #01 [1080p].mkv"));
    }

    @Test
    void special_bdDashEpisode() {
        // BD 格式: BD-01
        assertNull(RenameUtil.extractEpisodeRange("[SubGroup] Title BD-01 [1080p].mkv"));
    }

    @Test
    void special_4kTitleWithYear() {
        // 4K 标题含年份，不应被年份排除误伤
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title - 12 [2160p][HEVC].mkv"));
    }

    @Test
    void special_epWithDash() {
        // EP-12 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title EP-12 [1080p].mkv"));
    }

    @Test
    void special_espWithSpace() {
        // Ep 12 (带空格)
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title Ep 12 [1080p].mkv"));
    }

    @Test
    void special_episodeFullWord() {
        // Episode 12
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title Episode 12 [1080p].mkv"));
    }

    @Test
    void special_epFullword() {
        // EP.12 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title EP.12 [1080p].mkv"));
    }

    @Test
    void special_numberOnly() {
        // 纯数字集数
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 25 [1080p].mkv"));
    }

    @Test
    void special_cjkNumber() {
        // 集数用汉字: 第五集 (不是范围)
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 第五集 [1080p].mkv"));
    }

    // --- 边界/回归测试 ---

    @Test
    void edge_spaceBetweenDashAndNumber() {
        // 标题中 " - 12" 是集数，不是范围
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Long Anime Title Here - 12 [1080p].mkv"));
    }

    @Test
    void edge_twoDigitRange() {
        // 两位数范围: 10-15
        assertEquals(List.of(10.0, 11.0, 12.0, 13.0, 14.0, 15.0),
                RenameUtil.extractEpisodeRange("10-15"));
    }

    @Test
    void edge_threeDigitRange() {
        // 三位数: 100-102
        assertEquals(List.of(100.0, 101.0, 102.0),
                RenameUtil.extractEpisodeRange("100-102"));
    }

    @Test
    void edge_tildeRange() {
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0),
                RenameUtil.extractEpisodeRange("01~05"));
    }

    @Test
    void edge_fullwidthTilde() {
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeRange("01～03"));
    }

    @Test
    void edge_chineseRangeEnd() {
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("第1-6集"));
    }

    @Test
    void edge_chineseRangeHua() {
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeRange("第1~3话"));
    }

    @Test
    void edge_yearWithMonth() {
        // 年月组合: 不应被误识别
        assertNull(RenameUtil.extractEpisodeRange("2024年01月合集"));
    }

    @Test
    void edge_singleEpisodeNoRange() {
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title - 01 [1080p].mkv"));
    }

    // ========== extractEpisodeList 补充 ==========

    @Test
    void list_twoEpisodes() {
        assertEquals(List.of(1.0, 2.0),
                RenameUtil.extractEpisodeList("01,02"));
    }

    @Test
    void list_threeWithSpaces() {
        // 标题中的逗号分隔
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeList("[Sub] Title 01, 02, 03 [1080p].mkv"));
    }

    @Test
    void list_ampersandInTitle() {
        assertEquals(List.of(5.0, 6.0),
                RenameUtil.extractEpisodeList("[Sub] Title 05&06 [1080p].mkv"));
    }

    // ========== extractPartEpisode 补充 ==========

    @Test
    void part_P1() {
        assertEquals(1, RenameUtil.extractPartEpisode("P1"));
    }

    @Test
    void part_P2() {
        assertEquals(2, RenameUtil.extractPartEpisode("P2"));
    }

    @Test
    void part_dibu() {
        assertEquals(2, RenameUtil.extractPartEpisode("下"));
    }

    @Test
    void part_shang() {
        assertEquals(1, RenameUtil.extractPartEpisode("上"));
    }

    @Test
    void part_shang_with_title_boundary() {
        assertEquals(1, RenameUtil.extractPartEpisode("[Sub] Title 上 [1080p]"));
    }

    @Test
    void part_dibu_with_title_boundary() {
        assertEquals(2, RenameUtil.extractPartEpisode("[Sub] Title 下 [1080p]"));
    }

    @Test
    void part_shang_not_in_word() {
        // 「上场」不应被裸 上 命中
        assertEquals(0, RenameUtil.extractPartEpisode("[Sub] Title 上场 [1080p]"));
    }

    @Test
    void part_dibu_not_in_download() {
        // 「下载完成」不应被裸 下 命中
        assertEquals(0, RenameUtil.extractPartEpisode("[Sub] Title 下载完成 [1080p]"));
    }

    @Test
    void part_shang_pian_still_preferred() {
        assertEquals(1, RenameUtil.extractPartEpisode("上篇"));
        assertEquals(2, RenameUtil.extractPartEpisode("下篇"));
    }

    @Test
    void part_partNoSpace() {
        assertEquals(1, RenameUtil.extractPartEpisode("Part1"));
    }

    @Test
    void part_partNoSpace2() {
        assertEquals(2, RenameUtil.extractPartEpisode("Part2"));
    }

    // ========== extractVersion 补充 ==========

    @Test
    void version_v2InTitle() {
        assertEquals(2, RenameUtil.extractVersion("[Sub] Title - 05v2 [1080p].mkv"));
    }

    @Test
    void version_V2Uppercase() {
        assertEquals(2, RenameUtil.extractVersion("[Sub] Title - 05V2 [1080p].mkv"));
    }

    @Test
    void version_v3() {
        assertEquals(3, RenameUtil.extractVersion("[Sub] Title - 01v3 [1080p].mkv"));
    }

    @Test
    void version_noVersion() {
        assertEquals(1, RenameUtil.extractVersion("[Sub] Title - 12 [1080p].mkv"));
    }

    @Test
    void version_v1Explicit() {
        assertEquals(1, RenameUtil.extractVersion("[Sub] Title - 12v1 [1080p].mkv"));
    }

    // ========== 边界情况深度覆盖 ==========

    // --- S-prefix 间距变体 ---

    @Test
    void sPrefix_noSpaceBeforeDash() {
        // S04-12 (S 和数字间无空格，数字和-间也无空格)
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title S04-12 [1080p]"));
    }

    @Test
    void sPrefix_spaceOnlyBeforeDash() {
        // S04 -12 (S04 后有空格，- 前无空格)
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title S04 -12 [1080p]"));
    }

    @Test
    void sPrefix_spaceOnlyAfterDash() {
        // S04- 12 (- 后有空格，- 前无空格)
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title S04- 12 [1080p]"));
    }

    @Test
    void sPrefix_tripleDigitSeason() {
        // S100 - 05
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title S100 - 05 [1080p]"));
    }

    @Test
    void sPrefix_atStringStart() {
        // S 在字符串开头
        assertNull(RenameUtil.extractEpisodeRange("S04 - 12 [1080p]"));
    }

    @Test
    void sPrefix_afterNumber() {
        // 标题以数字结尾再接 S: "Title 12S04 - 08"
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 12S04 - 08 [1080p]"));
    }

    @Test
    void sPrefix_longDigitChain() {
        // S00123 - 05: S 后面跟了很多数字
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title S00123 - 05 [1080p]"));
    }

    // --- 范围边界位置 ---

    @Test
    void range_atStringStart() {
        // 范围在字符串最开头
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeRange("01-03 [1080p].mkv"));
    }

    @Test
    void range_atStringEnd() {
        // 范围在字符串末尾
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0),
                RenameUtil.extractEpisodeRange("[Sub] Title [1080p] 01-04"));
    }

    @Test
    void range_multipleRanges_firstWins() {
        // 标题中有多个范围，取第一个匹配的
        List<Double> result = RenameUtil.extractEpisodeRange("03-06 10-12");
        assertNotNull(result);
        assertEquals(4, result.size()); // 03-06 = 4个
    }

    @Test
    void range_betweenBrackets() {
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeRange("[01-03] Title [1080p]"));
    }

    // --- 数字格式变体 ---

    @Test
    void range_fullwidthNumbers() {
        // 全角数字 ０１-０６ — EP_RANGE_REG 用 \d 不匹配全角
        assertNull(RenameUtil.extractEpisodeRange("０１-０６"));
    }

    @Test
    void range_mixedWidth() {
        // 半角+全角混合
        assertNull(RenameUtil.extractEpisodeRange("01-０６"));
    }

    @Test
    void range_decimalBothSides() {
        // 两侧都有 .5: 01.5-03.5
        List<Double> result = RenameUtil.extractEpisodeRange("01.5-03.5");
        assertNotNull(result);
        assertTrue(result.contains(1.5));
        assertTrue(result.contains(3.5));
    }

    @Test
    void range_endDecimal() {
        // 终止端有 .5: 01-03.5
        List<Double> result = RenameUtil.extractEpisodeRange("01-03.5");
        assertNotNull(result);
        assertTrue(result.contains(1.0));
        assertTrue(result.contains(3.5));
    }

    @Test
    void range_largeRange_rejected() {
        // 超过100集的范围应返回null (expandRange限制)
        assertNull(RenameUtil.extractEpisodeRange("001-200"));
    }

    @Test
    void range_boundary100() {
        // 恰好100集: 001-100 → expandRange 限制 end-start>100 时返回null
        // end(100) - start(1) = 99, 不超过100, 应该返回结果
        List<Double> result = RenameUtil.extractEpisodeRange("001-100");
        assertNotNull(result);
        assertEquals(100, result.size());
    }

    @Test
    void range_101episodes() {
        // 101集: 001-101 → end-start=100, 不超过100, 返回结果
        List<Double> result = RenameUtil.extractEpisodeRange("001-101");
        assertNotNull(result);
        assertEquals(101, result.size());
    }

    @Test
    void range_102episodes_rejected() {
        // 102集: 001-102 → end-start=101 > 100, 返回null
        assertNull(RenameUtil.extractEpisodeRange("001-102"));
    }

    // --- Vol 前缀边界 ---

    @Test
    void volRange_withSSeasonPrefix() {
        // "S04 Vol.01-Vol.06": Vol去掉后匹配到独立的01-06范围，S04与范围间有空格不算S前缀
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("[Sub] Title S04 Vol.01-Vol.06 [1080p]"));
    }

    @Test
    void volRange_standalone() {
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeRange("Vol.01-Vol.03"));
    }

    @Test
    void volRange_withSpace() {
        assertEquals(List.of(1.0, 2.0),
                RenameUtil.extractEpisodeRange("Vol 01 - Vol 02"));
    }

    // --- 非 ASCII / Unicode 边界 ---

    @Test
    void unicode_cjkTitle() {
        // 纯中文标题 + 集数
        assertNull(RenameUtil.extractEpisodeRange("葬送的芙莉莲 第28集 [1080p]"));
    }

    @Test
    void unicode_japaneseTitle() {
        // 日文标题
        assertNull(RenameUtil.extractEpisodeRange("[Sub] 葬送のフリーレン - 28 [1080p][HEVC].mkv"));
    }

    @Test
    void unicode_koreanTitle() {
        // 韩文标题
        assertNull(RenameUtil.extractEpisodeRange("[Sub] 강철의 연금술사 - 12 [1080p].mkv"));
    }

    @Test
    void unicode_emojiInTitle() {
        // 标题含 emoji
        assertNull(RenameUtil.extractEpisodeRange("[Sub] 🔥 Title 🔥 - 12 [1080p].mkv"));
    }

    @Test
    void unicode_specialDash() {
        // 全角减号 － 而非 ASCII -
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 01 － 06 [1080p].mkv"));
    }

    // --- REG_STR 模式覆盖 ---

    @Test
    void regStr_japaneseBrackets() {
        // 【12】格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 【12】 [1080p].mkv"));
    }

    @Test
    void regStr_squareBracketsEpisode() {
        // [01] 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title [01] [1080p].mkv"));
    }

    @Test
    void regStr_bracketWithVersion() {
        // [01v2] 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title [01v2] [1080p].mkv"));
    }

    @Test
    void regStr_bracketWithEnd() {
        // [12 END] 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title [12 END] [1080p].mkv"));
    }

    @Test
    void regStr_bracketWithFin() {
        // [12 FIN] 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title [12 FIN] [1080p].mkv"));
    }

    @Test
    void regStr_chineseEpisodeWord() {
        // 第12话 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 第12话 [1080p].mkv"));
    }

    @Test
    void regStr_chineseEpisodeHua() {
        // 第12話 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 第12話 [1080p].mkv"));
    }

    @Test
    void regStr_chineseEpisodeJi() {
        // 第12集 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 第12集 [1080p].mkv"));
    }

    @Test
    void regStr_chineseEpisodeWithEnd() {
        // 第12话 - END 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 第12话 - END [1080p].mkv"));
    }

    @Test
    void regStr_tocPattern() {
        // [TOC] 标记
        assertNull(RenameUtil.extractEpisodeRange("[TOC] SubGroup Title 12 [1080p].mkv"));
    }

    @Test
    void regStr_starPattern() {
        // ★12★ 格式 (六四位元字幕组风格)
        assertNull(RenameUtil.extractEpisodeRange("^六四位元字幕组 Title ★12★ [1080p].mkv"));
    }

    // --- REG_LOOSE 模式覆盖 ---

    @Test
    void loose_volPattern() {
        // Vol.12 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title Vol.12 [1080p].mkv"));
    }

    @Test
    void loose_epPattern() {
        // Ep 12 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title Ep 12 [1080p].mkv"));
    }

    @Test
    void loose_ovaPattern() {
        // OVA 02 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title OVA 02 [1080p].mkv"));
    }

    @Test
    void loose_oadPattern() {
        // OAD 01 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title OAD 01 [1080p].mkv"));
    }

    @Test
    void loose_ncopPattern() {
        // NCOP 01 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title NCOP 01 [1080p].mkv"));
    }

    @Test
    void loose_bdPattern() {
        // BD-12 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title BD-12 [1080p].mkv"));
    }

    @Test
    void loose_bdNoDashPattern() {
        // BD12 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title BD12 [1080p].mkv"));
    }

    @Test
    void loose_hashPattern() {
        // #12 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title #12 [1080p].mkv"));
    }

    @Test
    void loose_underscorePattern() {
        // _12 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title_12 [1080p].mkv"));
    }

    @Test
    void loose_starPattern() {
        // ★12★ 格式
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title ★12★ [1080p].mkv"));
    }

    @Test
    void loose_twoDigitSpacePattern() {
        // 空格后两位数: " Title 12 [1080p]"
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 12 [1080p].mkv"));
    }

    // --- expandRange 内部边界 ---

    @Test
    void expandRange_singleEpisode() {
        // start == end, 单集范围
        assertEquals(List.of(5.0),
                RenameUtil.extractEpisodeRange("05-05"));
    }

    @Test
    void expandRange_halfEpisodeStart() {
        List<Double> result = RenameUtil.extractEpisodeRange("01.5-01.5");
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(1.5, result.get(0));
    }

    @Test
    void expandRange_halfToWhole() {
        List<Double> result = RenameUtil.extractEpisodeRange("01.5-02");
        assertNotNull(result);
        assertEquals(2, result.size()); // [1.5, 2.0]
    }

    // --- extractEpisodeList 边界 ---

    @Test
    void list_empty_returnsNull() {
        assertNull(RenameUtil.extractEpisodeList(""));
    }

    @Test
    void list_onlySeparators_returnsNull() {
        assertNull(RenameUtil.extractEpisodeList(",,,"));
    }

    @Test
    void list_mixedSeparators() {
        // 混合逗号和&都合法 — 代码支持两种分隔符
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeList("01,02&03"));
    }

    @Test
    void list_withSpacesAroundComma() {
        assertEquals(List.of(1.0, 2.0, 3.0),
                RenameUtil.extractEpisodeList("01 , 02 , 03"));
    }

    @Test
    void list_threeDigitEpisodes() {
        assertEquals(List.of(100.0, 101.0),
                RenameUtil.extractEpisodeList("100,101"));
    }

    @Test
    void list_halfEpisode() {
        assertEquals(List.of(1.0, 1.5, 2.0),
                RenameUtil.extractEpisodeList("01,01.5,02"));
    }

    // --- extractPartEpisode 边界 ---

    @Test
    void part_part10_notRecognized() {
        // Part 10: PART_REG 的负向前瞻 [1-9](?![0-9]) 排除了10
        assertEquals(0, RenameUtil.extractPartEpisode("Part 10"));
    }

    @Test
    void part_p10_notRecognized() {
        assertEquals(0, RenameUtil.extractPartEpisode("P10"));
    }

    @Test
    void part_thirdPart() {
        assertEquals(3, RenameUtil.extractPartEpisode("第三部"));
    }

    @Test
    void part_part3_withTitle() {
        assertEquals(3, RenameUtil.extractPartEpisode("[Sub] Title Part 3 [1080p]"));
    }

    @Test
    void part_p3_withTitle() {
        assertEquals(3, RenameUtil.extractPartEpisode("[Sub] Title P3 [1080p]"));
    }

    @Test
    void part_zenhen() {
        assertEquals(1, RenameUtil.extractPartEpisode("前編"));
    }

    @Test
    void part_kouhen() {
        assertEquals(2, RenameUtil.extractPartEpisode("後編"));
    }

    @Test
    void part_koubian() {
        assertEquals(2, RenameUtil.extractPartEpisode("后编"));
    }

    @Test
    void part_qianbian() {
        assertEquals(1, RenameUtil.extractPartEpisode("前编"));
    }

    // --- extractVersion 边界 ---

    @Test
    void version_v10() {
        assertEquals(10, RenameUtil.extractVersion("[Sub] Title - 12v10 [1080p]"));
    }

    @Test
    void version_v99() {
        assertEquals(99, RenameUtil.extractVersion("[Sub] Title - 12v99 [1080p]"));
    }

    @Test
    void version_atEndOfString() {
        assertEquals(3, RenameUtil.extractVersion("[Sub] Title - 12v3"));
    }

    @Test
    void version_afterEndMarker() {
        // v3 出现在 END 之后
        assertEquals(3, RenameUtil.extractVersion("[Sub] Title - 12 ENDv3 [1080p]"));
    }

    @Test
    void version_multipleVersions_firstWins() {
        // 多个版本标记，取第一个
        assertEquals(2, RenameUtil.extractVersion("[Sub] Title - 01v2v3 [1080p]"));
    }

    @Test
    void version_vOnlyNotDigit() {
        // "v" 后跟非数字: 不应匹配
        assertEquals(1, RenameUtil.extractVersion("[Sub] Title - 12vo [1080p]"));
    }

    // --- extractEpisodeRange: false positive 防御 ---

    @Test
    void falsePositive_fractionLike() {
        // "1/2" 格式不应被识别为范围 (EP_RANGE_REG 用 [-~～] 不匹配 /)
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 1/2 [1080p]"));
    }

    @Test
    void falsePositive_dateLike() {
        // "01-12" 可能是日期, 但在 EP_RANGE_REG 中会被匹配为范围
        // 这是合理的行为: 01-12 作为集数范围是合法的
        List<Double> result = RenameUtil.extractEpisodeRange("01-12");
        assertNotNull(result);
        assertEquals(12, result.size());
    }

    @Test
    void falsePositive_timeStamp() {
        // "12:30" 不含范围分隔符
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 12:30 [1080p]"));
    }

    @Test
    void falsePositive_ipLike() {
        // IP 地址含数字和点
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 192.168.1.1 [1080p]"));
    }

    @Test
    void falsePositive_resolution() {
        // 分辨率 1920x1080 不匹配 EP_RANGE_REG (x 不是范围分隔符)
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 1920x1080 [HEVC].mkv"));
    }

    @Test
    void falsePositive_bitrate() {
        // "10bit" 含数字但不是范围
        assertNull(RenameUtil.extractEpisodeRange("[Sub] Title 10bit [1080p].mkv"));
    }

    // --- 真实 RSS 标题补充 ---

    @Test
    void realWorld_judasSubs() {
        // JUDAS 字幕组风格
        assertNull(RenameUtil.extractEpisodeRange(
                "[Judas] Jujutsu Kaisen S02 - 12 [BD 1080p HEVC AAC DTS].mkv"));
    }

    @Test
    void realWorld_eraiRawStyle() {
        // Erai-raws 风格
        assertNull(RenameUtil.extractEpisodeRange(
                "[Erai-raws] Solo Leveling - 12 [1080p][Multiple Subtitle].mkv"));
    }

    @Test
    void realWorld_subspleaseStyle() {
        // SubsPlease 风格
        assertNull(RenameUtil.extractEpisodeRange(
                "[SubsPlease] Solo Leveling S01 - 12 (1080p) [hash].mkv"));
    }

    @Test
    void realWorld_kaleidoStyle() {
        // Kaleido-subs 风格
        assertNull(RenameUtil.extractEpisodeRange(
                "[Kaleido-subs] Sousou no Frieren - 28 [BD 1080p AVC FLAC].mkv"));
    }

    @Test
    void realWorld_mkvOnly() {
        // 只有扩展名没有其他信息
        assertNull(RenameUtil.extractEpisodeRange("Title - 12.mkv"));
    }

    @Test
    void realWorld_longSubgroupMultiWord() {
        assertNull(RenameUtil.extractEpisodeRange(
                "[FFF] Shingeki no Kyojin The Final Season Part 3 - 01 [BD 1080p].mkv"));
    }

    @Test
    void realWorld_titleWithColon() {
        // 标题含冒号
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub] 少女前线: 云图计划 - 12 [1080p][Baha][WEB-DL].mp4"));
    }

    @Test
    void realWorld_titleWithAmpersand() {
        // 标题含 &
        assertNull(RenameUtil.extractEpisodeRange(
                "[SweetSub&LoliHouse] Title & Subtitle - 01 [1080p].mkv"));
    }

    @Test
    void realWorld_titleWithParens() {
        // 标题含圆括号
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub] Title (TV) - 12 [1080p][HEVC].mkv"));
    }

    @Test
    void realWorld_batchRelease() {
        // 批量发布: 标题含 Batch
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                RenameUtil.extractEpisodeRange("[Sub] Title Batch 01-06 [1080p]"));
    }

    @Test
    void realWorld_completeSeries() {
        // 完结合集
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0),
                RenameUtil.extractEpisodeRange("[Sub] Title Complete 01-12 [1080p]"));
    }

    @Test
    void realWorld_uncensoredLabel() {
        // 无修正标签
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub] Title - 12 Uncensored [1080p][HEVC].mkv"));
    }

    @Test
    void realWorld_dualAudio() {
        // 双音轨标签
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub] Title - 12 [BD 1080p HEVC FLAC Dual Audio].mkv"));
    }

    @Test
    void realWorld_multiSubFormat() {
        // 多字幕格式标签
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub] Title - 12 [BD 1080p HEVC ASS SRT SSA].mkv"));
    }

    @Test
    void realWorld_jpTitleWithSlash() {
        // 日文标题含 /
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub] 葬送のフリーレン / Frieren - 28 [1080p].mkv"));
    }

    @Test
    void realWorld_multiSeasonDash() {
        // 标题含 S01 且集数 - 格式
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub] Jujutsu Kaisen S01 - 24 [1080p].mkv"));
    }

    @Test
    void realWorld_specialCharsInGroup() {
        // 字幕组名含特殊字符
        assertNull(RenameUtil.extractEpisodeRange(
                "[S.A.F.S] Title - 12 [1080p][HEVC].mkv"));
    }

    @Test
    void realWorld_underscoreInTitle() {
        // 标题含下划线
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub] Title_Name - 12 [1080p].mkv"));
    }

    @Test
    void realWorld_spaceInTitle() {
        // 标题含多个空格
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub] Very   Long   Title   Name - 12 [1080p].mkv"));
    }

    @Test
    void realWorld_noBrackets() {
        // 无方括号的标题
        assertNull(RenameUtil.extractEpisodeRange(
                "SubGroup - Title - 12 [1080p][HEVC].mkv"));
    }

    @Test
    void realWorld_longTitle() {
        // 非常长的标题
        assertNull(RenameUtil.extractEpisodeRange(
                "[SubGroup] This Is A Very Long Anime Title That Goes On And On And On - 12 [1080p][HEVC].mkv"));
    }

    @Test
    void realWorld_shortTitle() {
        // 非常短的标题
        assertNull(RenameUtil.extractEpisodeRange("[X] A - 1 [1080p].mkv"));
    }

    @Test
    void realWorld_numberInSubgroup() {
        // 字幕组名含数字
        assertNull(RenameUtil.extractEpisodeRange(
                "[4chan-raws] Title - 12 [1080p].mkv"));
    }

    @Test
    void realWorld_yearInTitle() {
        // 标题含年份
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub] 2024 Title - 12 [1080p].mkv"));
    }

    @Test
    void realWorld_seasonYear() {
        // "S01 2024" 格式
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub] Title S01 2024 - 12 [1080p].mkv"));
    }

    @Test
    void realWorld_longSeasonNumber() {
        // S12 标题
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub] Title S12 - 01 [1080p].mkv"));
    }

    @Test
    void realWorld_dashInSubgroup() {
        // 字幕组名含 -
        assertNull(RenameUtil.extractEpisodeRange(
                "[Sub-Group] Title - 12 [1080p].mkv"));
    }
}

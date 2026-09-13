package ani.rss.controller;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.StandbyRss;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订阅分享码：<b>白名单复制</b>与编解码往返。
 * <p>
 * 这是全项目唯一会把订阅信息"送出去"的功能，因此安全断言是重点：
 * 分享码里绝不能出现本地路径、进度、评分、上传目标等隐私字段。
 * 采用白名单（而非黑名单）的意义正在于——将来 Ani 新增字段时默认不带出去。
 */
class ShareControllerTest {

    private static Ani fullAni() {
        return new Ani()
                .setId("ani-1")
                .setTitle("番剧A")
                .setUrl("https://mikanani.me/RSS/Bangumi?bangumiId=1")
                .setSeason(2)
                .setSubgroup("字幕组A")
                .setMatch(new ArrayList<>(List.of("番剧A")))
                .setExclude(new ArrayList<>(List.of("720P")))
                .setOva(false)
                .setType("mikan")
                .setBgmUrl("https://bgm.tv/subject/1")
                .setEnable(true)
                .setOffset(0)
                .setPriority(0)
                .setNamingVersion(2)
                // ---- 以下均为不应出现在分享码中的字段 ----
                .setCover("/config/files/cover.jpg")
                .setDownloadPath("/media/anime/番剧A")
                .setCustomDownloadPath(true)
                .setCustomUploadPathTarget("/cloud/番剧A")
                .setScore(8.5)
                .setHealthScore(92)
                .setHealthLevel("good")
                .setCurrentEpisodeNumber(10)
                .setTotalEpisodeNumber(12)
                .setLastDownloadTime(System.currentTimeMillis())
                .setOmitCount(3)
                .setPinyin("fanjuA")
                .setThemoviedbName("Fanju A")
                .setMessage(true)
                .setCompleted(true)
                .setCustomCompletedPathTemplate("/archive/番剧A")
                .setStandbyRssList(new ArrayList<>(List.of(
                        new StandbyRss().setLabel("备用组").setUrl("https://example.com/rss").setOffset(1))));
    }

    @Test
    void sanitize_keeps_subscription_definition() {
        Ani safe = ShareController.sanitize(fullAni());
        assertNotNull(safe);
        assertEquals("番剧A", safe.getTitle());
        assertEquals(2, safe.getSeason());
        assertEquals("字幕组A", safe.getSubgroup());
        assertEquals(0, safe.getPriority());
        assertEquals(List.of("番剧A"), safe.getMatch());
        assertEquals(List.of("720P"), safe.getExclude());
        assertEquals(1, safe.getStandbyRssList().size());
        assertEquals("备用组", safe.getStandbyRssList().get(0).getLabel());
    }

    @Test
    void sanitize_strips_local_paths_and_progress() {
        Ani safe = ShareController.sanitize(fullAni());
        assertNotNull(safe);
        // 本地路径
        assertNull(safe.getCover(), "封面本地路径不应外传");
        assertNull(safe.getDownloadPath(), "本地下载路径不应外传");
        assertNull(safe.getCustomDownloadPath());
        assertNull(safe.getCustomUploadPathTarget(), "上传目标路径不应外传");
        assertNull(safe.getCustomCompletedPathTemplate());
        // 进度与统计
        assertNull(safe.getCurrentEpisodeNumber());
        assertNull(safe.getTotalEpisodeNumber());
        assertNull(safe.getLastDownloadTime());
        assertNull(safe.getOmitCount());
        assertNull(safe.getHealthScore());
        assertNull(safe.getHealthLevel());
        // 评分与派生字段
        assertNull(safe.getScore());
        assertNull(safe.getPinyin());
        assertNull(safe.getThemoviedbName());
        // 本地行为开关
        assertNull(safe.getMessage());
        assertNull(safe.getCompleted());
        // 标识
        assertNull(safe.getId(), "id 由导入端重新生成，不应沿用来源方 id");
    }

    @Test
    void sanitize_returns_null_for_invalid_ani() {
        assertNull(ShareController.sanitize(null));
        assertNull(ShareController.sanitize(new Ani().setTitle("无地址")));
    }

    @Test
    void sanitize_does_not_alias_mutable_lists() {
        Ani source = fullAni();
        Ani safe = ShareController.sanitize(source);
        assertNotNull(safe);
        safe.getMatch().add("被篡改");
        assertFalse(source.getMatch().contains("被篡改"),
                "分享对象不应与源订阅共享可变列表，避免互相污染");
    }

    @Test
    void code_roundtrip_preserves_sanitized_subscriptions() {
        List<Ani> list = List.of(ShareController.sanitize(fullAni()));
        String json = GsonStatic.toJson(list);
        String code = cn.hutool.core.codec.Base64.encodeUrlSafe(
                cn.hutool.core.util.ZipUtil.gzip(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        List<Ani> decoded = ShareController.decode(code);
        assertEquals(1, decoded.size());
        assertEquals("番剧A", decoded.get(0).getTitle());
        assertEquals(2, decoded.get(0).getSeason());
        assertEquals("https://mikanani.me/RSS/Bangumi?bangumiId=1", decoded.get(0).getUrl());
        assertNull(decoded.get(0).getDownloadPath(), "解码后同样不应带回本地路径");
    }

    @Test
    void decode_rejects_garbage() {
        assertThrows(Exception.class, () -> ShareController.decode("!!!not-base64!!!"));
    }
}

package ani.rss.util.other;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-09 / F-11 存量订阅兼容性。
 * <p>
 * 旧版 ani.v2.json 没有 priority / group / tags / quality profile 字段。
 * 验证实际 AniUtil.load() 路径：Gson 反序列化后通过 createAni() 默认值补齐，
 * 而不是要求用户手动删除或迁移旧文件。
 */
class AniLegacyFieldCompatibilityTest {

    @TempDir
    Path tempDir;

    @Test
    void old_ani_file_without_new_fields_gets_safe_defaults() throws Exception {
        String oldJson = "[{"
                + "\"id\":\"legacy-1\","
                + "\"title\":\"旧订阅\","
                + "\"url\":\"https://example.com/rss\","
                + "\"season\":1,"
                + "\"offset\":0,"
                + "\"enable\":true"
                + "}]";
        Files.writeString(tempDir.resolve(AniUtil.FILE_NAME), oldJson, StandardCharsets.UTF_8);

        List<Ani> before = new ArrayList<>(AniUtil.getAniList());
        Config previousConfig = ConfigUtil.CONFIG;
        String previousConfigProperty = System.getProperty("CONFIG");
        try {
            System.setProperty("CONFIG", tempDir.toString());

            AniUtil.load();

            assertEquals(1, AniUtil.getAniList().size());
            Ani loaded = AniUtil.getAniList().get(0);
            assertEquals("旧订阅", loaded.getTitle());
            assertEquals("legacy-1", loaded.getId());
            assertEquals(1, loaded.getPriority(), "旧文件缺失字段应默认普通优先级");
            assertEquals("", loaded.getGroup(), "旧文件缺失字段应默认未分组");
            assertNotNull(loaded.getTags(), "旧文件缺失字段应默认空标签列表");
            assertTrue(loaded.getTags().isEmpty());
            assertFalse(Boolean.TRUE.equals(loaded.getCustomQualityProfileEnable()),
                    "旧订阅不应意外启用自定义质量规则");
            assertNotNull(loaded.getCustomQualityProfile());
            assertTrue(Boolean.TRUE.equals(loaded.getCustomQualityProfile().getEnable()),
                    "自定义质量对象的内部 enable 由外层 customQualityProfileEnable 控制，因此默认应可用");
            assertEquals("", loaded.getCustomQualityProfile().getMinResolution());
            assertEquals("", loaded.getCustomQualityProfile().getMaxResolution());
            assertEquals(0, loaded.getCustomQualityProfile().getMinSeeders());
        } finally {
            AniUtil.getAniList().clear();
            AniUtil.getAniList().addAll(before);
            ConfigUtil.CONFIG = previousConfig;
            if (previousConfigProperty == null) {
                System.clearProperty("CONFIG");
            } else {
                System.setProperty("CONFIG", previousConfigProperty);
            }
        }
    }

    @Test
    void gson_deserialization_itself_remains_backward_compatible() {
        Ani old = GsonStatic.fromJson("{\"title\":\"旧\",\"season\":1}", Ani.class);
        assertNotNull(old);
        assertEquals("旧", old.getTitle());
        assertNull(old.getPriority(), "Gson 本身只负责反序列化，默认补齐由 AniUtil.load 完成");
        assertNull(old.getGroup());
        assertNull(old.getCustomQualityProfile());
    }
}

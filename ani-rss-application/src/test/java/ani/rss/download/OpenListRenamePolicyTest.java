package ani.rss.download;

import ani.rss.entity.OpenListFileInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenList 归位重命名的纯判定边界。
 * <p>
 * 已有的 {@code OpenListRenameCollisionTest} 覆盖目标名碰撞，本类只补来源集数偏移、
 * 半集、无效集数与非视频过滤，以及超时快照的空值归一化。
 */
class OpenListRenamePolicyTest {

    @Test
    @DisplayName("合集重命名先平移来源集数，再套用模板季集")
    void collection_rename_applies_source_offset_before_template_shape() {
        OpenList openList = new OpenList();

        assertEquals("Show S04E24",
                openList.collectionEpisodeReName("Show - 96.mkv", "Show S04E01", 4, -72));
        assertEquals("Show.E25.5",
                openList.collectionEpisodeReName("Show - 97.5.mkv", "Show.E01", 4, -72));
        assertEquals("Show S01E01",
                openList.collectionEpisodeReName("Show - 01.mkv", "Show", null));
    }

    @Test
    @DisplayName("合集校验按来源偏移口径匹配整数集与半集")
    void collection_validation_matches_source_coordinates_including_half_episode() {
        OpenList openList = new OpenList();
        List<OpenListFileInfo> files = List.of(
                new OpenListFileInfo().setName("Show - 96.mkv"),
                new OpenListFileInfo().setName("Show - 97.5.mkv"),
                new OpenListFileInfo().setName("Show - 97.5.zh-CN.ass"));

        OpenList.EpisodeValidation validation = openList.validateCollectionEpisodes(
                List.of(24.0, 25.5), files, -72);

        assertEquals(List.of(24.0, 25.5), validation.expected());
        assertEquals(List.of(24.0, 25.5), validation.matched());
        assertTrue(validation.missing().isEmpty());
    }

    @Test
    @DisplayName("合集校验忽略非视频与无法解析集数的文件")
    void collection_validation_ignores_non_video_and_unparseable_names() {
        OpenList openList = new OpenList();
        List<OpenListFileInfo> files = List.of(
                new OpenListFileInfo().setName("Show S01E01.mkv"),
                new OpenListFileInfo().setName("Show S01E02.ass"),
                new OpenListFileInfo().setName("Show.mkv"));

        OpenList.EpisodeValidation validation = openList.validateCollectionEpisodes(
                List.of(1.0, 2.0), files);

        assertEquals(List.of(1.0), validation.matched());
        assertEquals(List.of(2.0), validation.missing());
    }

    @Test
    @DisplayName("合集校验对空列表与 null 入参返回空结果而不是抛异常")
    void collection_validation_accepts_null_inputs() {
        OpenList.EpisodeValidation validation = new OpenList().validateCollectionEpisodes(null, null);

        assertTrue(validation.expected().isEmpty());
        assertTrue(validation.matched().isEmpty());
        assertTrue(validation.missing().isEmpty());
    }

    @Test
    @DisplayName("超时快照把 null 大小归一为 0，并保留全部文件计数")
    void timeout_snapshot_normalizes_null_sizes() {
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("Show S01E01.mkv", null);
        files.put("Show S01E01.ass", 12L);

        OpenList.TimeoutFileSnapshot snapshot = OpenList.snapshotTimeoutFiles(files);

        assertEquals(Map.of("Show S01E01.mkv", 0L, "Show S01E01.ass", 12L), snapshot.files());
        assertEquals(2, snapshot.videoCount());
        assertEquals(12L, snapshot.totalBytes());

        OpenList.TimeoutFileSnapshot empty = OpenList.snapshotTimeoutFiles(null);
        assertEquals(0, empty.videoCount());
        assertEquals(0L, empty.totalBytes());
        assertTrue(empty.files().isEmpty());
    }
}

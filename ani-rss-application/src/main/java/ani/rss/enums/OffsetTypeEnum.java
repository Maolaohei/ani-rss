package ani.rss.enums;

import ani.rss.offset.AutoOffset;
import ani.rss.offset.BaseOffset;
import ani.rss.offset.BgmOffset;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 自动剧集偏移策略。
 * <p>
 * 跟随上游「新增自动偏移类型, 可跟随 Bangumi 自动偏移」：默认仍是从第 1 集开始编号，
 * 需要按 Bangumi 正篇编号时可以切到 BGM_OFFSET。
 */
@AllArgsConstructor
public enum OffsetTypeEnum {
    /**
     * 从第 1 集开始编号（原有行为）
     */
    AUTO_OFFSET(AutoOffset.class),
    /**
     * 跟随 Bangumi 正篇编号
     */
    BGM_OFFSET(BgmOffset.class);

    @Getter
    private final Class<? extends BaseOffset> baseOffsetClass;
}

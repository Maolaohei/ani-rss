package ani.rss.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 系统自检单条结果
 */
@Data
@Accessors(chain = true)
@Schema(description = "自检结果")
public class DoctorCheck implements Serializable {

    /**
     * 结果等级
     */
    public enum Level {
        /**
         * 正常
         */
        OK,
        /**
         * 需要注意（不影响主流程）
         */
        WARN,
        /**
         * 不通过（会阻断功能）
         */
        FAIL,
        /**
         * 未配置 / 不适用，跳过
         */
        SKIP
    }

    @Schema(description = "检查项标识")
    private String key;

    @Schema(description = "检查项名称")
    private String label;

    @Schema(description = "结果等级 ok/warn/fail/skip")
    private String level;

    @Schema(description = "结论说明")
    private String detail;

    @Schema(description = "下一步建议")
    private String suggestion;

    @Schema(description = "耗时毫秒")
    private Long elapsedMs;

    public static DoctorCheck ok(String key, String label, String detail) {
        return build(key, label, Level.OK, detail, null);
    }

    public static DoctorCheck warn(String key, String label, String detail, String suggestion) {
        return build(key, label, Level.WARN, detail, suggestion);
    }

    public static DoctorCheck fail(String key, String label, String detail, String suggestion) {
        return build(key, label, Level.FAIL, detail, suggestion);
    }

    public static DoctorCheck skip(String key, String label, String detail) {
        return build(key, label, Level.SKIP, detail, null);
    }

    private static DoctorCheck build(String key, String label, Level level, String detail, String suggestion) {
        return new DoctorCheck()
                .setKey(key)
                .setLabel(label)
                .setLevel(level.name().toLowerCase())
                .setDetail(detail)
                .setSuggestion(suggestion);
    }
}

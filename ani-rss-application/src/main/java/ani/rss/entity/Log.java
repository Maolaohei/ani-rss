package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 日志
 */
@Data
@Accessors(chain = true)
@Schema(description = "日志")
public class Log implements Serializable {

    /**
     * 日志信息
     */
    @Schema(description = "日志信息")
    private String message;

    /**
     * 日志级别
     */
    @Schema(description = "日志级别")
    private String level;

    /**
     * 时间戳（epoch 毫秒）
     * <p>
     * 此前前端只能显示 message（时间被拼在字符串里、服务端时区且无法排序/过滤），
     * 用户排查“某时刻为什么没下载”时无法对齐时间线。
     */
    @Schema(description = "时间戳（epoch 毫秒）")
    private Long timestamp;

    /**
     * 类路径
     */
    @Schema(description = "类路径")
    private String loggerName;

    /**
     * 线程名
     */
    @Schema(description = "线程名")
    private String threadName;
}

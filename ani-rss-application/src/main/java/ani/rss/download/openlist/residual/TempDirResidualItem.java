package ani.rss.download.openlist.residual;

@lombok.Data
@lombok.experimental.Accessors(chain = true)
public class TempDirResidualItem implements java.io.Serializable {
    private String id;
    private String name;
    private String savePath;
    private String state;
    private String kind;
    private String action;
    private String error;
    private Boolean protectedCurrent;
    private Boolean cleanable;
    /**
     * 是否已有"删除临时目录"的清理债务（离开收尾路径后仍未确认删除）。
     */
    private Boolean debt;
    /**
     * 该债务已重试次数。
     */
    private Integer attempts;
    /**
     * 下次重试时间戳（0=不自动重试）。
     */
    private Long nextAttemptAt;
    /**
     * 债务最近一次失败原因。
     */
    private String debtError;
}

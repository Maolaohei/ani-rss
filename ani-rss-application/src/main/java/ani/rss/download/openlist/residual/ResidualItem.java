package ani.rss.download.openlist.residual;

@lombok.Data
@lombok.experimental.Accessors(chain = true)
public class ResidualItem implements java.io.Serializable {
    private String id;
    private String name;
    private String state;
    /** ACTIVE / TERMINAL */
    private String kind;
    private Integer progress;
    private String totalBytes;
    private String error;
    private Boolean protectedCurrent;
    /** 清理时将执行的动作说明 */
    private String action;
}

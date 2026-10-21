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
}

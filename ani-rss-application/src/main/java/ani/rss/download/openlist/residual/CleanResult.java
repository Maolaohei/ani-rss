package ani.rss.download.openlist.residual;

@lombok.Data
@lombok.experimental.Accessors(chain = true)
public class CleanResult implements java.io.Serializable {
    private boolean ok;
    private int cleaned;
    private int skipped;
    private int failed;
    private String message;
    private ResidualSnapshot snapshot;
}

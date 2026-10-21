package ani.rss.download.openlist.residual;

import java.util.List;

@lombok.Data
@lombok.experimental.Accessors(chain = true)
public class TempDirResidualSnapshot implements java.io.Serializable {
    private int totalCount;
    private int cleanableCount;
    private int protectedCount;
    private int keepCount;
    private Long scannedAt;
    private Boolean cleaning;
    private String message;
    private List<String> samples;
    private List<TempDirResidualItem> items;

    public static TempDirResidualSnapshot empty() {
        return new TempDirResidualSnapshot()
                .setTotalCount(0)
                .setCleanableCount(0)
                .setProtectedCount(0)
                .setKeepCount(0)
                .setCleaning(false)
                .setSamples(List.of())
                .setItems(List.of());
    }
}

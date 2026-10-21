package ani.rss.download.openlist.residual;

import java.util.List;

@lombok.Data
@lombok.experimental.Accessors(chain = true)
public class ResidualSnapshot implements java.io.Serializable {
    private int activeCount;
    private int terminalCount;
    private int totalCount;
    private Long scannedAt;
    private Boolean cleaning;
    private String message;
    private List<String> samples;
    /** 结构化预览明细（最多 {@link #RESIDUAL_PREVIEW_LIMIT} 条） */
    private List<ResidualItem> items;

    public static ResidualSnapshot empty() {
        return new ResidualSnapshot()
                .setActiveCount(0)
                .setTerminalCount(0)
                .setTotalCount(0)
                .setCleaning(false)
                .setSamples(List.of())
                .setItems(List.of());
    }
}

package ani.rss.download.openlist.residual;

import java.util.List;

@lombok.Data
@lombok.experimental.Accessors(chain = true)
public class TempDirResidualSnapshot implements java.io.Serializable {
    private int totalCount;
    private int cleanableCount;
    private int protectedCount;
    private int keepCount;
    /**
     * 已登记、等后台重试的清理债务条数（{@code DELETE_PENDING}）。
     */
    private int debtPendingCount;
    /**
     * 现场不符、停止自动重试、等人工处理的条数。
     */
    private int debtNeedsHumanCount;
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
                .setDebtPendingCount(0)
                .setDebtNeedsHumanCount(0)
                .setCleaning(false)
                .setSamples(List.of())
                .setItems(List.of());
    }
}

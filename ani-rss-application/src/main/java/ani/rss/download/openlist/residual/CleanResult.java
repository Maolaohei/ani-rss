package ani.rss.download.openlist.residual;

@lombok.Data
@lombok.experimental.Accessors(chain = true)
public class CleanResult implements java.io.Serializable {
    private boolean ok;
    private int cleaned;
    private int skipped;
    private int failed;
    /**
     * 本次<b>未确认删除</b>、已登记清理债务等待后台重试的条数。
     * <p>
     * 与 {@link #failed} 分开统计：限流/冷却导致的"没删成"不是失败结论，
     * 也没有把东西弄坏，只是这次没删掉。旧实现把它们混进 cleaned，
     * 于是面板显示"已清理 3 个"而盘上原样还在。
     */
    private int deferred;
    private String message;
    private ResidualSnapshot snapshot;
}

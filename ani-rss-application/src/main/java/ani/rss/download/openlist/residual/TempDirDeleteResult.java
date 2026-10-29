package ani.rss.download.openlist.residual;

/**
 * 临时目录删除的<b>确认态</b>结果。
 * <p>
 * 为什么需要四态：OpenList/115 的 {@code fs/remove} 对非空目录是异步的，
 * 返回 {@code code=990009}（"操作尚未执行完成"）只代表<b>已受理</b>，不代表已删除；
 * 限流/冷却期还会让请求根本发不出去。旧实现把这些情况一并当成"删掉了"，
 * 于是日志写着「已删除临时目录」而盘上原样还在，媒体管理器随后扫到
 * {@code S1/S1E01.MKV} 与 {@code S1/S1E01/S1E01.MKV} 两份（重复集）。
 * <p>
 * 因此删除动作必须<b>回读确认</b>后才能返回确认态；只有 {@link #DELETED} 与
 * {@link #ALREADY_ABSENT} 才能计入"已清理"统计并销账。
 */
public enum TempDirDeleteResult {

    /**
     * 已回读确认：目录不存在了。
     */
    DELETED,

    /**
     * 目录本来就不存在（列举返回「目录不存在」，属业务事实=确认没有）。
     */
    ALREADY_ABSENT,

    /**
     * 未确认：限流/冷却/超时/990009 尚未落地等，都归此类。
     * <p>
     * 语义是「这次没删成，但可以再试」——不是失败结论，绝不能据此判下载失败，
     * 也不能据此销账。
     */
    FAILED_RETRYABLE,

    /**
     * 现场与预期不符，<b>不自动删</b>，留给人工：顶层同名项是文件而非目录、
     * 顶层缺少本次已归位的成片等。
     */
    MISMATCH_HUMAN;

    public boolean confirmed() {
        return this == DELETED || this == ALREADY_ABSENT;
    }

    /**
     * 是否应停止自动重试、转人工处理。
     */
    public boolean needsHuman() {
        return this == MISMATCH_HUMAN;
    }
}

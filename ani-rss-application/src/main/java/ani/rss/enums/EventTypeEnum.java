package ani.rss.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 结构化事件类型（对外 Webhook）
 * <p>
 * 与 {@link NotificationStatusEnum} 的区别：那个是"给人看的通知状态"，
 * 这个是"给程序消费的事件"，payload 是 JSON 而非渲染好的文本。
 */
@Getter
@AllArgsConstructor
public enum EventTypeEnum {
    /**
     * 开始下载
     */
    DOWNLOAD_START("开始下载"),
    /**
     * 下载完成
     */
    DOWNLOAD_END("下载完成"),
    /**
     * 下载失败
     */
    DOWNLOAD_FAILED("下载失败"),
    /**
     * 新增订阅
     */
    SUBSCRIPTION_ADDED("新增订阅"),
    /**
     * 删除订阅
     */
    SUBSCRIPTION_DELETED("删除订阅"),
    /**
     * 启用/禁用订阅
     */
    SUBSCRIPTION_ENABLED_CHANGED("订阅启停变更"),
    /**
     * 一轮 RSS 扫描结束
     */
    RSS_ROUND_FINISHED("RSS 轮次结束"),
    /**
     * 检测到漏集
     */
    OMIT_DETECTED("检测到漏集"),
    /**
     * 磁盘空间预警
     */
    DISK_WARNING("磁盘空间预警");

    private final String label;
}

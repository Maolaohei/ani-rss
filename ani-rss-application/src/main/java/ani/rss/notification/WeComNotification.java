package ani.rss.notification;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 企业微信群机器人
 * <p>
 * markdown 消息体上限 4096 字节，超出会被企微直接拒绝，这里做一次安全截断。
 */
@Slf4j
public class WeComNotification implements BaseNotification {

    /**
     * 企微 markdown 正文上限（字节），留出余量
     */
    private static final int MAX_CONTENT_BYTES = 3800;

    @Override
    public Boolean test(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        return send(notificationConfig, ani, text, notificationStatusEnum);
    }

    @Override
    public Boolean send(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        String webhook = notificationConfig.getWeComWebhook();
        if (StrUtil.isBlank(webhook)) {
            log.warn("企业微信 未设置 Webhook");
            return false;
        }

        String body = replaceNotificationTemplate(ani, notificationConfig, text, notificationStatusEnum);
        String content = StrUtil.blankToDefault(ani.getTitle(), "ani-rss") + "\n" + body;

        String payload = GsonStatic.toJson(Map.of(
                "msgtype", "markdown",
                "markdown", Map.of("content", truncate(content))
        ));

        return HttpReq.post(webhook.trim())
                .timeout(10_000)
                .body(payload)
                .thenFunction(HttpResponse::isOk);
    }

    static String truncate(String content) {
        if (content == null) {
            return "";
        }
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= MAX_CONTENT_BYTES) {
            return content;
        }
        // 按字节截断后可能切在多字节字符中间，回退到最近的安全边界
        int end = MAX_CONTENT_BYTES;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8) + "\n...(内容过长已截断)";
    }
}

package ani.rss.notification;

import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * ntfy 推送（https://ntfy.sh 或自建）
 * <p>
 * 走 ntfy 的"发布到主题"接口：POST {server}/{topic}，正文即消息内容，
 * 标题/优先级/标签通过请求头传递。
 */
@Slf4j
public class NtfyNotification implements BaseNotification {

    @Override
    public Boolean test(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        return send(notificationConfig, ani, text, notificationStatusEnum);
    }

    @Override
    public Boolean send(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        String serverUrl = StrUtil.removeSuffix(StrUtil.blankToDefault(notificationConfig.getNtfyServerUrl(), ""), "/");
        String topic = notificationConfig.getNtfyTopic();
        if (StrUtil.isBlank(serverUrl)) {
            log.warn("ntfy 未设置 ServerUrl");
            return false;
        }
        if (StrUtil.isBlank(topic)) {
            log.warn("ntfy 未设置 Topic");
            return false;
        }

        String body = replaceNotificationTemplate(ani, notificationConfig, text, notificationStatusEnum);
        String title = StrUtil.blankToDefault(ani.getTitle(), "ani-rss");

        var req = HttpReq.post(serverUrl + "/" + topic)
                .timeout(10_000)
                .header("Title", encodeHeader(title))
                .header("Tags", "ani-rss")
                .header("Priority", String.valueOf(clampPriority(notificationConfig.getNtfyPriority())))
                .body(body);

        String token = notificationConfig.getNtfyToken();
        if (StrUtil.isNotBlank(token)) {
            req.header("Authorization", "Bearer " + token.trim());
        }

        return req.thenFunction(HttpResponse::isOk);
    }

    /**
     * ntfy 的 Title 头只允许 ASCII，中文标题需按 RFC 2047 编码，否则会 400。
     */
    static String encodeHeader(String title) {
        try {
            if (title.chars().allMatch(c -> c < 128)) {
                return title;
            }
            return "=?UTF-8?B?" + java.util.Base64.getEncoder()
                    .encodeToString(title.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "?=";
        } catch (Exception e) {
            return "ani-rss";
        }
    }

    static int clampPriority(Integer priority) {
        if (priority == null) {
            return 3;
        }
        return Math.max(1, Math.min(5, priority));
    }
}

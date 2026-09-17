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
 * Gotify 推送
 * <p>
 * POST {server}/message?token={appToken}，JSON 体包含 title/message/priority。
 */
@Slf4j
public class GotifyNotification implements BaseNotification {

    @Override
    public Boolean test(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        return send(notificationConfig, ani, text, notificationStatusEnum);
    }

    @Override
    public Boolean send(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        String serverUrl = StrUtil.removeSuffix(StrUtil.blankToDefault(notificationConfig.getGotifyServerUrl(), ""), "/");
        String token = notificationConfig.getGotifyToken();
        if (StrUtil.isBlank(serverUrl)) {
            log.warn("Gotify 未设置 ServerUrl");
            return false;
        }
        if (StrUtil.isBlank(token)) {
            log.warn("Gotify 未设置 Token");
            return false;
        }

        String body = replaceNotificationTemplate(ani, notificationConfig, text, notificationStatusEnum);
        String title = StrUtil.blankToDefault(ani.getTitle(), "ani-rss");

        String payload = GsonStatic.toJson(Map.of(
                "title", title,
                "message", body,
                "priority", clampPriority(notificationConfig.getGotifyPriority())
        ));

        return HttpReq.post(serverUrl + "/message?token=" + token.trim())
                .timeout(10_000)
                .body(payload)
                .thenFunction(HttpResponse::isOk);
    }

    static int clampPriority(Integer priority) {
        if (priority == null) {
            return 5;
        }
        return Math.max(0, Math.min(10, priority));
    }
}

package ani.rss.notification;

import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * PushDeer 推送
 * <p>
 * 表单方式 POST {apiUrl}/message/push，字段 pushkey / text / desp。
 * 官方接口对 JSON 与表单都支持，这里用表单以兼容自建实例。
 */
@Slf4j
public class PushDeerNotification implements BaseNotification {

    @Override
    public Boolean test(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        return send(notificationConfig, ani, text, notificationStatusEnum);
    }

    @Override
    public Boolean send(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        String key = notificationConfig.getPushDeerKey();
        if (StrUtil.isBlank(key)) {
            log.warn("PushDeer 未设置 Key");
            return false;
        }
        String apiUrl = StrUtil.removeSuffix(
                StrUtil.blankToDefault(notificationConfig.getPushDeerApiUrl(), "https://api2.pushdeer.com"), "/");

        String body = replaceNotificationTemplate(ani, notificationConfig, text, notificationStatusEnum);
        String title = StrUtil.blankToDefault(ani.getTitle(), "ani-rss");

        return HttpReq.post(apiUrl + "/message/push")
                .form("pushkey", key.trim())
                .form("text", title)
                .form("desp", body)
                .form("type", "markdown")
                .thenFunction(HttpResponse::isOk);
    }
}

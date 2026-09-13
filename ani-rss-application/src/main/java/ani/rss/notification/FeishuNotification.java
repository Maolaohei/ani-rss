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
 * 飞书（Lark）自定义机器人
 * <p>
 * 用 text 消息类型而非交互卡片：卡片对字段长度与转义更敏感，
 * 而通知正文常含文件名里的特殊字符，text 最不容易因转义失败而丢消息。
 */
@Slf4j
public class FeishuNotification implements BaseNotification {

    @Override
    public Boolean test(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        return send(notificationConfig, ani, text, notificationStatusEnum);
    }

    @Override
    public Boolean send(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        String webhook = notificationConfig.getFeishuWebhook();
        if (StrUtil.isBlank(webhook)) {
            log.warn("飞书 未设置 Webhook");
            return false;
        }

        String body = replaceNotificationTemplate(ani, notificationConfig, text, notificationStatusEnum);
        String content = StrUtil.blankToDefault(ani.getTitle(), "ani-rss") + "\n" + body;

        String payload = GsonStatic.toJson(Map.of(
                "msg_type", "text",
                "content", Map.of("text", content)
        ));

        return HttpReq.post(webhook.trim())
                .body(payload)
                .thenFunction(FeishuNotification::isBusinessOk);
    }

    /**
     * 飞书 HTTP 200 也可能是业务失败（如签名校验不通过），需要看 code 字段。
     */
    static boolean isBusinessOk(HttpResponse response) {
        if (!response.isOk()) {
            return false;
        }
        try {
            String body = response.body();
            if (StrUtil.isBlank(body)) {
                return false;
            }
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (json.has("code")) {
                return json.get("code").getAsInt() == 0;
            }
            // 旧版返回 StatusCode
            if (json.has("StatusCode")) {
                return json.get("StatusCode").getAsInt() == 0;
            }
            return true;
        } catch (Exception e) {
            log.debug("解析飞书响应失败: {}", e.getMessage());
            return true;
        }
    }
}

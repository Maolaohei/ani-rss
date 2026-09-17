package ani.rss.notification;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 钉钉自定义机器人
 * <p>
 * 支持"加签"安全设置：配置了 Secret 时自动追加 timestamp + sign 参数。
 * 注意钉钉还要求消息含自定义关键词（或使用加签/IP 白名单），
 * 关键词校验由用户在钉钉侧配置，本实现不做额外改写。
 */
@Slf4j
public class DingTalkNotification implements BaseNotification {

    @Override
    public Boolean test(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        return send(notificationConfig, ani, text, notificationStatusEnum);
    }

    @Override
    public Boolean send(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        String webhook = notificationConfig.getDingTalkWebhook();
        if (StrUtil.isBlank(webhook)) {
            log.warn("钉钉 未设置 Webhook");
            return false;
        }

        String body = replaceNotificationTemplate(ani, notificationConfig, text, notificationStatusEnum);
        String title = StrUtil.blankToDefault(ani.getTitle(), "ani-rss");

        String url = appendSign(webhook.trim(), notificationConfig.getDingTalkSecret());

        String payload = GsonStatic.toJson(Map.of(
                "msgtype", "markdown",
                "markdown", Map.of("title", title, "text", body)
        ));

        return HttpReq.post(url)
                .timeout(10_000)
                .body(payload)
                .thenFunction(HttpResponse::isOk);
    }

    /**
     * 加签：sign = base64(HmacSHA256(timestamp + "\n" + secret, secret))
     */
    static String appendSign(String webhook, String secret) {
        if (StrUtil.isBlank(secret)) {
            return webhook;
        }
        try {
            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret.trim();
            HMac hMac = new HMac(HmacAlgorithm.HmacSHA256, secret.trim().getBytes(StandardCharsets.UTF_8));
            String sign = URLUtil.encode(hMac.digestBase64(stringToSign, false));
            String separator = webhook.contains("?") ? "&" : "?";
            return webhook + separator + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception e) {
            log.warn("钉钉加签失败, 将以未加签方式发送: {}", e.getMessage());
            return webhook;
        }
    }
}

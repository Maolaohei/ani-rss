package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.Ani;
import ani.rss.entity.BgmInfo;
import ani.rss.entity.NotificationConfig;
import ani.rss.entity.web.Result;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.NotificationTypeEnum;
import ani.rss.notification.BaseNotification;
import ani.rss.notification.TelegramNotification;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.BgmUtil;
import ani.rss.util.other.NotificationUtil;
import ani.rss.util.other.TmdbUtils;
import cn.hutool.core.util.ReflectUtil;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import wushuo.tmdb.api.entity.Tmdb;

import java.util.Map;
import java.util.Optional;

@RestController
public class NotificationController extends BaseController {

    @Auth
    @Operation(summary = "测试通知")
    @PostMapping("/testNotification")
    public Result<Void> testNotification(@RequestBody NotificationConfig notificationConfig) {
        NotificationTypeEnum notificationType = notificationConfig.getNotificationType();
        Class<? extends BaseNotification> aClass = NotificationUtil.NOTIFICATION_MAP.get(notificationType);
        BaseNotification baseNotification = ReflectUtil.newInstance(aClass);
        Ani ani = AniUtil.createAni();
        BgmInfo bgmInfo = BgmUtil.getBgmInfo("292970", true);
        BgmUtil.toAni(bgmInfo, ani);

        String image = ani.getImage();
        String title = ani.getTitle();

        ani.setCover(AniUtil.saveCover(image))
                .setCurrentEpisodeNumber(6)
                .setTotalEpisodeNumber(12)
                .setSubgroup("未知字幕组");

        Optional<Tmdb> tmdb = TmdbUtils.getTmdbTv(title);

        tmdb.ifPresent(ani::setTmdb);

        try {
            Boolean ok = baseNotification.test(notificationConfig, ani, "test", NotificationStatusEnum.DOWNLOAD_START);
            if (ok == null || !ok) {
                // 各实现的 send() 在参数不全（token/chatId/key 为空等）时返回 false 而不抛异常，
                // 此前一律返回 Result.success()（message 字面量为 "success"），
                // 用户看到绿色 "success" 却根本没收到通知。
                return Result.error("发送失败：请检查该通知通道的参数是否填写完整（Token / ChatId / Key / 地址等）");
            }
            return Result.success("测试通知已发送，请查看接收端是否收到");
        } catch (Exception e) {
            return Result.error("发送失败：" + e.getMessage());
        }
    }

    @Auth
    @Operation(summary = "最近一次通知发送结果")
    @PostMapping("/notificationLastSend")
    public Result<NotificationUtil.LastSend> notificationLastSend() {
        // 运行期通知失败此前只在日志里，设置页看不到"到底发出去没有"
        NotificationUtil.LastSend last = NotificationUtil.getLastSend();
        if (last == null) {
            return Result.success();
        }
        return Result.success(last);
    }

    @Auth
    @Operation(summary = "新的通知")
    @PostMapping("/newNotification")
    public Result<NotificationConfig> newNotification() {
        NotificationConfig notificationConfig = NotificationConfig.createNotificationConfig();
        return Result.success(notificationConfig);
    }

    @Auth
    @Operation(summary = "获取TG最近消息")
    @PostMapping("/getTgUpdates")
    public Result<Map<String, String>> getUpdates(@RequestBody NotificationConfig notificationConfig) {
        Map<String, String> map = TelegramNotification.getUpdates(notificationConfig);
        return Result.success(map);
    }

}

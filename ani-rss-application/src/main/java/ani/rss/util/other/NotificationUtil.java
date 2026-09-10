package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.NotificationTypeEnum;
import ani.rss.notification.*;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class NotificationUtil {
    private static final ExecutorService EXECUTOR_SERVICE = ExecutorBuilder.create()
            .setCorePoolSize(1)
            .setMaxPoolSize(1)
            .setWorkQueue(new LinkedBlockingQueue<>(256))
            .build();

    public final static Map<NotificationTypeEnum, Class<? extends BaseNotification>>
            NOTIFICATION_MAP =
            Map.of(
                    NotificationTypeEnum.EMBY_REFRESH, EmbyRefreshNotification.class,
                    NotificationTypeEnum.MAIL, MailNotification.class,
                    NotificationTypeEnum.SERVER_CHAN, ServerChanNotification.class,
                    NotificationTypeEnum.SYSTEM, SystemNotification.class,
                    NotificationTypeEnum.TELEGRAM, TelegramNotification.class,
                    NotificationTypeEnum.WEB_HOOK, WebHookNotification.class,
                    NotificationTypeEnum.SHELL, ShellNotification.class,
                    NotificationTypeEnum.FILE_MOVE, FileMoveNotification.class,
                    NotificationTypeEnum.OPEN_LIST_UPLOAD, OpenListUploadNotification.class,
                    NotificationTypeEnum.BARK, BarkNotification.class
            );

    /**
     * 发送通知
     *
     * @param config
     * @param ani
     * @param text
     * @param notificationStatusEnum
     */
    public static synchronized void send(Config config, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        Boolean isMessage = ani.getMessage();

        if (!isMessage) {
            // 未开启此订阅通知
            return;
        }

        List<NotificationConfig> notificationConfigList = config.getNotificationConfigList();
        notificationConfigList = notificationConfigList
                .stream()
                .sorted(Comparator.comparingLong(NotificationConfig::getSort))
                .toList();

        for (NotificationConfig notificationConfig : notificationConfigList) {
            boolean enable = notificationConfig.getEnable();
            // 钳制重试次数到 [1, 10]，防止用户配置任意大导致通知线程长期重试阻塞
            int retry = Math.min(Math.max(ObjectUtil.defaultIfNull(notificationConfig.getRetry(), 1), 1), 10);
            NotificationTypeEnum notificationType = notificationConfig.getNotificationType();
            List<NotificationStatusEnum> statusList = notificationConfig.getStatusList();

            // 通知状态可能被删除
            statusList = statusList.stream().filter(Objects::nonNull).toList();

            if (!enable) {
                // 未开启
                continue;
            }

            if (!statusList.contains(notificationStatusEnum)) {
                // 未启用 通知状态
                continue;
            }

            if (Objects.isNull(notificationType)) {
                // 通知类型可能已经被删除
                continue;
            }

            if (!NOTIFICATION_MAP.containsKey(notificationType)) {
                continue;
            }

            Class<? extends BaseNotification> aClass = NOTIFICATION_MAP.get(notificationType);

            BaseNotification baseNotification = ReflectUtil.newInstance(aClass);
            try {
                EXECUTOR_SERVICE.execute(() -> {
                    int currentRetry = 0;
                    do {
                        if (currentRetry > 0) {
                            log.warn("通知失败 正在重试 第{}次 {}", currentRetry, aClass.getName());
                        }
                        try {
                            Boolean ok = baseNotification.send(notificationConfig, ani, text, notificationStatusEnum);
                            // 发送成功才结束，返回 false 视为失败进入重试
                            if (Boolean.TRUE.equals(ok)) {
                                recordLastSend(notificationType, notificationConfig, true, "已发送");
                                return;
                            }
                            log.warn("通知发送返回失败 {} {}", aClass.getName(), text);
                            recordLastSend(notificationType, notificationConfig, false, "发送返回失败（参数可能不完整）");
                        } catch (Throwable t) {
                            // StackOverflowError 等 Error 只记录不再重试，避免无限递归反复触发
                            if (t instanceof Error) {
                                log.error("通知发送出现 Error 停止重试 {}", aClass.getName(), t);
                                recordLastSend(notificationType, notificationConfig, false,
                                        StrUtil.blankToDefault(t.getMessage(), t.getClass().getSimpleName()));
                                return;
                            }
                            log.error(t.getMessage(), t);
                            recordLastSend(notificationType, notificationConfig, false,
                                    StrUtil.blankToDefault(t.getMessage(), t.getClass().getSimpleName()));
                        }
                        currentRetry += 1;
                        ThreadUtil.sleep(1000);
                    } while (currentRetry < retry);
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // 队列满(256)时丢弃本条通知并计数，绝不让 RejectedExecutionException
                // 上抛中断调用方（download() 中 send 无捕获，曾会中断订阅下载处理）
                log.warn("通知队列已满，丢弃通知 {} {}", aClass.getName(), text);
                recordLastSend(notificationType, notificationConfig, false, "通知队列已满，本条被丢弃");
            }
        }
    }

    /**
     * 最近一次通知发送结果。
     * <p>
     * 运行期通知失败此前只在日志里，用户在设置页完全看不到"通知到底发出去没有"。
     */
    public record LastSend(String notificationType, String comment, boolean success, String message, Long at) {
    }

    private static final java.util.concurrent.atomic.AtomicReference<LastSend> LAST_SEND =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    private static void recordLastSend(NotificationTypeEnum type, NotificationConfig config,
                                      boolean success, String message) {
        try {
            String comment = config == null ? null : config.getComment();
            LAST_SEND.set(new LastSend(
                    type == null ? null : type.name(),
                    StrUtil.blankToDefault(comment, "无备注"),
                    success,
                    StrUtil.blankToDefault(message, success ? "已发送" : "发送失败"),
                    System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("记录通知发送结果失败: {}", e.getMessage());
        }
    }

    public static LastSend getLastSend() {
        return LAST_SEND.get();
    }
}

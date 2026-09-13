package ani.rss.util.other;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.enums.EventTypeEnum;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.thread.ExecutorBuilder;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 结构化事件 Webhook。
 * <p>
 * 既有的 {@code WebHookNotification} 发的是<b>渲染好的文本</b>，接收方要解析只能靠正则。
 * 这里发的是<b>结构化 JSON 事件</b>，供 HomeAssistant / n8n / 自建看板直接消费。
 * <p>
 * 反压策略与通知一致：单线程 + 有界队列（256），队列满则丢弃并计数，
 * <b>绝不</b>让 RejectedExecutionException 上抛中断下载主流程
 * （3.2.15 修过"通知队列满中断订阅下载"这一类问题，这里沿用同样的保护）。
 */
@Slf4j
public final class EventWebhookUtil {

    private static final ExecutorService EXECUTOR = ExecutorBuilder.create()
            .setCorePoolSize(1)
            .setMaxPoolSize(1)
            .setWorkQueue(new LinkedBlockingQueue<>(256))
            .build();

    /**
     * 发送超时（毫秒）：接收方故障时不能长期占用发送线程
     */
    private static final int TIMEOUT_MS = 10_000;

    private static final AtomicLong DROPPED = new AtomicLong(0);
    private static final AtomicLong SENT = new AtomicLong(0);
    private static final AtomicLong FAILED = new AtomicLong(0);

    private EventWebhookUtil() {
    }

    /**
     * 派发事件（异步，失败不影响调用方）
     *
     * @param type 事件类型
     * @param ani  关联订阅，可为 null（如磁盘预警）
     * @param data 事件附加数据
     */
    public static void emit(EventTypeEnum type, Ani ani, Map<String, Object> data) {
        if (type == null) {
            return;
        }
        Config config = ConfigUtil.CONFIG;
        if (config == null || StrUtil.isBlank(config.getEventWebhookUrl())) {
            return;
        }
        if (!isSubscribed(config, type)) {
            return;
        }
        String url = config.getEventWebhookUrl().trim();
        String header = config.getEventWebhookHeader();
        String body = buildPayload(type, ani, data);

        try {
            EXECUTOR.execute(() -> {
                try {
                    var request = HttpReq.post(url, TIMEOUT_MS).body(body);
                    if (StrUtil.isNotBlank(header)) {
                        int idx = header.indexOf(':');
                        if (idx > 0) {
                            request.header(header.substring(0, idx).trim(), header.substring(idx + 1).trim());
                        }
                    }
                    boolean ok = request.thenFunction(HttpResponse::isOk);
                    if (ok) {
                        SENT.incrementAndGet();
                    } else {
                        FAILED.incrementAndGet();
                        log.warn("事件 Webhook 返回非 2xx: {}", type);
                    }
                } catch (Throwable t) {
                    FAILED.incrementAndGet();
                    log.warn("事件 Webhook 发送失败 {}: {}", type, t.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            long dropped = DROPPED.incrementAndGet();
            log.warn("事件 Webhook 队列已满，丢弃事件 {}（累计丢弃 {}）", type, dropped);
        }
    }

    /**
     * 构造事件体（可测的纯函数）
     */
    static String buildPayload(EventTypeEnum type, Ani ani, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", type.name());
        payload.put("label", type.getLabel());
        payload.put("at", System.currentTimeMillis());
        payload.put("source", "ani-rss");
        if (ani != null) {
            Map<String, Object> aniInfo = new LinkedHashMap<>();
            aniInfo.put("id", ani.getId());
            aniInfo.put("title", ani.getTitle());
            aniInfo.put("season", ani.getSeason());
            aniInfo.put("subgroup", ani.getSubgroup());
            aniInfo.put("enable", ani.getEnable());
            aniInfo.put("priority", ani.getPriority());
            payload.put("ani", aniInfo);
        }
        payload.put("data", data == null ? new LinkedHashMap<>() : data);
        return GsonStatic.toJson(payload);
    }

    /**
     * 事件类型过滤：未配置表示全部订阅；支持逗号/空格分隔，也支持 ALL
     */
    static boolean isSubscribed(Config config, EventTypeEnum type) {
        String configured = config.getEventWebhookTypes();
        if (StrUtil.isBlank(configured)) {
            return true;
        }
        Set<String> types = parseTypes(configured);
        if (types.contains("ALL")) {
            return true;
        }
        return types.contains(type.name());
    }

    static Set<String> parseTypes(String configured) {
        Set<String> set = new LinkedHashSet<>();
        for (String part : configured.split("[,，\\s]+")) {
            String trimmed = part.trim().toUpperCase();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return set;
    }

    /**
     * 运行统计，供自检页展示
     */
    public static Map<String, Long> stats() {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("sent", SENT.get());
        map.put("failed", FAILED.get());
        map.put("dropped", DROPPED.get());
        return map;
    }
}

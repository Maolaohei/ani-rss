package ani.rss.util.other;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;

/**
 * 将下载/OpenList 原始错误转成任务管理器可读文案。
 */
public final class TaskFailureHumanizer {
    private TaskFailureHumanizer() {
    }

    public enum ErrorCode {
        DUPLICATE_TASK,
        OFFLINE_TIMEOUT,
        AUTH,
        PERMISSION,
        NETWORK,
        SPACE,
        CANCELED,
        BAD_TORRENT,
        OPENLIST_FAIL,
        UNKNOWN
    }

    public record HumanizedFailure(ErrorCode code, String title, String suggestion, String raw) {
    }

    public static HumanizedFailure humanize(String rawMessage) {
        String raw = StrUtil.blankToDefault(rawMessage, "");
        String lower = raw.toLowerCase(Locale.ROOT);

        if (containsAny(lower, "10008", "任务已存在", "duplicate", "重复的链接")) {
            return new HumanizedFailure(ErrorCode.DUPLICATE_TASK,
                    "离线任务已存在（重复提交）",
                    "无需当坏种处理：等待已有任务完成，或到任务管理器清理残留后再试。",
                    raw);
        }
        if (containsAny(lower, "离线超时", "offline timeout", "超过离线超时", "timeout")) {
            return new HumanizedFailure(ErrorCode.OFFLINE_TIMEOUT,
                    "离线下载超时",
                    "可加大「离线超时」；若网盘已下完可扫描残留后强制清临时目录。",
                    raw);
        }
        if (containsAny(lower, "unauthorized", "401", "apikey", "api key", "鉴权", "登录失败", "forbidden", "403")) {
            return new HumanizedFailure(ErrorCode.AUTH,
                    "下载器鉴权失败",
                    "检查 OpenList Token 或 qBittorrent ApiKey（5.2+ 使用 Bearer）。",
                    raw);
        }
        if (containsAny(lower, "permission", "权限", "denied", "forbidden path")) {
            return new HumanizedFailure(ErrorCode.PERMISSION,
                    "网盘/路径权限不足",
                    "检查 OpenList 临时目录与保存路径权限，以及 Driver 是否可用。",
                    raw);
        }
        if (containsAny(lower, "space", "quota", "磁盘", "空间不足", "disk full", "no space")) {
            return new HumanizedFailure(ErrorCode.SPACE,
                    "存储空间不足",
                    "清理网盘或本地磁盘后再重试。",
                    raw);
        }
        if (containsAny(lower, "cancel", "取消", "aborted")) {
            return new HumanizedFailure(ErrorCode.CANCELED,
                    "任务已取消",
                    "如需继续可重新刷新订阅。",
                    raw);
        }
        if (containsAny(lower, "离线下载未完成", "openlist", "非坏种")) {
            return new HumanizedFailure(ErrorCode.OPENLIST_FAIL,
                    "OpenList 离线未完成",
                    "不是坏种。可查看残留列表、清理终态任务后重试。",
                    raw);
        }
        if (containsAny(lower, "坏种", "无法解析 infohash", "无法解析 infoHash", "拒绝提交", "magnet invalid")) {
            return new HumanizedFailure(ErrorCode.BAD_TORRENT,
                    "种子/磁力异常",
                    "可删种子缓存后等下轮 RSS；若持续失败检查源站链接。",
                    raw);
        }
        if (containsAny(lower, "timed out", "connection", "network", "reset", "unreachable", "503", "502", "504")) {
            return new HumanizedFailure(ErrorCode.NETWORK,
                    "网络或服务暂时不可用",
                    "稍后自动重试；若长期失败检查代理与 OpenList 连通性。",
                    raw);
        }
        String title = StrUtil.isBlank(raw) ? "未知错误" : StrUtil.maxLength(raw, 80);
        return new HumanizedFailure(ErrorCode.UNKNOWN, title, "查看日志获取完整堆栈。", raw);
    }

    public static String formatNotify(String name, String rawMessage) {
        HumanizedFailure h = humanize(rawMessage);
        if (StrUtil.isBlank(name)) {
            return h.title() + " — " + h.suggestion();
        }
        return name + "：" + h.title() + " — " + h.suggestion();
    }

    private static boolean containsAny(String lower, String... keys) {
        for (String k : keys) {
            if (lower.contains(k.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}

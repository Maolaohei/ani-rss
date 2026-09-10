import dayjs from "dayjs";

/**
 * 相对时间格式化。
 *
 * 分档：刚刚 → N分钟前 → N小时前 → 昨天 HH:mm → N天前 → 绝对时间
 *
 * 注意：
 * - 时间戳在未来（服务端时钟偏差、RSS 给了未来的 pubDate）时不再显示"刚刚"，
 *   而是显式标注，避免把异常数据伪装成"刚更新"。
 * - 1 天前与"昨天"重叠时优先给"昨天 HH:mm"，判断"昨天还是前天"更直观。
 */
let formatTime = timestamp => {
    const now = Date.now();
    const elapsedMs = now - timestamp;

    // 未来时间：时钟偏差或源数据异常，直接点明
    if (elapsedMs < -60 * 1000) {
        return `时间异常(${dayjs(new Date(timestamp)).format('MM-DD HH:mm')})`;
    }

    const elapsedMin = Math.floor(elapsedMs / (1000 * 60));

    if (elapsedMin < 1) {
        return "刚刚";
    }

    if (elapsedMin < 60) {
        return `${elapsedMin}分钟前`;
    }

    const hour = Math.floor(elapsedMs / (1000 * 60 * 60));

    if (hour < 24) {
        return `${hour}小时前`;
    }

    const target = new Date(timestamp);
    const nowDate = new Date();

    // 昨天（按日历日判断，而不是固定 24 小时）
    const startOfToday = dayjs().startOf('day');
    const startOfTarget = dayjs(target).startOf('day');
    const dayDiff = startOfToday.diff(startOfTarget, 'day');

    if (dayDiff === 1) {
        return `昨天 ${dayjs(target).format('HH:mm')}`;
    }

    if (dayDiff >= 2 && dayDiff <= 3) {
        return `${dayDiff}天前`;
    }

    // 是否为当前年
    const isCurrentYear = target.getFullYear() === nowDate.getFullYear();

    const template = isCurrentYear ? 'MM-DD HH:mm:ss' : 'YYYY-MM-DD HH:mm:ss';

    return dayjs(target).format(template);
}

export default formatTime;

package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.util.other.AniUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.Week;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.*;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class IcsService {

    private static final ZoneId CALENDAR_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 生成 ICS 内容
     *
     * @return ics 内容
     */
    public String generateIcs() {
        PropertyList propertyList = new PropertyList()
                .add(createVersion())
                .add(new ProdId("-//ani-rss//CN"))
                .add(new CalScale(CalScale.VALUE_GREGORIAN))
                .add(new Method(Method.VALUE_PUBLISH))
                .add(new XProperty("X-WR-CALNAME", "ani-rss"))
                .add(new XProperty("X-WR-TIMEZONE", CALENDAR_ZONE.getId()))
                .add(new XProperty("X-WR-CALDESC", "ani-rss 动漫订阅日历"));

        List<VEvent> list = new ArrayList<>();
        // 单条订阅生成失败仅跳过该条, 不影响整体日历
        for (Ani ani : AniUtil.getAniList()) {
            if (!Boolean.TRUE.equals(ani.getEnable())) {
                continue;
            }
            try {
                list.add(generateEvent(ani));
            } catch (Exception e) {
                log.warn("生成日历事件失败, 已跳过订阅: {}", ani.getTitle());
                log.warn(e.getMessage(), e);
            }
        }

        ComponentList<VEvent> componentList = new ComponentList<>(list);

        return new Calendar(propertyList, componentList).toString();
    }

    /**
     * 生成事件
     *
     * @param ani 订阅
     * @return 事件
     */
    private VEvent generateEvent(Ani ani) {
        boolean ova = Boolean.TRUE.equals(ani.getOva());
        String bgmUrl = ani.getBgmUrl();
        Instant now = Instant.now();
        Date releaseDate = ani.getReleaseDate();

        String summary = buildSummary(ani);
        LocalDate startDate = toLocalDate(releaseDate);
        VEvent event = new VEvent(startDate, startDate.plusDays(1), summary);

        event
                .add(generateUid(ani))
                .add(new DtStamp(now))
                .add(new LastModified(now))
                .add(new Status(Status.VALUE_CONFIRMED))
                .add(new Transp(Transp.VALUE_TRANSPARENT))
                .add(buildCategories(ani))
                .add(buildDescription(ani));

        // bgmUrl 为空或非法时不添加 Url 属性, 而非抛异常
        if (StrUtil.isNotBlank(bgmUrl)) {
            try {
                event.add(new Url(URI.create(bgmUrl)));
            } catch (Exception e) {
                log.warn("bgmUrl 非法, 不添加 Url 属性: {}", bgmUrl);
            }
        }

        if (!ova) {
            event.add(buildRRule(ani));
        }
        return event;
    }

    /**
     * 构建事件摘要，格式为 "标题 第X季"
     *
     * @param ani 订阅
     * @return 摘要
     */
    private String buildSummary(Ani ani) {
        String title = ani.getTitle();
        Integer season = ani.getSeason();

        return StrUtil.format("{} 第{}季", title, season);
    }

    /**
     * 构建分类，包含 "动漫"、季度和类型（TV 或剧场版/OVA）
     *
     * @param ani 订阅
     * @return 分类
     */
    private Categories buildCategories(Ani ani) {
        boolean ova = Boolean.TRUE.equals(ani.getOva());
        Integer season = ani.getSeason();
        String seasonLabel = StrUtil.format("第{}季", season);
        String type = ova ? "剧场版/OVA" : "TV";

        Categories categories = new Categories();
        categories.addCategory("动漫");
        categories.addCategory(seasonLabel);
        categories.addCategory(type);

        return categories;
    }

    /**
     * 构建事件描述，包含标题、类型、季度和 Bangumi 链接
     *
     * @param ani 订阅
     * @return 描述
     */
    private Description buildDescription(Ani ani) {
        String title = ani.getTitle();
        Boolean ova = ani.getOva();
        String type = Boolean.TRUE.equals(ova) ? "剧场版/OVA" : "TV";
        Integer season = ani.getSeason();
        String bgmUrl = ani.getBgmUrl();

        String s = """
                标题: {}
                类型: {}
                季度: {}
                Bangumi: {}
                """;

        s = StrUtil.format(s, title, type, season, bgmUrl);

        return new Description(s);
    }

    private Version createVersion() {
        Version version = new Version();
        version.setValue(Version.VALUE_2_0);
        return version;
    }

    private LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(CALENDAR_ZONE).toLocalDate();
    }

    /**
     * 构建 RRule，表示每周在指定日期重复，直到结束日期
     *
     * @param ani 订阅
     * @return RRule
     */
    private RRule<Temporal> buildRRule(Ani ani) {
        Date releaseDate = ani.getReleaseDate();
        String icsDay = toIcsDay(releaseDate);

        StringBuilder rrule = new StringBuilder();

        // 开始时间
        rrule.append("FREQ=WEEKLY;BYDAY=").append(icsDay);

        // 结束时间
        Date untilDate = getEndDate(ani);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        rrule.append(";UNTIL=").append(dateFormat.format(untilDate));

        return new RRule<>(rrule.toString());
    }

    /**
     * 获取结束日期
     *
     * @param ani 订阅
     * @return 结束日期
     */
    private Date getEndDate(Ani ani) {
        Date releaseDate = ani.getReleaseDate();
        Integer totalEpisodeNumber = ani.getTotalEpisodeNumber();

        // 总集数缺失按 0 处理, 防止拆箱 NPE
        int episodeNumber = Objects.nonNull(totalEpisodeNumber) ? totalEpisodeNumber : 0;

        if (episodeNumber > 0) {
            // 一周一集, 计算结束时间
            return DateUtil.offsetWeek(releaseDate, episodeNumber - 1);
        }
        // 如果没有总集数，默认持续一个月
        return DateUtil.offsetMonth(DateUtil.beginOfDay(new Date()), 1);
    }

    /**
     * 将日期转换为 ICS 中的星期表示
     *
     * @param date 日期
     * @return 星期表示（MO, TU, WE, TH, FR, SA, SU）
     */
    private String toIcsDay(Date date) {
        Week week = DateUtil.dayOfWeekEnum(date);
        return switch (week) {
            case MONDAY -> "MO";
            case TUESDAY -> "TU";
            case WEDNESDAY -> "WE";
            case THURSDAY -> "TH";
            case FRIDAY -> "FR";
            case SATURDAY -> "SA";
            case SUNDAY -> "SU";
        };
    }

    /**
     * 生成 UID，确保每个事件都有唯一标识
     *
     * @param ani 订阅
     * @return UID
     */
    private Uid generateUid(Ani ani) {
        return new Uid("ani-rss-" + ani.getId() + "@ani-rss");
    }
}
package ani.rss.util.other;

import ani.rss.commons.CacheUtils;
import ani.rss.commons.GsonStatic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import wushuo.tmdb.api.entity.Tmdb;
import wushuo.tmdb.api.enums.TmdbTypeEnum;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TMDB「标题 → 条目」缓存。
 * <p>
 * (P1-15) 缓存的是 <b>JSON 快照</b>而不是 {@link Tmdb} 实例，理由有二：
 * ① {@code getRomaji} 会改 {@code name}，② {@code getFinalName(Ani)} 会把实例挂到
 * {@code ani.setTmdb(...)} 上。共享实例会让不同订阅互相影响。本用例把这两条不变量锁住。
 * <p>
 * 全部用例只走"缓存命中"路径，<b>不发起真实网络</b>。
 */
class TmdbUtilsCacheTest {

    private static final String TITLE = "TmdbUtilsCacheTest-不存在的番剧名";
    private static final String KEY = TmdbUtils.tmdbCacheKey(TITLE, TmdbTypeEnum.TV);

    @AfterEach
    void tearDown() {
        CacheUtils.remove(KEY);
        CacheUtils.remove(TmdbUtils.tmdbCacheKey(TITLE, TmdbTypeEnum.MOVIE));
    }

    private static Tmdb sample() {
        return new Tmdb()
                .setId("12345")
                .setName("快照名称")
                .setOriginalName("Snapshot Original")
                .setTmdbGroupId("group-1")
                .setDate(new Date(1_700_000_000_000L))
                .setTmdbType(TmdbTypeEnum.TV)
                .setGenreIds(List.of(1, 2))
                .setOriginCountry(List.of("JP"))
                .setOtherMap(Map.of("k", "v"));
    }

    /* ==================== 快照往返保真 ==================== */

    @Test
    void snapshot_round_trip_preserves_fields_used_by_rename() {
        Tmdb original = sample();
        Tmdb back = GsonStatic.fromJson(GsonStatic.toJson(original), Tmdb.class);

        // 改名链路真正用到的字段：id（tmdbid 后缀）、name/originalName（标题）、
        // tmdbGroupId（剧集组）、date（年份）、tmdbType（剧集/电影）
        assertEquals(original.getId(), back.getId());
        assertEquals(original.getName(), back.getName());
        assertEquals(original.getOriginalName(), back.getOriginalName());
        assertEquals(original.getTmdbGroupId(), back.getTmdbGroupId());
        // date 只比对到"天"：Tmdb.date 上带着第三方库的 @JsonAdapter，
        // 它固定按 yyyy-MM-dd 输出，且字段级注解优先级高于任何 registerTypeAdapter /
        // setDateFormat（已实测无法覆盖）。所以快照对 date 天生是日期粒度。
        // 这不影响功能——getDate() 的消费点只有 DateUtil.year() 与
        // DateUtil.format(..., NORM_DATE_PATTERN)，都不看时分秒。
        assertEquals(day(original.getDate()), day(back.getDate()));
        assertEquals(original.getTmdbType(), back.getTmdbType());
        assertEquals(original.getGenreIds(), back.getGenreIds());
        assertEquals(original.getOriginCountry(), back.getOriginCountry());
        assertEquals(original.getOtherMap(), back.getOtherMap());
    }

    /**
     * 现实形态必须<b>完全无损</b>：TMDB 接口给的是 {@code first_air_date}（{@code yyyy-MM-dd}），
     * 库内适配器解析出来就是当天零点。也就是说线上数据的日期本来就没有时分秒，
     * 快照往返是精确相等的——日期粒度只在"人为塞入带时分秒的 Date"时才可见。
     */
    @Test
    void api_shaped_date_round_trips_exactly() {
        Date startOfDay = Date.from(LocalDate.of(2023, 11, 15)
                .atStartOfDay(ZoneId.systemDefault()).toInstant());

        Tmdb back = GsonStatic.fromJson(GsonStatic.toJson(sample().setDate(startOfDay)), Tmdb.class);

        assertEquals(startOfDay, back.getDate(), "接口形态（当天零点）的日期必须精确往返");
    }

    private static String day(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString();
    }

    /* ==================== 缓存命中 ==================== */

    @Test
    void cached_snapshot_is_returned_on_hit() {
        CacheUtils.put(KEY, GsonStatic.toJson(sample()), TimeUnit.MINUTES.toMillis(10));

        Optional<Tmdb> got = TmdbUtils.getTmdb(TITLE, TmdbTypeEnum.TV);

        assertTrue(got.isPresent(), "缓存命中时应直接返回快照");
        assertEquals("12345", got.get().getId());
        assertEquals("快照名称", got.get().getName());
        assertEquals(TmdbTypeEnum.TV, got.get().getTmdbType());
    }

    @Test
    void each_hit_returns_an_independent_instance() {
        CacheUtils.put(KEY, GsonStatic.toJson(sample()), TimeUnit.MINUTES.toMillis(10));

        Tmdb first = TmdbUtils.getTmdb(TITLE, TmdbTypeEnum.TV).orElseThrow();
        Tmdb second = TmdbUtils.getTmdb(TITLE, TmdbTypeEnum.TV).orElseThrow();

        // 关键不变量：两次命中不能是同一个对象。否则 A 订阅改名时改掉的 name
        // 会串到 B 订阅；调用方就地改 ani.getTmdb() 也会污染缓存。
        assertNotSame(first, second, "缓存命中必须返回互相独立的实例");
    }

    /* ==================== 负结果 ==================== */

    @Test
    void negative_marker_yields_empty_without_network() {
        CacheUtils.put(KEY, TmdbUtils.TMDB_EMPTY_MARKER, TimeUnit.MINUTES.toMillis(10));

        assertTrue(TmdbUtils.getTmdb(TITLE, TmdbTypeEnum.TV).isEmpty(),
                "负结果标记应直接返回 empty，不再发起查询");
    }

    /* ==================== 缓存键隔离 ==================== */

    @Test
    void cache_key_separates_type_and_title() {
        String tv = TmdbUtils.tmdbCacheKey(TITLE, TmdbTypeEnum.TV);
        String movie = TmdbUtils.tmdbCacheKey(TITLE, TmdbTypeEnum.MOVIE);
        String other = TmdbUtils.tmdbCacheKey(TITLE + "x", TmdbTypeEnum.TV);

        // 剧集/电影是两套完全不同的查询结果，同名标题不能互相命中
        assertTrue(!tv.equals(movie), "TV 与 MOVIE 的缓存键必须不同");
        assertTrue(!tv.equals(other), "不同标题的缓存键必须不同");
    }

    @Test
    void blank_title_is_not_cached_and_returns_empty() {
        assertTrue(TmdbUtils.getTmdb("", TmdbTypeEnum.TV).isEmpty());
        assertTrue(TmdbUtils.getTmdb("   ", TmdbTypeEnum.TV).isEmpty());
    }
}

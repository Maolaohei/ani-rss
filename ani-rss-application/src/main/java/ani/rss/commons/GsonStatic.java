package ani.rss.commons;

import ani.rss.entity.IntEnum;
import cn.hutool.core.date.DatePattern;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import lombok.extern.slf4j.Slf4j;
import wushuo.tmdb.api.entity.Tmdb;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.TimeZone;

@Slf4j
public class GsonStatic {

    /**
     * 线程独占的 Gson 实例，用于序列化/反序列化 {@link Tmdb}。
     *
     * <h2>为什么 Tmdb 不能走共享的 {@link #GSON}</h2>
     * {@code Tmdb.date} 上带着第三方库的
     * {@code @JsonAdapter(wushuo.tmdb.api.common.DateAdapter.class)}，而那个适配器
     * <b>把 {@code java.text.DateFormat} 存在实例字段上</b>。Gson 对每个 Gson 实例
     * <b>只创建一个适配器实例并复用</b>，{@link #GSON} 又是全进程共用的静态单例，
     * 于是同一个非线程安全的 {@code SimpleDateFormat} 会被所有线程同时使用
     * （{@code SimpleDateFormat} 内部共享一个可变的 {@code Calendar}）。
     * <p>
     * 实测（8 线程 × 4000 次序列化同一个 {@code Tmdb}）：共享 {@link #GSON} 时
     * <b>约 36% 的日期被写成错误日期</b>，并偶发
     * {@code ArrayIndexOutOfBoundsException}（来自 {@code SimpleDateFormat} 内部数字格式化）；
     * 换成每线程独立实例后 <b>0 错误 / 0 错值</b>。
     * <p>
     * 影响面不小：{@link #GSON} 被 {@code WebMvcConfig} 直接设成了 Spring MVC 的消息转换器，
     * 而 {@code Ani} 里挂着 {@code Tmdb} 字段——订阅列表接口、{@code ani.v2.json} 落盘、
     * 订阅深拷贝都会经过这条路径。日期写坏会直接体现为 NFO 里的年份 / {@code releasedate}
     * 以及「标题 (年份)」改名结果出错。
     * <p>
     * 与 {@link DateAdapter} 处理的是同一类问题（那个修的是 {@code Ani.releaseDate}）。
     * 这里之所以不能照搬"自己写一个线程安全的适配器"，是因为 {@code @JsonAdapter} 打在
     * <b>字段</b>上，优先级高于任何 {@code registerTypeAdapter}/{@code setDateFormat}
     * （已实测：自建 Gson 的 Date 适配器对 {@code Tmdb.date} 完全无效）。
     * 唯一可行的办法就是让承载它的 Gson 实例<b>不跨线程共享</b>。
     * <p>
     * 用 {@link ThreadLocal} 而不是"每次调用新建"：新建一个 Gson 只要约 5µs，但首次
     * 序列化 {@code Tmdb} 需要现建反射适配器图，单次约 90µs；{@code doSync} 会一次
     * 序列化整份订阅列表，这个开销会按订阅数线性叠加。线程独占则只在首次付出一次。
     */
    private static final ThreadLocal<Gson> ISOLATED_GSON = ThreadLocal.withInitial(GsonStatic::isolatedGson);

    /**
     * 把 {@link Tmdb} 的（反）序列化转交给线程独占的 Gson。
     * <p>
     * 挂在共享的 {@link #GSON} 上，因此<b>所有</b>经过 {@link #GSON} 的路径
     * （包括 Spring MVC 消息转换器、{@code AniUtil} 落盘、订阅深拷贝）都会自动生效，
     * 无需逐个改调用点。
     */
    private static final TypeAdapterFactory ISOLATED_TMDB_FACTORY = new TypeAdapterFactory() {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (!Tmdb.class.equals(type.getRawType())) {
                return null;
            }
            return new TypeAdapter<>() {
                @Override
                public void write(JsonWriter out, T value) throws IOException {
                    if (value == null) {
                        out.nullValue();
                        return;
                    }
                    ISOLATED_GSON.get().toJson(value, Tmdb.class, out);
                }

                @Override
                @SuppressWarnings("unchecked")
                public T read(JsonReader in) throws IOException {
                    return (T) ISOLATED_GSON.get().fromJson(in, Tmdb.class);
                }
            };
        }
    };

    public static final Gson GSON = baseBuilder()
            .registerTypeAdapterFactory(ISOLATED_TMDB_FACTORY)
            .create();

    /**
     * 仅供「给人看」的导出场景使用的格式化实例（缩进 + 换行）。
     * <p>
     * {@link #GSON} <b>刻意不</b>开 {@code setPrettyPrinting()}（P1-12）：
     * 它是全应用唯一的 Gson，同时承担落盘、OpenList 请求体与外部响应解析。
     * 解析时空白会被跳过，所以 pretty 对解析无害，但对<b>写出</b>是纯损耗——
     * 实测 200 条订阅（30+ 字段）pretty 224.7KB vs compact 171.4KB，<b>1.31×（+31%）</b>；
     * 请求体里那些换行更是每次出网都白带。
     * <p>
     * 导出（{@code /exportConfig} 的 zip）里仍然给出格式化后的 {@code ani.v2.json} /
     * {@code config.v2.json}——这两个文件是用户会直接打开看的。
     */
    public static final Gson PRETTY_GSON = baseBuilder()
            .setPrettyPrinting()
            .registerTypeAdapterFactory(ISOLATED_TMDB_FACTORY)
            .create();

    /**
     * 除 {@link #ISOLATED_TMDB_FACTORY} 之外的全部 Gson 配置。
     * <p>
     * 抽成方法是为了让线程独占实例与共享实例的配置<b>不可能发生漂移</b>；
     * 同时线程独占实例<b>必须</b>不带 {@link #ISOLATED_TMDB_FACTORY}，
     * 否则它序列化 Tmdb 时又会去找一个线程独占实例，造成无限递归。
     */
    private static GsonBuilder baseBuilder() {
        return new GsonBuilder()
                .disableHtmlEscaping()
                .disableJdkUnsafe()
                .disableInnerClassSerialization()
                .setDateFormat(DatePattern.NORM_DATETIME_PATTERN)
                .registerTypeAdapter(TimeZone.class, new TimeZoneSerializer())
                .registerTypeHierarchyAdapter(IntEnum.class, new IntEnumDeserializer());
    }

    private static Gson isolatedGson() {
        return baseBuilder().create();
    }

    public static <T> T fromJson(JsonElement jsonElement, Class<T> clazz) {
        return GSON.fromJson(jsonElement, clazz);
    }

    public static <T> List<T> fromJsonList(JsonArray array, Class<T> clazz) {
        return array.asList()
                .stream()
                .map(it -> fromJson(it, clazz))
                .toList();
    }

    public static <T> List<T> fromJsonList(String body, Class<T> clazz) {
        JsonArray array = GSON.fromJson(body, JsonArray.class);
        return fromJsonList(array, clazz);
    }

    public static <T> T fromJson(String body, Type type) {
        return GSON.fromJson(body, type);
    }

    public static <T> T fromJson(String body, Class<T> tClass) {
        try {
            return GSON.fromJson(body, tClass);
        } catch (Exception e) {
            log.error("JSON 错误: {}", body);
            log.error(e.getMessage(), e);
            throw e;
        }
    }

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    /**
     * 格式化输出，仅用于导出/给人看的场景（见 {@link #PRETTY_GSON}）
     */
    public static String toPrettyJson(Object obj) {
        return PRETTY_GSON.toJson(obj);
    }

    /**
     * 把一段 JSON 文本重排为格式化输出。
     * <p>
     * 走 {@link JsonParser} → {@link JsonElement} 而不是"解析成 {@code Object} 再序列化"：
     * 后者会把数字统一读成 {@code Double}，大整数（如文件 size）会被写成科学计数法。
     * {@code JsonElement} 保留原始字面量，往返无损。
     */
    public static String prettyJson(String json) {
        return PRETTY_GSON.toJson(JsonParser.parseString(json));
    }

}

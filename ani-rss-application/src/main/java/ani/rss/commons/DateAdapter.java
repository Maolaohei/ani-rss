package ani.rss.commons;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.text.ParsePosition;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.Objects;

/**
 * {@code releaseDate} 的 JSON 适配器。
 *
 * <h2>为什么必须用 DateTimeFormatter 而不是 SimpleDateFormat</h2>
 * 本类通过 {@code @JsonAdapter(DateAdapter.class)} 注册在 {@link ani.rss.entity.Ani} 上，
 * 而 Gson 对每个 Gson 实例<b>只创建一个 adapter 实例并复用</b>；承载它的
 * {@link GsonStatic#GSON} 又是被所有线程共用的静态单例。
 * <p>
 * 于是"一个 adapter 实例"会被并发调用，而 {@link java.text.SimpleDateFormat} <b>不是线程安全的</b>
 * （内部共享一个可变的 {@code Calendar}）：并发序列化 {@code ANI_LIST} 时可能产出错误日期、
 * 抛 {@code NumberFormatException}，或把 {@code releaseDate} 解析成完全不相干的日期。
 * 这类问题极难复现，一旦发生就是"订阅的播出日期莫名变了"。
 * <p>
 * {@link DateTimeFormatter} 是不可变且线程安全的，替换后这个竞争从根上消失。
 *
 * <h2>兼容性：输出格式与解析宽容度都保持不变</h2>
 * <ul>
 *   <li><b>输出</b>：仍为 {@code yyyy-MM-dd}（{@link DatePattern#NORM_DATE_PATTERN}），
 *       与既有 {@code ani.v2.json} 完全一致，不会因为这次改动而重写全部日期。</li>
 *   <li><b>输入</b>：仍保持 {@code SimpleDateFormat.parse} 的宽容度——允许 {@code 2024-1-5}、
 *       也允许尾部带多余内容（如 {@code 2024-01-15 00:00:00}）。
 *       这里用 {@link DateTimeFormatter#parse(CharSequence, ParsePosition)} 而不是
 *       {@code LocalDate.parse}，正是因为后者要求<b>整串消费</b>，会让历史/手改过的
 *       数据突然解析失败。</li>
 * </ul>
 */
public class DateAdapter implements JsonDeserializer<Date>, JsonSerializer<Date> {

    /** 输出格式：与原先的 SimpleDateFormat(NORM_DATE_PATTERN) 一致 */
    private static final DateTimeFormatter OUTPUT_FORMATTER =
            DateTimeFormatter.ofPattern(DatePattern.NORM_DATE_PATTERN);

    /**
     * 输入格式：月份/日期允许 1~2 位（兼容历史数据），并按 ParsePosition 语义
     * 允许尾部存在未消费的内容。
     */
    private static final DateTimeFormatter INPUT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-M-d");

    @Override
    public JsonElement serialize(Date src, Type typeOfSrc, JsonSerializationContext context) {
        if (Objects.isNull(src)) {
            return null;
        }
        LocalDate localDate = src.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        return new JsonPrimitive(OUTPUT_FORMATTER.format(localDate));
    }

    @Override
    public Date deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (Objects.isNull(json) || json.isJsonNull()) {
            return null;
        }

        String s = json.getAsString();

        if (StrUtil.isBlank(s)) {
            return null;
        }

        String regex = "^(19|20)\\d{2}$";
        if (ReUtil.contains(regex, s)) {
            return DateTime.of(s, "yyyy");
        }

        try {
            // 用 ParsePosition 复刻 SimpleDateFormat.parse(String) 的宽容语义：
            // 只要开头能解析出日期就成功，不要求消费整串
            ParsePosition position = new ParsePosition(0);
            TemporalAccessor parsed = INPUT_FORMATTER.parse(s, position);
            if (parsed == null || position.getIndex() <= 0) {
                throw new JsonParseException(StrUtil.format("无法解析日期: {}", s));
            }
            return Date.from(LocalDate.from(parsed)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant());
        } catch (JsonParseException e) {
            throw e;
        } catch (Exception e) {
            throw new JsonParseException(e);
        }
    }
}

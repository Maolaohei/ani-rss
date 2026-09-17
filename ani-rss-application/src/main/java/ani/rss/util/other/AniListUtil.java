package ani.rss.util.other;

import ani.rss.commons.GsonStatic;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

public class AniListUtil {
    /**
     * 获取罗马音
     *
     * @param title
     * @return
     */
    public static String getRomaji(String title) {
        if (StrUtil.isBlank(title)) {
            return "";
        }

        String body = GsonStatic.toJson(Map.of(
                "query", "query ($search: String) { Page (page: 1, perPage: 1) { media (search: $search, type: ANIME) { title { romaji native } } } }",
                "variables", Map.of("search", title)
        ));

        return HttpReq.post("https://graphql.anilist.co")
                .timeout(5000)
                .body(body)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    return Opt.ofNullable(jsonObject)
                            .map(o -> optObject(o, "data"))
                            .map(o -> optObject(o, "Page"))
                            .map(o -> {
                                JsonElement mediaElement = o.get("media");
                                if (mediaElement == null || !mediaElement.isJsonArray()
                                        || mediaElement.getAsJsonArray().isEmpty()) {
                                    return null;
                                }
                                JsonElement first = mediaElement.getAsJsonArray().get(0);
                                return first != null && first.isJsonObject() ? first.getAsJsonObject() : null;
                            })
                            .map(o -> optObject(o, "title"))
                            .map(o -> {
                                JsonElement romajiElement = o.get("romaji");
                                return romajiElement != null && romajiElement.isJsonPrimitive()
                                        ? romajiElement.getAsString()
                                        : null;
                            })
                            .filter(StrUtil::isNotBlank)
                            .orElse("");
                });
    }

    private static JsonObject optObject(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }
}

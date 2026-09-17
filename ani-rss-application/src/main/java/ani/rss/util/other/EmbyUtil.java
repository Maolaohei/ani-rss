package ani.rss.util.other;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.EmbyViews;
import ani.rss.entity.NotificationConfig;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.lang.Assert;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class EmbyUtil {

    /**
     * 扫描媒体库
     * <p>
     * P1-8：去 static synchronized，Emby HTTP 调用无需持类锁串行。
     */
    public static void refresh(NotificationConfig notificationConfig) {
        List<String> viewIds = notificationConfig.getEmbyRefreshViewIds();
        List<EmbyViews> views = getViews(notificationConfig);

        List<String> newViewIds = views
                .stream()
                .filter(view -> viewIds.contains(view.getId()))
                .peek(view -> refresh(view, notificationConfig))
                .map(EmbyViews::getId)
                .toList();

        notificationConfig.setEmbyRefreshViewIds(newViewIds);
    }

    /**
     * 扫描媒体库
     * <p>
     * P1-8：去 static synchronized。
     *
     * @param embyViews 媒体库
     */
    public static void refresh(EmbyViews embyViews, NotificationConfig notificationConfig) {
        String embyHost = notificationConfig.getEmbyHost();
        String embyApiKey = notificationConfig.getEmbyApiKey();

        Assert.notBlank(embyHost, "embyHost 为空");
        Assert.notBlank(embyApiKey, "embyApiKey 为空");

        String s = "Recursive=true&ImageRefreshMode=Default&MetadataRefreshMode=Default&ReplaceAllImages=false&ReplaceAllMetadata=false";

        String id = embyViews.getId();
        HttpReq.post(embyHost + "/emby/Items/" + id + "/Refresh?" + s)
                .header("X-Emby-Token", embyApiKey)
                .then(res -> {
                    if (res.isOk()) {
                        log.info("Emby正在扫描媒体库 id: {} name: {}", embyViews.getId(), embyViews.getName());
                    } else {
                        int status = res.getStatus();
                        log.error("Emby扫描媒体库出错 id: {} name: {} status: {}", embyViews.getId(), embyViews.getName(), status);
                    }
                });
    }

    /**
     * 获取媒体库列表
     * <p>
     * P1-8：去 static synchronized；解析加空守卫，Items 缺失时返回空列表。
     *
     * @return 媒体库列表
     */
    public static List<EmbyViews> getViews(NotificationConfig notificationConfig) {
        String embyHost = notificationConfig.getEmbyHost();
        String embyApiKey = notificationConfig.getEmbyApiKey();

        Assert.notBlank(embyHost, "embyHost 为空");
        Assert.notBlank(embyApiKey, "embyApiKey 为空");

        List<EmbyViews> viewsList = new ArrayList<>();

        JsonArray items = HttpReq.get(embyHost + "/Library/MediaFolders")
                .header("X-Emby-Token", embyApiKey)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject body = GsonStatic.fromJson(res.body(), JsonObject.class);
                    // P1-8：空守卫，body/Items 缺失或形态异常时返回空数组
                    if (body == null) {
                        return new JsonArray();
                    }
                    JsonElement itemsEl = body.get("Items");
                    if (itemsEl == null || itemsEl.isJsonNull() || !itemsEl.isJsonArray()) {
                        return new JsonArray();
                    }
                    return itemsEl.getAsJsonArray();
                });

        // 遍历媒体库
        for (JsonElement item : items) {
            if (item == null || item.isJsonNull() || !item.isJsonObject()) {
                continue;
            }
            JsonObject itemAsJsonObject = item.getAsJsonObject();
            JsonElement idElement = itemAsJsonObject.get("Id");
            JsonElement nameElement = itemAsJsonObject.get("Name");
            if (idElement == null || idElement.isJsonNull() || nameElement == null || nameElement.isJsonNull()) {
                continue;
            }
            String id = idElement.getAsString();
            String name = nameElement.getAsString();
            EmbyViews views = new EmbyViews()
                    .setId(id)
                    .setName(name);
            viewsList.add(views);
        }

        return viewsList;
    }

}

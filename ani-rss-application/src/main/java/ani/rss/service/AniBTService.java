package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GroupRegexUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.WeekComparator;
import ani.rss.entity.Ani;
import ani.rss.entity.AniBT;
import ani.rss.entity.GroupRegex;
import ani.rss.entity.dto.AniBTQueryDTO;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.BgmUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class AniBTService {
    private static final String HOST = "https://anibt.net";

    public AniBT list(AniBTQueryDTO dto) {
        String title = dto.getTitle();

        List<String> bgmIdList = AniUtil.getAniList()
                .stream()
                .map(Ani::getBgmUrl)
                .filter(StrUtil::isNotBlank)
                .map(BgmUtil::getSubjectId)
                .distinct()
                .toList();

        String bgmUrl = dto.getBgmUrl();
        String season = dto.getSeason();

        String bgmId = "";
        if (StrUtil.isNotBlank(bgmUrl)) {
            bgmId = BgmUtil.getSubjectId(bgmUrl);
        }

        if (StrUtil.isNotBlank(title)) {
            season = "";
            bgmId = "";
        }

        AniBT aniBT = HttpReq.get(HOST + "/api/seasons/anime")
                .form("season", season)
                .form("bgmId", bgmId)
                .form("query", title)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonElement dataElement = jsonObject.get("data");
                    if (Objects.isNull(dataElement) || dataElement.isJsonNull()) {
                        // data 缺失, 响应异常, 抛出带语义异常而非 NPE
                        throw new IllegalStateException("AniBT 响应缺少 data 字段");
                    }
                    JsonObject data = dataElement.getAsJsonObject();
                    return GsonStatic.fromJson(data, AniBT.class);
                });

        List<AniBT.ByWeekday> byWeekday = aniBT.getByWeekday();
        if (CollUtil.isEmpty(byWeekday)) {
            byWeekday = new ArrayList<>();
            aniBT.setByWeekday(byWeekday);
        }

        for (AniBT.ByWeekday weekday : byWeekday) {
            List<AniBT.Anime> animeList = weekday.getAnimes();
            if (CollUtil.isEmpty(animeList)) {
                continue;
            }
            animeList = animeList.stream()
                    .filter(Objects::nonNull)
                    .filter(anime -> {
                        if (StrUtil.isBlank(title)) {
                            // 计数缺失视为 0
                            return Optional.ofNullable(anime.getRssReleaseCount()).orElse(0) > 0;
                        }
                        return true;
                    })
                    .sorted(Comparator.comparing(
                            (AniBT.Anime anime) -> Optional.ofNullable(anime.getRating()).orElse(0.0)
                    ).reversed())
                    .peek(anime -> {
                        boolean exists = bgmIdList.contains(anime.getBgmId());
                        anime.setExists(exists);
                    })
                    .toList();
            weekday.setAnimes(animeList);
        }

        WeekComparator weekComparator = new WeekComparator();
        byWeekday = byWeekday.stream()
                .filter(weekday -> CollUtil.isNotEmpty(weekday.getAnimes()))
                .sorted((a, b) ->
                        weekComparator.compare(a.getWeekdayLabel(), b.getWeekdayLabel())
                )
                .toList();
        aniBT.setByWeekday(byWeekday);

        return aniBT;
    }

    public List<AniBT.Group> getGroups(String bgmId) {
        return HttpReq.get(HOST + "/api/anime/groups")
                .form("bgmId", bgmId)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonElement dataElement = jsonObject.get("data");
                    if (Objects.isNull(dataElement) || dataElement.isJsonNull()) {
                        // data 缺失, 响应异常, 抛出带语义异常而非 NPE
                        throw new IllegalStateException("AniBT 字幕组响应缺少 data 字段");
                    }
                    JsonObject data = dataElement.getAsJsonObject();
                    JsonElement groupsElement = data.get("groups");
                    if (Objects.isNull(groupsElement) || groupsElement.isJsonNull()) {
                        // groups 缺失, 返回空列表 (调用方兼容: 控制器直接返回成功空数组)
                        return new ArrayList<>();
                    }
                    List<AniBT.Group> groupList = GsonStatic.fromJsonList(groupsElement.getAsJsonArray(), AniBT.Group.class);
                    for (AniBT.Group group : groupList) {
                        String slug = group.getSlug();
                        String rss = "https://anibt.net/rss/anime.xml?bgmId={}&groupSlug={}";
                        rss = StrUtil.format(rss, bgmId, slug);
                        group.setRss(rss);

                        List<AniBT.Item> items = group.getItems();
                        if (CollUtil.isEmpty(items)) {
                            items = new ArrayList<>();
                            group.setItems(items);
                        }
                        GroupRegex groupRegx = GroupRegexUtils.toGroupRegx(items, AniBT.Item::getTitle);

                        for (AniBT.Item item : items) {
                            Long size = item.getSize();
                            String formatSize = FileUtils.formatSize(size, true);
                            item.setFormatSize(formatSize);
                        }

                        group.setBgmId(bgmId)
                                .setGroupRegex(groupRegx);
                    }
                    return groupList;
                });
    }
}

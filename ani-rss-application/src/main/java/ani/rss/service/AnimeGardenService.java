package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GroupRegexUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.WeekComparator;
import ani.rss.entity.Ani;
import ani.rss.entity.AnimeGarden;
import ani.rss.entity.BgmInfo;
import ani.rss.entity.GroupRegex;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.BgmUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnimeGardenService {
    private static final String HOST = "https://api.animes.garden";

    @Resource
    private CacheService cacheService;

    public List<AnimeGarden.Week> list(String bgmUrl) {
        List<AnimeGarden.Week> weekList = new ArrayList<>();

        if (StrUtil.isNotBlank(bgmUrl)) {
            AnimeGarden.Week week = new AnimeGarden.Week();
            weekList.add(week);

            String bgmId = BgmUtil.getSubjectId(bgmUrl);
            BgmInfo bgmInfo = BgmUtil.getBgmInfo(bgmId);
            String name = BgmUtil.getFinalName(bgmInfo);
            BgmInfo.Images images = bgmInfo.getImages();

            AnimeGarden.Subject subject = new AnimeGarden.Subject();
            subject.setName(name)
                    .setId(bgmId)
                    // images 可能缺失
                    .setCover(Objects.isNull(images) ? "" : StrUtil.nullToEmpty(images.getSmall()))
                    .setExists(true);

            week.setWeekLabel("搜索")
                    .setSubjects(List.of(subject));
            return weekList;
        }

        JsonObject bgmScore = cacheService.getBgmScore();
        JsonObject bgmCover = cacheService.getBgmCover();

        List<String> bgmIdList = AniUtil.getAniList()
                .stream()
                .map(Ani::getBgmUrl)
                .filter(StrUtil::isNotBlank)
                .map(BgmUtil::getSubjectId)
                .distinct()
                .toList();

        List<AnimeGarden.Subject> subjectList = HttpReq.get(HOST + "/subjects")
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonArray subjects = jsonObject.getAsJsonArray("subjects");
                    return GsonStatic.fromJsonList(subjects, AnimeGarden.Subject.class);
                });

        subjectList = subjectList.stream()
                .peek(subject -> {
                    String id = subject.getId();

                    Double score = Optional.ofNullable(bgmScore.get(id))
                            .map(JsonElement::getAsDouble)
                            .orElse(0.0);

                    String cover = Optional.ofNullable(bgmCover.get(id))
                            .map(it -> GsonStatic.fromJson(it, BgmInfo.Images.class))
                            .map(BgmInfo.Images::getSmall)
                            .orElse("");

                    boolean exists = bgmIdList.contains(subject.getId());

                    subject
                            .setScore(score)
                            .setCover(cover)
                            .setExists(exists);
                })
                .sorted(Comparator.comparingDouble(AnimeGarden.Subject::getScore).reversed())
                .toList();

        List<String> weeks = List.of("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六");

        Map<String, List<AnimeGarden.Subject>> map = subjectList.stream()
                .peek(subject -> {
                    Date activedAt = subject.getActivedAt();
                    String weekLabel;
                    if (Objects.isNull(activedAt)) {
                        // 放映时间缺失, 归入"未知"组
                        weekLabel = "未知";
                    } else {
                        int i = DateUtil.dayOfWeek(activedAt) - 1;
                        weekLabel = weeks.get(i);
                    }
                    subject.setWeekLabel(weekLabel);
                })
                .collect(Collectors.groupingBy(AnimeGarden.Subject::getWeekLabel));

        for (String weekLabel : weeks) {
            if (!map.containsKey(weekLabel)) {
                continue;
            }

            AnimeGarden.Week week = new AnimeGarden.Week();
            week.setWeekLabel(weekLabel)
                    .setSubjects(map.get(weekLabel));
            weekList.add(week);
        }

        WeekComparator weekComparator = new WeekComparator();
        weekList = weekList.stream()
                .sorted((a, b) ->
                        weekComparator.compare(a.getWeekLabel(), b.getWeekLabel())
                ).toList();

        return weekList;
    }

    public List<AnimeGarden.Group> group(String bgmId) {
        List<AnimeGarden.Item> items = HttpReq.get(HOST + "/resources")
                .form("subject", bgmId)
                .form("pageSize", 200)
                .form("duplicate", false)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonArray resources = jsonObject.getAsJsonArray("resources");
                    return GsonStatic.fromJsonList(resources, AnimeGarden.Item.class);
                });

        items = items
                .stream()
                .filter(it -> {
                    AnimeGarden.Fansub fansub = it.getFansub();
                    return Objects.nonNull(fansub);
                })
                .peek(it -> {
                    Long size = it.getSize();
                    String formatSize = FileUtils.formatSize(size, true);
                    it.setFormatSize(formatSize);
                })
                .toList();


        Map<String, List<AnimeGarden.Item>> groupIdMap = items.stream()
                .filter(it -> StrUtil.isNotBlank(it.getFansub().getId()))
                .collect(Collectors.groupingBy(it -> it.getFansub().getId()));

        List<AnimeGarden.Group> list = items
                .stream()
                .map(it -> {
                    AnimeGarden.Fansub fansub = it.getFansub();
                    String id = fansub.getId();
                    String name = fansub.getName();
                    Date createdAt = it.getCreatedAt();

                    // fansub 名需编码, 防止特殊字符 (& # 空格等) 拼坏 feed URL
                    String rss = StrUtil.format(
                            "{}/feed.xml?subject={}&fansub={}",
                            HOST,
                            bgmId,
                            URLUtil.encodeAll(StrUtil.nullToEmpty(name))
                    );

                    return new AnimeGarden.Group()
                            .setId(id)
                            .setName(name)
                            .setLastUpdatedAt(createdAt)
                            .setRss(rss)
                            .setBgmId(bgmId);
                })
                // 更新时间缺失按 epoch 起点处理, 排到最后
                .sorted(Comparator.comparing(
                        (AnimeGarden.Group group) -> Optional.ofNullable(group.getLastUpdatedAt()).orElse(new Date(0))
                ).reversed())
                .toList();

        list = CollUtil.distinct(list, AnimeGarden.Group::getId, false);

        for (AnimeGarden.Group group : list) {
            String id = group.getId();
            List<AnimeGarden.Item> itemList = groupIdMap.get(id);
            GroupRegex groupRegx = GroupRegexUtils.toGroupRegx(itemList, AnimeGarden.Item::getTitle);

            group.setItems(itemList)
                    .setGroupRegex(groupRegx);
        }

        return list;
    }
}

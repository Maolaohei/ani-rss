package ani.rss.util.other;

import ani.rss.commons.CacheUtils;
import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.StandbyRss;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.StringEnum;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUnit;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.*;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;


@Slf4j
public class ItemsUtil {

    /**
     * 获取视频列表
     *
     * @param ani
     * @return
     */
    public static synchronized List<Item> getItems(Ani ani) {
        Config config = ConfigUtil.CONFIG;
        String url = ani.getUrl();
        String subgroup = StrUtil.blankToDefault(ani.getSubgroup(), "未知字幕组");
        log.info("[DEBUG] getItems(Ani) url={} subgroup={} standbyRss={}", url, subgroup, config.getStandbyRss());
        List<Item> items = new ArrayList<>(ItemsUtil.getItems(ani, url, subgroup)
                .stream()
                .peek(item -> item.setMaster(true))
                .toList());
        log.info("[DEBUG] 主RSS获取到 {} 条items", items.size());

        if (!config.getStandbyRss()) {
            // v2: 合集优先去重
            if (RenameUtil.isNamingV2(ani)) {
                items = distinctWithCollectionPriority(items);
            }
            items.sort(Comparator.comparingDouble(Item::getEpisode));
            return items;
        }

        List<StandbyRss> standbyRssList = ani.getStandbyRssList();
        log.info("[DEBUG] standbyRssList.size={} standbyRss urls:", standbyRssList.size());
        for (StandbyRss rss : standbyRssList) {
            log.info("[DEBUG]   standby: label={} url={}", rss.getLabel(), rss.getUrl());
            ThreadUtil.sleep(1000);
            subgroup = StrUtil.blankToDefault(rss.getLabel(), "未知字幕组");
            Ani clone = ObjUtil.clone(ani);
            clone.setOffset(rss.getOffset());
            items.addAll(ItemsUtil.getItems(clone, rss.getUrl(), subgroup)
                    .stream()
                    .peek(item -> item.setMaster(false))
                    .toList());
        }
        log.info("[DEBUG] 备用RSS处理完后总items数量: {}", items.size());
        // 多字幕组共存模式
        Boolean coexist = config.getCoexist();
        if (coexist) {
            items = CollUtil.distinct(items, Item::getReName, false);
        } else {
            items = distinctWithCollectionPriority(items);
        }
        items.sort(Comparator.comparingDouble(Item::getEpisode));
        return items;
    }

    /**
     * 获取视频列表
     *
     * @param ani
     * @param rssUrl
     * @param subgroupName
     * @return
     */
    public static List<Item> getItems(Ani ani, String rssUrl, String subgroupName) {
        Config config = ConfigUtil.CONFIG;

        String xml = getRss(rssUrl);

        List<String> exclude = ani.getExclude();
        // v2: 移除范围/合集排除规则，由展开逻辑处理
        if (RenameUtil.isNamingV2(ani)) {
            exclude = new ArrayList<>(exclude);
            exclude.remove("\\d-\\d");
            exclude.remove("合集");
        }
        List<String> match = ani.getMatch();

        List<Item> items = new ArrayList<>();

        Document document = XmlUtil.readXML(xml);
        Node channel = document.getElementsByTagName("channel").item(0);
        NodeList childNodes = channel.getChildNodes();
        List<String> globalExcludeList = config.getExclude();
        Boolean globalExclude = ani.getGlobalExclude();

        for (int i = childNodes.getLength() - 1; i >= 0; i--) {
            Node item = childNodes.item(i);
            String nodeName = item.getNodeName();
            if (!nodeName.equals("item")) {
                continue;
            }
            String itemTitle = "";
            String torrent = "";
            String length = "";
            String infoHash = "";

            String formatSize = "0MiB";

            DateTime pubDate = null;

            NodeList itemChildNodes = item.getChildNodes();
            for (int j = 0; j < itemChildNodes.getLength(); j++) {
                Node itemChild = itemChildNodes.item(j);
                String itemChildNodeName = itemChild.getNodeName();
                if (itemChildNodeName.equals("title")) {
                    itemTitle = itemChild.getTextContent();
                }

                if (itemChildNodeName.equals("enclosure")) {
                    NamedNodeMap attributes = itemChild.getAttributes();
                    torrent = attributes.getNamedItem("url").getNodeValue();
                    length = Optional.of(attributes)
                            .map(it -> it.getNamedItem("length"))
                            .map(Node::getNodeValue)
                            .filter(NumberUtil::isLong)
                            .orElse("1");

                    if (ReUtil.contains(StringEnum.MAGNET_REG, torrent)) {
                        infoHash = ReUtil.get(StringEnum.MAGNET_REG, torrent, 1);
                    }
                    if (ReUtil.contains(StringEnum.ED2K_REG, torrent)) {
                        infoHash = ReUtil.get(StringEnum.ED2K_REG, torrent, 3);
                    }
                }

                if ("guid".equals(itemChildNodeName)) {
                    if (ReUtil.isMatch("^([a-z]|[0-9])+$", itemChild.getTextContent())) {
                        infoHash = itemChild.getTextContent();
                    }
                }

                if ("nyaa:infoHash".equals(itemChildNodeName)) {
                    infoHash = itemChild.getTextContent();
                }
                if (itemChildNodeName.equals("nyaa:size")) {
                    formatSize = itemChild.getTextContent();
                }

                if (itemChildNodeName.equals("pubDate")) {
                    try {
                        pubDate = DateUtil.parse(itemChild.getTextContent(), DatePattern.HTTP_DATETIME_PATTERN);
                    } catch (Exception ignored) {
                    }
                }

                if (itemChildNodeName.equals("torrent")) {
                    try {
                        Element infoHashEl = XmlUtil.getElement((Element) itemChild, "infohash");
                        if (Objects.nonNull(infoHashEl)) {
                            infoHash = infoHashEl.getTextContent();
                        }

                        Element pubDateEl = XmlUtil.getElement((Element) itemChild, "pubDate");

                        if (Objects.nonNull(pubDateEl)) {
                            String pubDateStr = pubDateEl.getTextContent();
                            pubDateStr = pubDateStr.replaceAll("\\.\\d+$", "");
                            pubDate = DateUtil.parse(pubDateStr, DatePattern.UTC_SIMPLE_PATTERN);
                        }
                    } catch (Exception ignored) {
                    }
                }

                if (itemChildNodeName.equals("link")) {
                    String link = itemChild.getTextContent();
                    if (!link.endsWith(".torrent")) {
                        continue;
                    }
                    torrent = link;
                }

            }

            if (StrUtil.isBlank(torrent)) {
                continue;
            }

            if (StrUtil.isBlank(infoHash)) {
                infoHash = FileUtil.mainName(torrent);
            }

            infoHash = infoHash.toLowerCase();
            infoHash = URLUtil.decode(infoHash);

            try {
                length = StrUtil.nullToDefault(length, "0");
                if (formatSize.equals("0MiB")) {
                    formatSize = FileUtils.formatSize(Long.parseLong(length), true);
                }
            } catch (Exception e) {
                log.warn(e.getMessage());
            }

            Item addNewItem = new Item();

            addNewItem
                    .setSubgroup(subgroupName)
                    .setEpisode(1.0)
                    .setTitle(itemTitle)
                    .setReName(itemTitle)
                    .setTorrent(torrent)
                    .setInfoHash(infoHash)
                    .setFormatSize(formatSize)
                    .setPubDate(pubDate);

            Function<String, String> map = s -> {
                String subgroup = ReUtil.get(StringEnum.SUBGROUP_REG_STR, s, 1);
                if (StrUtil.isBlank(subgroup)) {
                    return s;
                }
                if (subgroup.equals(subgroupName)) {
                    return ReUtil.get(StringEnum.SUBGROUP_REG_STR, s, 2);
                }
                return "";
            };

            // 排除
            if (!exclude.isEmpty()) {
                if (exclude.stream().map(map).filter(StrUtil::isNotBlank).anyMatch(s -> ReUtil.contains(s, addNewItem.getTitle()))) {
                    continue;
                }
            }

            // 匹配
            if (!match.isEmpty()) {
                if (match.stream().map(map).filter(StrUtil::isNotBlank).anyMatch(s -> !ReUtil.contains(s, addNewItem.getTitle()))) {
                    continue;
                }
            }

            // 全局排除
            if (globalExclude) {
                if (globalExcludeList.stream().map(map).filter(StrUtil::isNotBlank).anyMatch(s -> ReUtil.contains(s, addNewItem.getTitle()))) {
                    continue;
                }
            }
            items.add(addNewItem);
        }

        log.info("[DEBUG] rename前 items数量: {}", items.size());
        for (Item debugItem : items) {
            log.info("[DEBUG] 待rename: {}", debugItem.getTitle());
        }

        items = items.stream()
                .filter(item -> {
                    try {
                        boolean result = RenameUtil.rename(ani, item);
                        if (!result) {
                            log.info("[DEBUG] rename返回false: {}", item.getTitle());
                        }
                        return result;
                    } catch (Exception e) {
                        log.error("解析rss视频集次出现问题");
                        log.error(e.getMessage(), e);
                    }
                    return false;
                }).toList();

        log.info("[DEBUG] rename后 items数量: {}", items.size());
        for (Item di : items) {
            log.info("[DEBUG]   rename后: ep={} title={}", di.getEpisode(), di.getTitle().substring(0, Math.min(70, di.getTitle().length())));
        }

        // v2: 展开范围/列表/分割类种子
        if (RenameUtil.isNamingV2(ani)) {
            items = expandMultiEpisode(ani, items);
            log.info("[DEBUG] expandMultiEpisode后 items数量: {}", items.size());
            for (Item di : items) {
                log.info("[DEBUG]   expand: ep={} title={}", di.getEpisode(), di.getTitle().substring(0, Math.min(60, di.getTitle().length())));
            }
        }

        // v2: 合集优先去重
        if (RenameUtil.isNamingV2(ani)) {
            items = distinctWithCollectionPriority(items);
            log.info("[DEBUG] distinct后 items数量: {}", items.size());
            for (Item di : items) {
                log.info("[DEBUG]   distinct: ep={} title={}", di.getEpisode(), di.getTitle().substring(0, Math.min(60, di.getTitle().length())));
            }
            return items;
        }

        return CollUtil.distinct(items, item -> item.getEpisode().toString(), true);
    }

    /**
     * 获取rss内容
     *
     * @param url RSS链接
     * @return XML
     */
    public static String getRss(String url) {
        Config config = ConfigUtil.CONFIG;

        String xml = HttpReq.thenClose(
                HttpReq.get(url).timeout(config.getRssTimeout() * 1000),
                res -> {
                    HttpReq.assertStatus(res);
                    HttpReq.assertXml(res);
                    return res.body();
                });

        Assert.notBlank(xml, "xml is blank");
        boolean isXml = StrUtil.startWith(xml, '<');
        Assert.isTrue(isXml, "xml error");

        return xml;
    }

    public static synchronized List<Integer> omitList(Ani ani, List<Item> items) {
        ArrayList<Integer> list = new ArrayList<>();
        Config config = ConfigUtil.CONFIG;
        Boolean omit = config.getOmit();
        if (!omit) {
            return list;
        }
        if (items.isEmpty()) {
            return list;
        }

        if (!ani.getOmit()) {
            return list;
        }

        Boolean ova = ani.getOva();
        if (ova) {
            return list;
        }

        int[] array = items.stream().mapToInt(o -> o.getEpisode().intValue()).distinct().toArray();
        int max = ArrayUtil.max(array);
        int min = ArrayUtil.min(array);
        if (max == min) {
            return list;
        }

        for (int ep = min; ep <= max; ep++) {
            if (ArrayUtil.contains(array, ep)) {
                // 包含该集
                continue;
            }
            if (50 < list.size()) {
                // 防止list过多
                return list;
            }
            list.add(ep);
        }
        return list;
    }

    /**
     * 检测是否缺集
     *
     * @param ani
     * @param items
     */
    public static synchronized void omit(Ani ani, List<Item> items) {
        Config config = ConfigUtil.CONFIG;
        List<Integer> list = omitList(ani, items);

        if (list.isEmpty()) {
            return;
        }

        // 缺少集数大于10个时可能是误判。因此不进行通知
        if (list.size() > 10) {
            return;
        }

        Integer season = ani.getSeason();
        String title = ani.getTitle();
        String id = ani.getId();

        ArrayList<String> sList = new ArrayList<>();

        for (Integer ep : list) {
            String s = StrFormatter.format("缺少集数 {} S{}E{}", title, String.format("%02d", season), String.format("%02d", ep));
            String key = StrFormatter.format("omit:{}:ep-{}", id, ep);
            if (CacheUtils.containsKey(key)) {
                // 一天内已经提醒过了
                continue;
            }
            log.info(s);
            // 缓存一天 不重复发送
            CacheUtils.put(key, s, TimeUnit.DAYS.toMillis(1));
            sList.add(s);
        }

        if (sList.isEmpty()) {
            return;
        }

        NotificationUtil.send(config, ani, CollUtil.join(sList, "\n"), NotificationStatusEnum.OMIT);
    }

    public static int currentEpisodeNumber(Ani ani, List<Item> items) {
        Config config = ConfigUtil.CONFIG;
        Boolean standbyRss = config.getStandbyRss();
        Boolean coexist = config.getCoexist();
        if (standbyRss && coexist) {
            // 开启多字幕组共存模式则只计算主rss集数
            items = items.stream()
                    .filter(Item::getMaster)
                    .toList();
        }

        // 过滤掉x.5集
        items = items
                .stream()
                .filter(it -> it.getEpisode() == it.getEpisode().intValue())
                .toList();

        if (items.isEmpty()) {
            return 0;
        }

        Boolean downloadNew = ani.getDownloadNew();
        if (downloadNew) {
            return items
                    .stream()
                    .mapToInt(item -> item.getEpisode().intValue())
                    .max()
                    .orElse(0);
        }
        return items.size();
    }

    /**
     * 摸鱼检测
     *
     * @param ani
     * @param items
     */
    public static void procrastinating(Ani ani, List<Item> items) {
        Config config = ConfigUtil.CONFIG;
        Boolean procrastinating = config.getProcrastinating();
        Integer procrastinatingDay = config.getProcrastinatingDay();
        if (!procrastinating) {
            return;
        }

        procrastinating = ani.getProcrastinating();

        if (!procrastinating) {
            // 未开启摸鱼检测
            return;
        }

        Boolean procrastinatingMasterOnly = config.getProcrastinatingMasterOnly();
        if (procrastinatingMasterOnly) {
            // 仅启用主rss摸鱼检测
            items = items.stream()
                    .filter(Item::getMaster)
                    .toList();
        }

        items.stream()
                .map(Item::getPubDate)
                .filter(Objects::nonNull)
                .mapToLong(Date::getTime)
                .max()
                .ifPresent(t -> {
                    DateTime date = DateUtil.date(t);
                    DateTime now = DateTime.now();

                    // 时间不对
                    if (now.getTime() <= t) {
                        return;
                    }
                    long day = DateUtil.between(date, now, DateUnit.DAY);
                    if (procrastinatingDay > day) {
                        // 未达到指定摸鱼时间
                        return;
                    }

                    String id = ani.getId();
                    String title = ani.getTitle();

                    String text = StrFormatter.format("检测到{}, 已摸鱼{}天", title, day);

                    String key = StrFormatter.format("procrastinating:{}", id);

                    if (CacheUtils.containsKey(key)) {
                        // 一天内已经提醒过了
                        return;
                    }

                    CacheUtils.put(key, text, TimeUnit.DAYS.toMillis(1));
                    NotificationUtil.send(config, ani, text, NotificationStatusEnum.PROCRASTINATING);
                });
    }

    /**
     * 展开范围/列表/分割类种子为多个独立 Item
     */
    public static List<Item> expandMultiEpisode(Ani ani, List<Item> items) {
        List<Item> expanded = new ArrayList<>();
        int offset = ani.getOffset();

        for (Item item : items) {
            String title = item.getTitle();
            double beforeEp = item.getEpisode();
            List<Double> range = RenameUtil.extractEpisodeRange(title);
            List<Double> list = RenameUtil.extractEpisodeList(title);
            int partEp = RenameUtil.extractPartEpisode(title);
            log.info("[DEBUG-Expand] beforeEp={} range={} list={} partEp={} title={}",
                    beforeEp,
                    range == null ? "null" : range.size(),
                    list == null ? "null" : list.size(),
                    partEp,
                    title.substring(0, Math.min(60, title.length())));

            if (range != null && !range.isEmpty()) {
                // 范围种子: 01-06 → [1,2,3,4,5,6]
                log.info("[DEBUG-Expand] RANGE path! range={}", range);
                for (Double ep : range) {
                    Item clone = cloneItem(item);
                    double newEp = ep + offset;
                    clone.setEpisode(newEp);
                    clone.setEpisodeRange(range);
                    // 同步更新 reName 中的集数（S00E01 → S00E02 等），只匹配 SxxExx 结构
                    String rn = clone.getReName();
                    if (rn != null) {
                        clone.setReName(rn.replaceAll("(S\\d{2})E\\d+", "$1" + String.format("E%02d", (int) newEp)));
                    }
                    expanded.add(clone);
                }
                continue;
            }

            if (list != null && !list.isEmpty()) {
                // 列表种子: 01,02,03 → [1,2,3]
                for (Double ep : list) {
                    Item clone = cloneItem(item);
                    double newEp = ep + offset;
                    clone.setEpisode(newEp);
                    clone.setEpisodeRange(list);
                    String rn = clone.getReName();
                    if (rn != null) {
                        clone.setReName(rn.replaceAll("(S\\d{2})E\\d+", "$1" + String.format("E%02d", (int) newEp)));
                    }
                    expanded.add(clone);
                }
                continue;
            }

            if (partEp > 0 && item.getEpisode() <= 0) {
                // 分割种子: 上篇→1, 下篇→2（仅在未解析出集数时使用part补充）
                Item clone = cloneItem(item);
                clone.setEpisode((double) partEp + offset);
                expanded.add(clone);
                continue;
            }

            // 普通种子，直接保留
            expanded.add(item);
        }
        return expanded;
    }

    private static Item cloneItem(Item item) {
        Item clone = new Item();
        clone.setTitle(item.getTitle())
                .setReName(item.getReName())
                .setTorrent(item.getTorrent())
                .setInfoHash(item.getInfoHash())
                .setFormatSize(item.getFormatSize())
                .setLength(item.getLength())
                .setLocal(item.getLocal())
                .setMaster(item.getMaster())
                .setSubgroup(item.getSubgroup())
                .setPubDate(item.getPubDate());
        return clone;
    }

    /**
     * 按画质优先级排序，同画质按体积降序
     * 优先级：2160p > 1080p > 720p > 480p > 其他
     */
    private static List<Item> sortByQualityAndSize(List<Item> items) {
        return items.stream()
                .sorted(Comparator
                        .comparingInt((Item item) -> getQualityPriority(item.getTitle())).reversed()
                        .thenComparing(Comparator.comparingLong((Item item) ->
                                item.getLength() != null ? item.getLength() : 0L).reversed()))
                .toList();
    }

    /**
     * 画质优先级评分，越高越好
     */
    private static int getQualityPriority(String title) {
        if (title.contains("2160p") || title.contains("4K") || title.contains("2160P")) return 40;
        if (title.contains("1080p") || title.contains("1080P")) return 30;
        if (title.contains("720p") || title.contains("720P")) return 20;
        if (title.contains("480p") || title.contains("480P")) return 10;
        return 0;
    }

    /**
     * 合集优先去重：同一集同时有合集展开源和单集源时，优先保留合集源（带 episodeRange 的条目），
     * 再按画质+体积选最优。单集仅作为合集未覆盖时的补充。
     */
    public static List<Item> distinctWithCollectionPriority(List<Item> items) {
        if (CollUtil.isEmpty(items)) {
            return items;
        }

        // 1. 按集数分组
        Map<Double, List<Item>> grouped = items.stream()
                .filter(item -> item.getEpisode() != null)
                .collect(Collectors.groupingBy(Item::getEpisode));

        List<Item> result = new ArrayList<>();

        // 2. 每集内部分为合集源和单集源，优先合集
        for (Map.Entry<Double, List<Item>> entry : grouped.entrySet()) {
            List<Item> episodeItems = entry.getValue();

            List<Item> collections = new ArrayList<>();
            List<Item> singles = new ArrayList<>();

            for (Item item : episodeItems) {
                if (item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty()) {
                    collections.add(item);
                } else {
                    singles.add(item);
                }
            }

            List<Item> candidates = !collections.isEmpty() ? collections : singles;
            candidates = sortByQualityAndSize(candidates);
            result.add(candidates.get(0));
        }

        // 3. 补充无集数的特殊条目（OVA/剧场版等）
        for (Item item : items) {
            if (item.getEpisode() == null) {
                result.add(item);
            }
        }

        return result;
    }

    /**
     * 预览专用：将合集 items 聚合成树结构，合集作为父节点，子集挂在 children 下
     */
    public static List<Item> groupCollectionForPreview(List<Item> items) {
        if (CollUtil.isEmpty(items)) {
            return items;
        }

        // 按 infoHash 分组（同一合集展开的 clone 共享 infoHash）
        Map<String, List<Item>> grouped = items.stream()
                .filter(item -> item.getEpisodeRange() != null && !item.getEpisodeRange().isEmpty())
                .collect(Collectors.groupingBy(Item::getInfoHash));

        // 已被聚合的 infoHash 集合
        Set<String> groupedHashes = new HashSet<>(grouped.keySet());

        List<Item> result = new ArrayList<>();
        for (Item item : items) {
            if (groupedHashes.contains(item.getInfoHash())) {
                // 只添加一次父节点
                groupedHashes.remove(item.getInfoHash());
                List<Item> children = grouped.get(item.getInfoHash());

                // 父节点：用第一个 clone 的信息，标题保留原始合集标题
                Item parent = new Item();
                parent.setTitle(item.getTitle());
                parent.setReName(item.getReName());
                parent.setTorrent(item.getTorrent());
                parent.setInfoHash(item.getInfoHash());
                parent.setFormatSize(item.getFormatSize());
                parent.setLength(item.getLength());
                parent.setLocal(item.getLocal());
                parent.setMaster(item.getMaster());
                parent.setSubgroup(item.getSubgroup());
                parent.setPubDate(item.getPubDate());
                parent.setEpisodeRange(item.getEpisodeRange());
                parent.setChildren(children);

                result.add(parent);
            } else if (item.getEpisodeRange() == null || item.getEpisodeRange().isEmpty()) {
                // 单集直接保留
                result.add(item);
            }
        }
        return result;
    }

    public static String getSubgroup(List<Item> items) {
        String reg = "^\\[(.+?)]";
        for (Item item : items) {
            String title = item.getTitle();
            if (!ReUtil.contains(reg, title)) {
                title = FileUtil.getName(title);
            }
            if (ReUtil.contains(reg, title)) {
                return ReUtil.get(reg, title, 1);
            }
        }
        return "未知字幕组";
    }

    /**
     * 判断是否为 x.5 集
     *
     * @param item 集数
     * @return 判断结果
     */
    public static Boolean is5(Item item) {
        if (Objects.isNull(item)) {
            return false;
        }
        return is5(item.getEpisode());
    }

    /**
     * 判断是否为 x.5 集
     *
     * @param episode 集数
     * @return 判断结果
     */
    public static Boolean is5(Double episode) {
        if (Objects.isNull(episode)) {
            return false;
        }
        return episode.intValue() != episode;
    }

}

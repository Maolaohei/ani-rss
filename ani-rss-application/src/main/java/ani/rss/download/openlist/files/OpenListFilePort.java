package ani.rss.download.openlist.files;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.OpenListFileInfo;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * files 模块需要的宿主能力。
 */
public interface OpenListFilePort {
    boolean loggedIn();

    Boolean renameEnabled();

    List<OpenListFileInfo> findFiles(String path);

    List<OpenListFileInfo> findEpisodeFiles(String dir, String reName);

    List<OpenListFileInfo> expectedEpisodeVideos(List<OpenListFileInfo> files,
                                                 List<Double> expectedEpisodes,
                                                 Integer expectedSeason,
                                                 int episodeOffset);

    List<OpenListFileInfo> findCloudDownloadFiles();

    String resolveCloudDownloadDir();

    List<OpenListFileInfo> findCloudDownloadEpisodeVideos(List<OpenListFileInfo> cloudFiles,
                                                          String cloudDir,
                                                          List<Double> expectedEpisodes,
                                                          Integer expectedSeason,
                                                          List<String> titleTokens,
                                                          int episodeOffset);

    String describeVideos(List<OpenListFileInfo> videos);

    String extractEpisodeFromFileName(String name);

    String extractSubtitleLangSuffix(String name);

    String collectionEpisodeReName(String originalName, String reName, Integer season, int episodeOffset);

    /**
     * 定点列目录；Optional.empty() 明确表示目录不存在，其它列举失败必须抛出。
     */
    Optional<List<OpenListFileInfo>> listDirectChildrenCached(String path);

    List<OpenListFileInfo> listDirectChildrenStrict(String path);

    List<OpenListFileInfo> listStrict(String path);

    void invalidateListCache(String... paths);

    void fsBatchRename(List<java.util.Map<String, String>> renameObjects, String dir);

    void fsMove(String srcDir, String dstDir, List<String> names);

    void cleanupTempDownloadDir(String savePath, String tempDirName);

    void cleanupCloudDownloadEmptyDirs(Set<String> sourceDirs);

    List<OpenListFileInfo> findCloudDownloadFilesStrict();

    boolean listingCoolingDown();

    void logListingCooldownSkip();

    long listingCooldownRemainingMs();

    void recordHistory(Ani ani, Item item, String note);

    void appendEpisodes(String aniId, String savePath, Set<String> keys);

    void recordPlanGap(Ani ani, Item item, String raw);

    void notifyDownloadCompleted(Ani ani, String message);

    void recordRelocateUncertain();

    void recordSuccess();

    EpisodeValidation validateCollectionEpisodes(List<Double> expected, List<OpenListFileInfo> actualVideos);

    record EpisodeValidation(List<Double> expected, List<Double> matched, List<Double> missing) {
    }
}

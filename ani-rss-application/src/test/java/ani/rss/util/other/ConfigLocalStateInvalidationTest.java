package ani.rss.util.other;

import ani.rss.entity.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F6-4 配置变更联动：哪些字段变化需要让"本地状态判定"的缓存作废。
 * <p>
 * 这个判定之所以被抽成纯函数，是因为它极容易被后续迭代漏掉：新增一个影响判定口径的
 * 配置项时，改动者往往只记得把它接进业务逻辑，忘了接进失效逻辑，于是用户改完设置
 * 仍然看到旧结果（最长 {@code stateCacheTtlDays} = 90 天才自然过期），
 * 而且这种"看起来没生效"的问题极难归因。
 * <p>
 * 对应调用点：{@code ConfigController.setConfig}（Spring 依赖重，不便直接单测），
 * 逻辑本体在这里被完整覆盖。
 */
class ConfigLocalStateInvalidationTest {

    /** 以当前默认配置为基线，只覆盖需要变的字段 */
    private static Config base() {
        Config config = new Config();
        config.setDownloadPathTemplate("/Media/番剧/${title}/Season ${season}");
        config.setOvaDownloadPathTemplate("/Media/剧场版/${title}");
        config.setRename(true);
        config.setFileExist(true);
        config.setDownloadToolType("QBITTORRENT");
        return config;
    }

    @Test
    void no_change_needs_no_invalidation() {
        assertFalse(ConfigUtil.localStateInputChanged(base(), base()));
    }

    @Test
    void download_path_template_change_invalidates() {
        Config next = base().setDownloadPathTemplate("/Media2/番剧/${title}/Season ${season}");
        assertTrue(ConfigUtil.localStateInputChanged(base(), next));
    }

    @Test
    void ova_download_path_template_change_invalidates() {
        Config next = base().setOvaDownloadPathTemplate("/Media2/剧场版/${title}");
        assertTrue(ConfigUtil.localStateInputChanged(base(), next));
    }

    @Test
    void rename_change_invalidates() {
        // 影响最大的一项：直接切换"按真实文件判定"与"回退种子记录判定"两种口径
        assertTrue(ConfigUtil.localStateInputChanged(base(), base().setRename(false)));
        assertTrue(ConfigUtil.localStateInputChanged(base().setRename(false), base()));
    }

    @Test
    void file_exist_change_invalidates() {
        assertTrue(ConfigUtil.localStateInputChanged(base(), base().setFileExist(false)));
    }

    @Test
    void download_tool_type_change_invalidates() {
        // 本地磁盘 ↔ 网盘：快照来源(Source)与构建代价都不同
        assertTrue(ConfigUtil.localStateInputChanged(base(), base().setDownloadToolType("OPENLIST")));
    }

    @Test
    void unrelated_config_change_does_not_invalidate() {
        // 反向用例：如果这里退化成"任何配置变更都失效"，媒体库缓存会被无谓清空，
        // F2 的分级 TTL 也就白做了。挑几个与判定口径无关的字段做代表。
        assertFalse(ConfigUtil.localStateInputChanged(base(), base().setRssSleepMinutes(30)));
        assertFalse(ConfigUtil.localStateInputChanged(base(), base().setMikanHost("mikanani.me")));
        assertFalse(ConfigUtil.localStateInputChanged(base(), base().setAutoDisabled(false)));
        assertFalse(ConfigUtil.localStateInputChanged(base(), base().setProxy(true).setProxyHost("127.0.0.1")));
    }

    @Test
    void null_arguments_are_treated_as_no_change() {
        // setConfig 里 oldConfig 理论上不会为 null，但这里不抛异常比抛异常安全：
        // 一次"配置已保存成功、失效判断炸了"会把整个保存请求变成 500。
        assertFalse(ConfigUtil.localStateInputChanged(null, base()));
        assertFalse(ConfigUtil.localStateInputChanged(base(), null));
        assertFalse(ConfigUtil.localStateInputChanged(null, null));
    }

    @Test
    void null_vs_value_is_a_change() {
        // 老配置里字段缺失(null)、新配置补上值 —— 这确实改变了判定口径，必须失效
        Config oldConfig = base().setRename(null);
        assertTrue(ConfigUtil.localStateInputChanged(oldConfig, base().setRename(true)));
    }
}

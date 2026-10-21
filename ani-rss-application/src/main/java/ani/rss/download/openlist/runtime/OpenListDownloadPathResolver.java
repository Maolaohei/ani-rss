package ani.rss.download.openlist.runtime;

import ani.rss.entity.Ani;

/**
 * OpenList 残留扫描与遗留修复所需的下载路径解析能力。
 * <p>
 * 下载模块只依赖这个窄接口，不再通过 Spring 容器反查下载服务。
 */
@FunctionalInterface
public interface OpenListDownloadPathResolver {
    OpenListDownloadPathResolver NONE = ani -> null;

    String resolve(Ani ani);
}

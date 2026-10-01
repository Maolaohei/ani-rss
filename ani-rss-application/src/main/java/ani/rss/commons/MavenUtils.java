package ani.rss.commons;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.XmlUtil;
import cn.hutool.system.OsInfo;
import cn.hutool.system.SystemUtil;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Slf4j
public class MavenUtils {
    /**
     * 版本缓存；"None" 表示尚未解析到。volatile 保证多线程可见性
     * （{@link #getVersion()} 已去掉 synchronized）。
     */
    private static volatile String version = "None";

    public static CurrentFile getCurrentFile() {
        OsInfo osInfo = SystemUtil.getOsInfo();
        String splitStr = osInfo.isWindows() ? ";" : ":";
        String s = System.getProperty("java.class.path")
                .split(splitStr)[0];
        return new CurrentFile()
                .setFile(new File(s));
    }

    /**
     * 获取当前版本号。
     * <p>
     * 不再把 {@code JarFile} 常驻在静态字段上（fork 旧实现 {@code public static JarFile JAR_FILE}
     * 从不关闭，句柄/mmap 泄漏；且初始化失败会抛 {@code ExceptionInInitializerError}，
     * 被 {@code Runner} 捕获后直接 {@code System.exit(1)}，让"读不到版本"升级成"起不来"）。
     * 这里改成每次读取都 try-with-resources 打开并立即关闭。
     */
    public static String getVersion() {
        if (!"None".equalsIgnoreCase(version)) {
            return version;
        }
        try {
            CurrentFile currentFile = getCurrentFile();
            if (currentFile.isFile()) {
                String pomPath = "META-INF/maven/ani.rss/ani-rss-application/pom.xml";
                try (JarFile jarFile = new JarFile(currentFile.getFile())) {
                    JarEntry jarEntry = jarFile.getJarEntry(pomPath);
                    if (Objects.isNull(jarEntry)) {
                        return "None";
                    }
                    try (InputStream inputStream = jarFile.getInputStream(jarEntry)) {
                        String s = IoUtil.readUtf8(inputStream);
                        version = ReUtil.get("<version>(.*?)</version>", s, 1);
                        return version;
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        File file = new File("pom.xml");
        if (file.exists()) {
            Document document = XmlUtil.readXML(file);
            Element element = XmlUtil.getElement(document.getDocumentElement(), "version");
            if (Objects.nonNull(element)) {
                version = element.getTextContent();
            }
        }
        return version;
    }

    @Data
    @Accessors(chain = true)
    public static class CurrentFile implements Serializable {
        private File file;

        public String getName() {
            return file.getName();
        }

        public Boolean isDirectory() {
            return file.isDirectory();
        }

        public Boolean isFile() {
            return file.isFile();
        }

        public Boolean isExe() {
            if (isDirectory()) {
                return false;
            }

            String extName = FileUtil.extName(file);
            if (StrUtil.isBlank(extName)) {
                return false;
            }

            return "exe".equalsIgnoreCase(extName);
        }

        public Boolean isJar() {
            if (isDirectory()) {
                return false;
            }

            String extName = FileUtil.extName(file);
            if (StrUtil.isBlank(extName)) {
                return false;
            }

            return "jar".equalsIgnoreCase(extName);
        }
    }

}

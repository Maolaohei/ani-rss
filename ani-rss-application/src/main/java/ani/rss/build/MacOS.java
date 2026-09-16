package ani.rss.build;

import ani.rss.util.basic.HttpReq;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.system.OsInfo;
import cn.hutool.system.SystemUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Slf4j
public class MacOS implements Runnable {
    @Override
    public void run() {
        String basedir = System.getProperty("basedir");

        File target = new File(basedir, "target");
        Assert.isTrue(target.exists(), "target not exists");

        File macosZip = new File(target, "ani-rss-macos-main.zip");

        if (!macosZip.exists()) {
            String url = "https://github.com/wushuo894/ani-rss-macos/archive/refs/heads/main.zip";
            /*
            原实现用 hutool 的 HttpUtil.downloadFile，是全项目唯一一处「裸下载」：
            hutool 默认 timeout = -1（无限等待），也没有统一的代理设置。
            这里改走 HttpReq（默认带代理、UA、跟随重定向），并显式区分连接/读取超时（P2-6）。
            */
            HttpReq.get(url, 1000 * 30, 1000 * 60 * 5)
                    .then(res -> {
                        HttpReq.assertStatus(res);
                        FileUtil.writeFromStream(res.bodyStream(), macosZip, true);
                    });
        }

        ZipUtil.unzip(macosZip, macosZip.getParentFile());

        File jarFile = new File(target, "ani-rss.jar");

        Path path = Path.of(target.getPath(), "ani-rss-macos-main/ani-rss.app/Contents/MacOS/ani-rss.jar");

        FileUtil.copy(Paths.get(jarFile.getPath()), path, StandardCopyOption.REPLACE_EXISTING);


        OsInfo osInfo = SystemUtil.getOsInfo();

        if (osInfo.isMac()) {
            String appDir = Path.of(target.getPath(), "ani-rss-macos-main/ani-rss.app").toString();

            RuntimeUtil.execForStr("chmod", "-R", "755", appDir);
            RuntimeUtil.execForStr("xattr", "-cr", appDir);
        }
    }
}

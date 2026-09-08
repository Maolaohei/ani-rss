package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.URLUtils;
import ani.rss.entity.Global;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpConnection;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@RestController
public class ProxyImageController extends BaseController {

    /**
     * 图片路径段白名单: 仅允许字母数字与 . _ -, 显式拒绝 . 与 .. 相对段
     */
    private static final String IMG_SEGMENT_REGEX = "^[A-Za-z0-9._-]+$";

    @Auth
    @Operation(summary = "下载并缓存图片")
    @GetMapping("/proxyImage")
    public void proxyImage(@RequestParam("imgUrl") String imgUrl) {
        imgUrl = imgUrl.replace(" ", "+");
        imgUrl = Base64.decodeStr(imgUrl);
        URLUtils.verify(imgUrl);

        // 发起请求前对 host 做 DNS 全量解析校验, 任一解析结果命中内网/回环/链路本地即拒绝 (SSRF 防护)
        URLUtils.verifyResolveAll(URLUtil.url(imgUrl).getHost());

        HttpServletResponse response = Global.RESPONSE.get();

        // 30 天
        long maxAge = 86400 * 30;
        setCacheControl(response, maxAge);

        String contentType = getContentType(URLUtil.getPath(imgUrl));

        File configDir = ConfigUtil.getConfigDir();
        File imgRoot = Path.of(configDir.toString(), "img").toFile();

        // 路径穿越防护: 归一化 URL path, 命中与下载两个分支共用同一受限路径
        File imgFile = resolveImgFile(imgRoot, URLUtil.getPath(imgUrl));

        FileUtil.mkdir(imgFile.getParentFile());
        if (imgFile.exists()) {
            try {
                response.setContentType(contentType);
                response.setContentLengthLong(imgFile.length());

                @Cleanup
                InputStream inputStream = FileUtil.getInputStream(imgFile);
                @Cleanup
                OutputStream outputStream = response.getOutputStream();
                IoUtil.copy(inputStream, outputStream);
            } catch (Exception ignored) {
            }
            return;
        }

        getImg(imgUrl, is -> {
            try {
                FileUtil.writeFromStream(is, imgFile, true);

                response.setContentType(contentType);
                response.setContentLengthLong(imgFile.length());

                @Cleanup
                BufferedInputStream inputStream = FileUtil.getInputStream(imgFile);
                @Cleanup
                ServletOutputStream outputStream = response.getOutputStream();
                IoUtil.copy(inputStream, outputStream);
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 解析图片存储路径: 拒绝 "." / ".." 段与空段, 目录与文件名均走白名单,
     * 最终 normalize 后断言仍位于 img 根目录内, 防止路径穿越读写
     */
    private File resolveImgFile(File imgRoot, String imgPath) {
        Assert.notBlank(imgPath, "非法的图片路径");
        // 内部空段 (连续斜杠) 直接拒绝
        Assert.isFalse(imgPath.contains("//"), "非法的图片路径");

        List<String> segments = StrUtil.split(imgPath, '/', true, true);
        Assert.isFalse(segments.size() < 2, "非法的图片路径");
        for (String segment : segments) {
            Assert.isFalse(".".equals(segment) || "..".equals(segment), "非法的图片路径");
            Assert.isTrue(ReUtil.contains(IMG_SEGMENT_REGEX, segment), "非法的图片路径");
        }

        // 与原逻辑保持一致: img/<父目录名>/<文件名>
        String dirName = segments.get(segments.size() - 2);
        String fileName = segments.get(segments.size() - 1);

        Path target = Path.of(imgRoot.toString(), dirName, fileName).normalize();
        Assert.isTrue(target.startsWith(imgRoot.toPath().normalize()), "非法的图片路径");
        return target.toFile();
    }

    public void getImg(String url, Consumer<InputStream> consumer) {
        getImg(url, 0, consumer);
    }

    private void getImg(String url, int depth, Consumer<InputStream> consumer) {
        if (depth > 3) {
            // 重定向深度超限, 防止重定向环导致栈溢出
            log.warn("图片代理重定向深度超限, 放弃获取: {}", url);
            return;
        }
        URI host = URLUtil.getHost(URLUtil.url(url));
        HttpReq.thenClose(
                HttpReq.get(url),
                res -> {
                    HttpConnection httpConnection = (HttpConnection) ReflectUtil.getFieldValue(res, "httpConnection");
                    URI host1 = URLUtil.getHost(httpConnection.getUrl());

                    // 处理mikan自动重定向的问题
                    if (host.toString().equals(host1.toString())) {
                        try {
                            @Cleanup
                            InputStream inputStream = res.bodyStream();
                            consumer.accept(inputStream);
                        } catch (Exception ignored) {
                        }
                        return;
                    }
                    String newUrl = url.replace(host.toString(), host1.toString());
                    getImg(newUrl, depth + 1, consumer);
                });
    }
}

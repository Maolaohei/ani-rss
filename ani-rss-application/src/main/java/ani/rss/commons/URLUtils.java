package ani.rss.commons;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;

public class URLUtils {
    /**
     * 自动添加http协议
     *
     * @param urlStr
     * @return
     */
    public static String getUrlStr(String urlStr) {
        if (StrUtil.isBlank(urlStr)) {
            return "";
        }

        if (!ReUtil.contains("^https?://", urlStr)) {
            urlStr = StrFormatter.format("http://{}", urlStr);
        }

        if (urlStr.endsWith("/")) {
            urlStr = urlStr.substring(0, urlStr.length() - 1);
        }

        return urlStr;
    }

    /**
     * 校验url安全性:
     * 统一解析为 InetAddress 后按 回环/通配/链路本地/站点本地 全量判定,
     * 覆盖 IPv6 字面量(含括号)、127.0.0.0/8、0.0.0.0、169.254.0.0/16、
     * 十进制/八进制/十六进制整型 IPv4(如 2130706433) 以及解析到内网地址的域名;
     * DNS 解析失败视为非法地址, 直接拒绝
     */
    public static void verify(String s) {
        Assert.notBlank(s, "URL 为空");

        String regex = "^https?://";

        Assert.isTrue(ReUtil.contains(regex, s), "错误的链接");

        URL url = URLUtil.url(s);

        String host = url.getHost();

        Assert.notBlank(host, "URL 缺少 host");

        checkForbiddenAddress(parseAddress(stripIpv6Extras(host)));
    }

    /**
     * 对 host 做 DNS 全量解析校验: 任一解析结果命中回环/内网/链路本地即拒绝。
     * 用于发起请求前的 SSRF 防护, 防止域名部分解析结果指向内网
     */
    public static void verifyResolveAll(String host) {
        Assert.notBlank(host, "host 为空");

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(stripIpv6Extras(host));
        } catch (UnknownHostException e) {
            // DNS 解析失败视为非法, 防止绕过校验
            throw new IllegalArgumentException(StrFormatter.format("无法解析 host: {}", host));
        }
        Assert.notEmpty(addresses, "无法解析 host: {}", host);

        for (InetAddress address : addresses) {
            checkForbiddenAddress(address);
        }
    }

    /**
     * 判定地址是否为禁止访问的本地/内网地址, 命中即抛出异常
     */
    private static void checkForbiddenAddress(InetAddress address) {
        Assert.notNull(address, "禁止访问内部网络");
        Assert.isFalse(address.isLoopbackAddress(), "禁止访问回环网络");
        Assert.isFalse(address.isAnyLocalAddress(), "禁止访问通配地址");
        Assert.isFalse(address.isLinkLocalAddress(), "禁止访问链路本地地址");
        Assert.isFalse(address.isSiteLocalAddress(), "禁止访问内部网络");
        if (address instanceof Inet6Address) {
            // fc00::/7 为 IPv6 内网(唯一本地地址), InetAddress 未提供现成判定
            byte[] bytes = address.getAddress();
            Assert.isFalse((bytes[0] & 0xFE) == 0xFC, "禁止访问内部网络");
        }
    }

    /**
     * 解析主机地址, 解析失败视为非法并抛出异常
     */
    private static InetAddress parseAddress(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            // DNS 解析失败视为非法, 直接拒绝
            throw new IllegalArgumentException(StrFormatter.format("无法解析 host: {}", host));
        }
    }

    /**
     * 去除 IPv6 字面量的方括号与 ZoneID(如 [fe80::1%25eth0] → fe80::1)
     */
    private static String stripIpv6Extras(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int zoneIndex = host.indexOf('%');
        if (zoneIndex > 0) {
            host = host.substring(0, zoneIndex);
        }
        return host;
    }
}

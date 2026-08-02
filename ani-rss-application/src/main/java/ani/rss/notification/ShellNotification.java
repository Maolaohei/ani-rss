package ani.rss.notification;

import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.util.other.RenameUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.system.SystemUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Shell
 */
@Slf4j
public class ShellNotification implements BaseNotification {

    /**
     * 测试
     *
     * @param notificationConfig     通知配置
     * @param ani                    订阅
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     */
    @Override
    public void test(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        send(notificationConfig, ani, text, notificationStatusEnum);
    }

    /**
     * 发送通知
     *
     * @param notificationConfig     通知配置
     * @param ani                    订阅
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     * @return 是否成功
     */
    @Override
    public Boolean send(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        String shell = notificationConfig.getShell();
        int aliveLimit = notificationConfig.getAliveLimit();
        Assert.notBlank(shell, "shell 不能为空");

        // 值清洗：RSS/订阅/BGM 等外部数据在拼入 shell 命令前剥离全部 shell 元字符（防注入主防线）
        Ani safeAni = sanitizeAni(ani);
        NotificationConfig safeConfig = new NotificationConfig();
        cn.hutool.core.bean.BeanUtil.copyProperties(notificationConfig, safeConfig);
        safeConfig.setComment(sanitizeShellValue(notificationConfig.getComment()));
        String safeText = sanitizeShellValue(text);
        safeConfig.setNotificationTemplate(shell);

        shell = replaceNotificationTemplate(safeAni, safeConfig, safeText, notificationStatusEnum);
        shell = shell.trim();

        // 安全检查：检测常见 shell 注入模式（纵深防御，覆盖值清洗遗漏面如 BGM 集标题）
        if (containsShellInjection(shell)) {
            log.error("检测到潜在 shell 注入，已阻止执行: {}", shell);
            return false;
        }

        log.debug(shell);

        Process process = null;
        try {
            process = new ProcessBuilder(getShellCommand(shell))
                    .redirectErrorStream(true)
                    .start();
            long pid = process.pid();
            log.info("pid: {}", pid);

            CompletableFuture<String> outputFuture = readStreamAsync(process.getInputStream());

            process.onExit()
                    .thenAccept(result -> {
                        try {
                            String output = outputFuture.get(5, TimeUnit.SECONDS);
                            log.debug(output);
                        } catch (Exception ignored) {
                        }

                        int exitValue = result.exitValue();
                        log.info("已退出 pid: {}, exit: {}", pid, exitValue);
                    });

            try {
                boolean b = process.waitFor(aliveLimit, TimeUnit.SECONDS);
                if (!b) {
                    log.info("存活超时已强制停止 pid: {}", pid);
                    process.destroy();
                    return false;
                }
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (Objects.nonNull(process)) {
                process.destroy();
            }
        }
    }

    private static String[] getShellCommand(String fullCommand) {
        boolean isWindows = SystemUtil.getOsInfo().isWindows();
        return isWindows ?
                new String[]{"cmd.exe", "/c", fullCommand} :
                new String[]{"sh", "-c", fullCommand};
    }

    private static CompletableFuture<String> readStreamAsync(InputStream input) {
        return CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                throw new CompletionException("流读取异常", e);
            }
        });
    }

    /**
     * 清洗将拼入 shell 命令的外部数据：剥离全部 shell 元字符，防命令注入。
     * 保留字母/数字/中文、空格与常见安全符号（. , : / - _ = + % @）
     */
    private static String sanitizeShellValue(String s) {
        if (s == null) {
            return null;
        }
        return s.replaceAll("[;&|<>$`\\\\\"'()\\[\\]{}*?!~#\\n\\r\\t]", " ");
    }

    /**
     * 复制订阅并清洗所有会进入通知模板的字段（title/subgroup 等）
     */
    private static Ani sanitizeAni(Ani ani) {
        if (ani == null) {
            return null;
        }
        Ani copy = new Ani();
        cn.hutool.core.bean.BeanUtil.copyProperties(ani, copy);
        copy.setTitle(sanitizeShellValue(ani.getTitle()));
        copy.setThemoviedbName(sanitizeShellValue(ani.getThemoviedbName()));
        copy.setSubgroup(sanitizeShellValue(ani.getSubgroup()));
        copy.setBgmUrl(sanitizeShellValue(ani.getBgmUrl()));
        try {
            // 预取 jpTitle 并清洗，避免模板替换时触发网络获取未清洗值
            copy.setJpTitle(sanitizeShellValue(RenameUtil.getJpTitle(ani)));
        } catch (Exception ignored) {
            // 网络失败：至少清洗已复制的原值，避免未清洗数据进入命令
            copy.setJpTitle(sanitizeShellValue(copy.getJpTitle()));
        }
        return copy;
    }

    /**
     * 检测 shell 注入模式
     */
    private static boolean containsShellInjection(String command) {
        // 检测管道、命令替换、后台执行等危险模式
        String[] dangerousPatterns = {
                "\\$\\(",        // $(command)
                "`[^`]*`",      // `command`
                ";\\s*rm\\s",   // ; rm
                "\\|\\s*rm\\s", // | rm
                "&&\\s*rm\\s",  // && rm
                ">\\s*/dev",    // > /dev
                "mkfs\\s",      // mkfs
                "dd\\s+if=",    // dd if=
                "\\$\\{",       // 未替换模板变量/变量注入
                ";\\s*(wget|curl|nc|python|perl|bash|sh|base64|chmod|chown|kill|pkill|systemctl|docker|sudo|su|shutdown|reboot)\\s",
                "\\|\\s*(wget|curl|nc|python|perl|bash|sh|base64|chmod|chown|kill|pkill|systemctl|docker|sudo|su|shutdown|reboot)\\s",
                "&&\\s*(wget|curl|nc|python|perl|bash|sh|base64|chmod|chown|kill|pkill|systemctl|docker|sudo|su|shutdown|reboot)\\s",
        };
        for (String pattern : dangerousPatterns) {
            if (command.matches("(?i).*" + pattern + ".*")) {
                return true;
            }
        }
        return false;
    }

}

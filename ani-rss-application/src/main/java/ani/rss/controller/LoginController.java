package ani.rss.controller;

import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.entity.web.Result;
import ani.rss.entity.web.ResultCode;
import ani.rss.util.other.AuthUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class LoginController extends BaseController {

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<String> login(@RequestBody Login myLogin) {
        AuthUtil.limitLoginAttempts(false);

        Config config = ConfigUtil.CONFIG;
        Login login = config.getLogin();

        String myUsername = myLogin.getUsername();
        String myPassword = myLogin.getPassword();

        Assert.notBlank(myUsername, "用户名不能为空");
        Assert.notBlank(myPassword, "密码不能为空");

        String username = login.getUsername();
        String password = login.getPassword();

        // 一个令牌只能用于一个ip
        String ip = AuthUtil.getIp();
        if (config.getVerifyLoginIp()) {
            myLogin.setIp(ip);
        } else {
            myLogin.setIp("");
        }

        PasswordCheck check = checkPassword(password, myPassword);

        if (username.equals(myUsername) && check.matched()) {
            if (check.normalizedPassword() != null) {
                // 归一化到"客户端提交的那个摘要"，并落盘：
                // ① 让配置收敛到唯一形态，历史双层哈希不会一直漂移；
                // ② 更关键的是让 token 两侧序列化一致（见 checkPassword 的说明）——
                //    不做这一步，登录能过但后续所有 @Auth 接口都会 403「登录已失效」。
                login.setPassword(check.normalizedPassword());
                ConfigUtil.sync();
                log.info("登录密码形态已归一化为客户端摘要 {}", username);
            }
            AuthUtil.resetKey();
            clearLimitLoginAttempts();
            log.info("登录成功 {} ip: {}", username, ip);
            String s = AuthUtil.getAuth(myLogin);
            return new Result<String>()
                    .setCode(ResultCode.HTTP_OK)
                    .setMessage("登录成功")
                    .setData(s);
        }
        AuthUtil.limitLoginAttempts(true);
        log.warn("登陆失败 {} ip: {}", myUsername, ip);
        // 登录失败不再随机 sleep：防暴力破解已由 AuthUtil 限流承担，
        // 失败时 sleep 会长时间占用 Tomcat worker，放大慢速攻击面
        return Result.error("用户名或密码错误");
    }

    /**
     * 清除限制尝试次数
     */
    private void clearLimitLoginAttempts() {
        String ip = AuthUtil.getIp();
        // AuthUtil.clearLoginAttempts(ip) 由限流重构统一提供 (按 ip 清除登录尝试计数)
        AuthUtil.clearLoginAttempts(ip);
    }

    /**
     * 密码校验结果。
     *
     * @param matched            是否通过
     * @param normalizedPassword 需要写回配置的规范化值；{@code null} = 配置已是对的形态、无需写回
     */
    record PasswordCheck(boolean matched, String normalizedPassword) {
        static final PasswordCheck FAIL = new PasswordCheck(false, null);
    }

    /**
     * 密码校验（纯函数，便于单测直接固化）。
     * <p>
     * <b>配置里存的必须是 SHA-256 摘要形态</b>（64 位 hex）：不再直比明文，也不再兼容 MD5。
     * 这是 {@code c9d23565 fix(security)} 的安全意图，保留。
     * <p>
     * 但那条提交把原先**唯一真正生效**的"摘要直比"（{@code password.equals(myPassword)}）一并删掉了，
     * 而前端登录页发的一直是 {@code sha256(明文)}（{@code http.js} 里的 CryptoJS.SHA256）。
     * 于是：
     * <ul>
     *   <li><b>单层配置</b>（首启随机口令 {@code ConfigUtil.load}、配置页首次保存
     *       {@code ConfigView.saveConfig} 都存 {@code sha256(明文)}）：客户端发的就是配置值本身，
     *       需要**直接相等**才认得出；</li>
     *   <li><b>双层配置</b>（配置页在"没改密码"时再保存一次，会把已有摘要再哈希一遍）：
     *       配置值 {@code sha256(sha256(明文))}，需要再哈希一次才相等。</li>
     * </ul>
     * 只留后者会让单层配置**根本登录不了**（实测：配置单层 + 提交摘要 → 500「用户名或密码错误」）。
     * <p>
     * <b>为什么必须给出"规范化值"</b>：签发的 token 是 {@code sha256(json(Login))}，
     * 签发侧用的是<b>请求体</b>里的 Login、校验侧（{@code ani.rss.auth.fun.Header}）用的是
     * <b>配置里</b>的 Login。两侧 {@code password} 只要不同，token 就永远对不上——
     * 表现为"登录 200，然后所有接口 403「登录已失效」"。把配置值归一化成提交值，
     * 这个一致性就由构造保证，而不是靠"恰好两层哈希"的巧合。
     * <p>
     * 大小写差异也一并归一化：hex 摘要理论上可以大小写混用，{@code equalsIgnoreCase} 放行之后
     * 若配置与提交值字面不同，同样会让 token 对不上。
     *
     * @param storedPassword    配置里的密码
     * @param submittedPassword 客户端提交的密码
     */
    static PasswordCheck checkPassword(String storedPassword, String submittedPassword) {
        if (StrUtil.isBlank(storedPassword) || StrUtil.isBlank(submittedPassword)) {
            return PasswordCheck.FAIL;
        }
        if (!isSha256Hex(storedPassword)) {
            // 明文 / MD5（32 位）等非摘要形态一律拒绝，避免退化成弱口令直比
            return PasswordCheck.FAIL;
        }
        if (storedPassword.equalsIgnoreCase(submittedPassword)) {
            // 字面完全一致时无需写回；仅大小写差异也要归一化，否则 token 仍会对不上
            return new PasswordCheck(true,
                    storedPassword.equals(submittedPassword) ? null : submittedPassword);
        }
        if (storedPassword.equalsIgnoreCase(SecureUtil.sha256(submittedPassword))) {
            // 历史双层哈希：放行并把配置拉回单层，下次登录走上面的直接相等分支
            return new PasswordCheck(true, submittedPassword);
        }
        return PasswordCheck.FAIL;
    }

    /**
     * 是否为 SHA-256 十六进制摘要（64 位 hex）。
     */
    static boolean isSha256Hex(String s) {
        return StrUtil.length(s) == 64 && ReUtil.isMatch("^[0-9a-fA-F]{64}$", s);
    }
}

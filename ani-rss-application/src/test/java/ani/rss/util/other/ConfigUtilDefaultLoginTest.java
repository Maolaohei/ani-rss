package ani.rss.util.other;

import ani.rss.entity.Login;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认登录信息识别：只认历史默认用户名+密码摘要，避免把用户改过的登录信息误报为默认。
 */
class ConfigUtilDefaultLoginTest {

    @Test
    void default_login_is_detected() {
        assertTrue(ConfigUtil.isDefaultLogin(new Login(ConfigUtil.DEFAULT_USERNAME, ConfigUtil.DEFAULT_PASSWORD)));
    }

    @Test
    void changed_username_is_not_default() {
        assertFalse(ConfigUtil.isDefaultLogin(new Login("root", ConfigUtil.DEFAULT_PASSWORD)));
    }

    @Test
    void changed_password_is_not_default() {
        assertFalse(ConfigUtil.isDefaultLogin(new Login(ConfigUtil.DEFAULT_USERNAME, "not-the-default")));
    }

    @Test
    void null_login_is_not_default() {
        assertFalse(ConfigUtil.isDefaultLogin(null));
    }

    @Test
    void response_flag_does_not_affect_detection() {
        Login login = new Login(ConfigUtil.DEFAULT_USERNAME, "not-the-default");
        login.setIsDefault(true);
        assertFalse(ConfigUtil.isDefaultLogin(login));
    }
}

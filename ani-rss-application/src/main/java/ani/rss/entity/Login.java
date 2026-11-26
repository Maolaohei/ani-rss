package ani.rss.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 登录
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
@Schema(description = "登录")
public class Login implements Serializable {
    /**
     * 用户名
     */
    @Schema(description = "用户名")
    private String username;
    /**
     * 密码
     */
    @Schema(description = "密码 (SHA-256摘要)")
    private String password;

    /**
     * 是否仍为默认登录信息
     */
    @Schema(description = "是否仍为默认登录信息", hidden = true)
    private Boolean isDefault;

    public Login(String username, String password) {
        this.username = username;
        this.password = password;
        this.isDefault = false;
    }

    /**
     * ip
     */
    @Schema(description = "ip", hidden = true)
    private String ip;
    /**
     * key
     */
    @Schema(description = "key", hidden = true)
    private String key;
}

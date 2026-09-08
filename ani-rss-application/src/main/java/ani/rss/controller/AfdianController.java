package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.Config;
import ani.rss.entity.web.Result;
import ani.rss.util.other.AfdianUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.date.DateUtil;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;

@Hidden
@RestController
public class AfdianController extends BaseController {

    @Auth
    @PostMapping("/verifyNo")
    public Result<Void> verifyNo(@RequestBody Config config) {
        String outTradeNo = config.getOutTradeNo();
        Result<Void> result = AfdianUtil.verifyNo(outTradeNo);

        int code = result.getCode();
        if (code == 200) {
            Long time = DateUtil.offsetYear(new Date(), 999).getTime();
            // 锁内快照合并+原子交换, 避免直接写 CONFIG 字段被并发交换丢弃
            ConfigUtil.updateAfdianInfo(outTradeNo, time, false);
        }

        return result;
    }
}

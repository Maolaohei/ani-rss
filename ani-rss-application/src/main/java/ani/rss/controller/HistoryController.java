package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.web.Result;
import ani.rss.util.other.DownloadHistory;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载历史 / 活动时间线。
 * <p>
 * 失败侧此前已有失败队列，成功侧完全不留痕：用户无法回答
 * "上周到底下了哪些""这周一共收了多少集""这集为什么没下"。
 * 本控制器把 {@link DownloadHistory} 的持久化记录透出给前端。
 */
@Slf4j
@RestController
public class HistoryController extends BaseController {

    /**
     * 单次返回上限
     */
    private static final int MAX_LIMIT = 500;

    public static class HistoryQuery {
        private String aniId;
        private String result;
        private Integer days;
        private Integer limit;

        public String getAniId() {
            return aniId;
        }

        public void setAniId(String aniId) {
            this.aniId = aniId;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public Integer getDays() {
            return days;
        }

        public void setDays(Integer days) {
            this.days = days;
        }

        public Integer getLimit() {
            return limit;
        }

        public void setLimit(Integer limit) {
            this.limit = limit;
        }
    }

    @Auth
    @Operation(summary = "下载历史列表")
    @PostMapping("/downloadHistory")
    public Result<List<DownloadHistory.DownloadRecord>> downloadHistory(@RequestBody(required = false) HistoryQuery query) {
        String aniId = query == null ? null : StrUtil.trimToNull(query.getAniId());
        String result = query == null ? null : StrUtil.trimToNull(query.getResult());
        int days = query == null || query.getDays() == null ? 30 : Math.max(0, Math.min(query.getDays(), 365));
        int limit = query == null || query.getLimit() == null ? 200 : Math.max(1, Math.min(query.getLimit(), MAX_LIMIT));
        Long since = days <= 0 ? null : DateUtil.offsetDay(new Date(), -days).getTime();
        return Result.success(DownloadHistory.query(aniId, result, since, limit));
    }

    @Auth
    @Operation(summary = "下载历史统计")
    @PostMapping("/downloadHistoryStats")
    public Result<Map<String, Object>> downloadHistoryStats(@RequestBody(required = false) HistoryQuery query) {
        int days = query == null || query.getDays() == null ? 7 : Math.max(0, Math.min(query.getDays(), 365));
        DownloadHistory.Summary summary = DownloadHistory.summary(days);
        List<DownloadHistory.DayStat> dayStats = DownloadHistory.dayStats(days <= 0 ? 7 : days);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", summary);
        data.put("days", dayStats);
        return Result.success(data);
    }

    @Auth
    @Operation(summary = "移除下载历史条目")
    @PostMapping("/downloadHistoryRemove")
    public Result<Void> downloadHistoryRemove(@RequestBody Map<String, Object> body) {
        String id = body == null || body.get("id") == null ? null : String.valueOf(body.get("id"));
        if (StrUtil.isBlank(id)) {
            return Result.error("id 不能为空");
        }
        boolean ok = DownloadHistory.remove(id);
        return ok ? Result.success("已移除") : Result.error("条目不存在");
    }

    @Auth
    @Operation(summary = "清空下载历史")
    @PostMapping("/downloadHistoryClear")
    public Result<Void> downloadHistoryClear() {
        int n = DownloadHistory.clear();
        return Result.success("已清空 {} 条", n);
    }
}

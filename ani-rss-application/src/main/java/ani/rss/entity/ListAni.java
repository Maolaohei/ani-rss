package ani.rss.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

@Data
@Accessors(chain = true)
public class ListAni implements Serializable {

    private List<String> releaseDateList;

    /**
     * 现有订阅中出现过的分组名（去重、排序），供前端筛选下拉直接使用
     */
    private List<String> groupList;

    private List<WeekAni> weekList;

    private Integer total;

    @Data
    @Accessors(chain = true)
    @AllArgsConstructor
    @NoArgsConstructor
    public static class WeekAni implements Serializable {
        private String weekLabel;
        private List<Ani> items;
    }
}

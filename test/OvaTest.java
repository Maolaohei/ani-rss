import java.util.regex.*;

/**
 * 复现 "我们真的学不来 OVA" 的解析问题
 * 日志中的标题格式: [喵萌奶茶屋]★10月新番★[我们真的学不来!][OVA][720p][繁体]
 */
public class OvaTest {
    static final String REG_STR = "(.*|\\[.*])(( - |Vol |[Ee][Pp]?)\\d+(\\.5)?( ?\\(\\d+\\))?|【\\d+(\\.5)?】|\\[\\d+(\\.5)?( ?\\(\\d+\\))?( ?[vV]\\d)?( ?END)?( ?完)?( ?FIN)?]|第\\d+(\\.5)?[话話集]( - END)?|^\\[TOC].* \\d+|^六四位元字幕组.*★\\d+(\\.5)?★)";

    public static void main(String[] args) {
        String[] titles = {
            "[喵萌奶茶屋]★10月新番★[我们真的学不来!/我们无法一起学习!/Bokutachi wa Benkyou ga Dekinai][OVA][720p][繁体][招募翻译校对]",
            "[喵萌奶茶屋]★10月新番★[我们真的学不来!/我们无法一起学习!/Bokutachi wa Benkyou ga Dekinai][OVA][720p][简体][招募翻译校对]",
            "[喵萌奶茶屋]★4月新番★[我们真的学不来!/我们无法一起学习!/Bokutachi wa Benkyou ga Dekinai][OVA][1080p][繁体][招募翻译校对]",
            "[喵萌奶茶屋]★10月新番★[我们真的学不来!/我们无法一起学习!/Bokutachi wa Benkyou ga Dekinai][OVA][1080p][简体][招募翻译校对]",
        };

        System.out.println("=== OVA 标题解析测试 ===\n");

        for (String title : titles) {
            System.out.println("标题: " + title);

            Matcher m = Pattern.compile(REG_STR).matcher(title);
            if (m.find()) {
                String group2 = m.group(2);
                System.out.println("  REG_STR 匹配: " + group2);
            } else {
                System.out.println("  REG_STR 未匹配");
            }

            // 模拟 OVA 处理逻辑
            boolean isOva = title.contains("[OVA]");
            System.out.println("  isOVA: " + isOva);

            if (isOva) {
                // OVA 不解析集数，直接使用标题
                String cleaned = title.replaceAll("\\[.*?\\]", " ").replaceAll("★.*?★", " ").replaceAll("\\s+", " ").trim();
                System.out.println("  清理后标题: " + cleaned);
                System.out.println("  命名结果: " + cleaned + " S00.E01");
            }

            System.out.println();
        }
    }
}

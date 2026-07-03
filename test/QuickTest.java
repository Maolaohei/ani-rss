import java.util.regex.*;

public class QuickTest {
    public static void main(String[] args) {
        String REG_STR = "(.*|\\[.*])(( - |Vol |[Ee][Pp]?)\\d+(\\.5)?( ?\\(\\d+\\))?|【\\d+(\\.5)?】|\\[\\d+(\\.5)?( ?\\(\\d+\\))?( ?[vV]\\d)?( ?END)?( ?完)?( ?FIN)?]|第\\d+(\\.5)?[话話集]( - END)?|^\\[TOC].* \\d+|^六四位元字幕组.*★\\d+(\\.5)?★)";

        String[] testCases = {
            "动漫名 第1集",
            "动漫名 第12集",
            "动漫名 第1话",
            "动漫名 第12话",
            "动漫名 EP01",
            "动漫名 - 01",
            "[字幕组] 动漫名 - 01 [1080p].mkv",
            "[字幕组] 动漫名 EP01 [1080p].mkv",
            "[字幕组] 动漫名 第1集 [1080p].mkv",
        };

        String renameTemplate = "[${subgroup}] ${title} S${seasonFormat}.E${episodeFormat}";
        int season = 1;

        for (String title : testCases) {
            Matcher m = Pattern.compile(REG_STR).matcher(title);
            if (m.find()) {
                String group2 = m.group(2);
                String ep = Pattern.compile("\\d+(\\.5)?").matcher(group2).group();
                int epNum = Integer.parseInt(ep);
                String seasonFmt = String.format("%02d", season);
                String epFmt = String.format("%02d", epNum);
                String result = renameTemplate
                    .replace("${subgroup}", "字幕组")
                    .replace("${title}", "动漫名")
                    .replace("${seasonFormat}", seasonFmt)
                    .replace("${episodeFormat}", epFmt);
                System.out.printf("%-35s => group2=%-10s => %s%n", title, group2, result);
            } else {
                System.out.printf("%-35s => NO MATCH%n", title);
            }
        }
    }
}

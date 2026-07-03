import java.util.*;
import java.util.regex.*;

public class FinalTest {
    static final String REG_STR = "(.*|\\[.*])(( - |Vol |[Ee][Pp]?)\\d+(\\.5)?( ?\\(\\d+\\))?|【\\d+(\\.5)?】|\\[\\d+(\\.5)?( ?\\(\\d+\\))?( ?[vV]\\d)?( ?END)?( ?完)?( ?FIN)?]|第\\d+(\\.5)?[话話集]( - END)?|^\\[TOC].* \\d+|^六四位元字幕组.*★\\d+(\\.5)?★)";
    static final String SEASON_REG = "[Ss](\\d+)[.\\s]?[Ee](\\d+(\\.5)?)";
    static final String EP_RANGE = "(\\d+(?:\\.5)?)[\\s]*[-~～][\\s]*(\\d+(?:\\.5)?)";
    static final String PART = "(上篇|下篇|前篇|後篇|前编|后编|前編|後編|第一部|第二部|第三部|Part\\s?[1-9](?![0-9])|P[1-9](?![0-9])|上(?=\\s|\\[|$)|下(?=\\s|\\[|$))";
    static final String FRAC = "\\((\\d+)/(\\d+)\\)";

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== FINAL TEST ===\n");

        // T1
        System.out.println("--- T1: SEASON_REG ---");
        eq(true, seMatch("S01E01"));
        eq(true, seMatch("S00.E01"));
        eq(true, seMatch("s01e01"));
        eq(true, seMatch("s00.e01"));
        eq(true, seMatch("S01 E01"));
        eq(true, seMatch("S1E1"));
        eq(false, seMatch("Title"));

        // T2
        System.out.println("\n--- T2: Range ---");
        eq("01-06", range("01-06"));
        eq("01-06", range("01~06"));
        eq("01-12", range("01～12"));
        eq("01-06", range("Vol.01-Vol.06"));
        eq("1-6", range("第1-6话"));
        eq("1-12", range("第1～12集"));
        eq(null, range("01 [1080p]"));

        // T3
        System.out.println("\n--- T3: Part ---");
        eq(1, part("上篇"));
        eq(2, part("下篇"));
        eq(1, part("前篇"));
        eq(2, part("後篇"));
        eq(1, part("Part 1"));
        eq(2, part("Part 2"));
        eq(1, part("Part1"));
        eq(1, part("P1"));
        eq(2, part("P2"));
        eq(1, part("(1/2)"));
        eq(2, part("(2/3)"));
        eq(1, part("上"));
        eq(2, part("下"));
        eq(1, part("第一部"));
        eq(2, part("第二部"));

        // T4
        System.out.println("\n--- T4: FP ---");
        eq(true, fp("[SubGroup] 动漫名 - 2024 [1080p].mkv"));
        eq(true, fp("[SubGroup] 动漫名 - 10周年 [1080p].mkv"));
        eq(true, fp("[SubGroup] 动漫名 - 10bit [1080p].mkv"));
        eq(false, fp("[SubGroup] 动漫名 - 01 [1080p].mkv"));
        eq(false, fp("[SubGroup] 动漫名 - 12 [1080p].mkv"));

        // T5
        System.out.println("\n--- T5: Quality ---");
        eq("1080p", qbest("720p", 1000, "1080p", 800));
        eq("2160p", qbest("1080p", 800, "2160p", 600));
        eq("720p", qbest("720p", 1000, "720p", 500));
        eq("big", qbest("", 500, "", 1000));
        eq("1080p", qbest("1080p", 800, "", 2000));

        // T6
        System.out.println("\n--- T6: Flow ---");
        eq("ep=1", flow("[SubGroup] 葬送的芙莉莲 - 01 [1080p].mkv"));
        eq("range=01~06", flow("[SubGroup] 葬送的芙莉莲 01-06 [1080p]"));
        eq("part=1", flow("[SubGroup] 鬼灭之刃 上篇 [1080p]"));
        eq("frac=1/2", flow("[SubGroup] 名侦探柯南 (1/2) [1080p]"));
        eq("ep=1", flow("[SubGroup] 夏目友人帐 BD01 [1080p].mkv"));
        eq("ep=1", flow("[SubGroup] 夏目友人帐 #01.mkv"));
        eq("ep=1", flow("[SubGroup] 夏目友人帐 Vol01 [1080p].mkv"));

        System.out.println("\n=== RESULT ===");
        System.out.printf("Total: %d | Pass: %d (%.1f%%) | Fail: %d%n",
                pass + fail, pass, pass * 100.0 / (pass + fail), fail);
    }

    static boolean seMatch(String s) { return Pattern.compile(SEASON_REG).matcher(s).find(); }

    static String range(String t) {
        String s = t.replaceAll("(?i)Vol\\.?\\s*", "");
        Matcher m = Pattern.compile(EP_RANGE).matcher(s);
        return m.find() ? m.group(1) + "-" + m.group(2) : null;
    }

    static int part(String t) {
        Matcher m = Pattern.compile(PART).matcher(t);
        if (m.find()) return mapPart(m.group(1));
        Matcher fm = Pattern.compile(FRAC).matcher(t);
        if (fm.find()) return Integer.parseInt(fm.group(1));
        return 0;
    }

    static boolean fp(String title) {
        Matcher m = Pattern.compile(REG_STR).matcher(title);
        if (!m.find()) return false;
        String e = m.group(2);
        if (e == null) return false;
        return filterFP(e, title) == null;
    }

    static String filterFP(String ep, String full) {
        String n = num(ep);
        if (n == null) return ep;
        if (n.length() == 4 && !n.contains(".")) {
            try { int v = Integer.parseInt(n); if (v >= 1900 && v <= 2100) return null; } catch (Exception ignored) {}
        }
        int idx = full.indexOf(ep);
        if (idx >= 0) {
            String after = full.substring(idx + ep.length());
            if (after.matches("^\\s*周.*") || after.matches("^\\s*年.*") || after.matches("^\\s*bit.*")) return null;
        }
        return ep;
    }

    static String qbest(String q1, long s1, String q2, long s2) {
        int p1 = q(q1), p2 = q(q2);
        if (p1 != p2) return p1 > p2 ? q1 : q2;
        return s1 >= s2 ? (q1.isEmpty() ? "big" : q1) : (q2.isEmpty() ? "big" : q2);
    }

    static int q(String q) {
        if (q.contains("2160")) return 40;
        if (q.contains("1080")) return 30;
        if (q.contains("720")) return 20;
        return 0;
    }

    static String flow(String title) {
        Matcher m = Pattern.compile(REG_STR).matcher(title);
        if (m.find()) { String e = filterFP(m.group(2), title); if (e != null) { String n = num(e); if (n != null) return "ep=" + n; } }
        // REG_LOOSE 回退
        String loose = title.replaceAll("(?i)Vol\\.?\\s*", "");
        Matcher lm = Pattern.compile("([Ee][Pp]?\\s*\\d+|BD[- ]?\\d+|#\\d+|_\\d+|★\\d+★)").matcher(loose);
        if (lm.find()) { String n = num(lm.group()); if (n != null) return "ep=" + n; }
        Matcher rm = Pattern.compile(EP_RANGE).matcher(loose);
        if (rm.find()) return "range=" + rm.group(1) + "~" + rm.group(2);
        Matcher pm = Pattern.compile(PART).matcher(title);
        if (pm.find()) return "part=" + mapPart(pm.group(1));
        Matcher fm = Pattern.compile(FRAC).matcher(title);
        if (fm.find()) return "frac=" + fm.group(1) + "/" + fm.group(2);
        return "none";
    }

    static int mapPart(String p) {
        return switch (p) {
            case "上篇","前篇","前编","前編","第一部","Part 1","Part1","P1","上" -> 1;
            case "下篇","後篇","后编","後編","第二部","Part 2","Part2","P2","下" -> 2;
            default -> 0;
        };
    }

    static String num(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("\\d+(?:\\.5)?").matcher(s);
        return m.find() ? m.group() : null;
    }

    static void eq(Object actual, Object expected) {
        boolean ok = Objects.equals(actual, expected);
        if (ok) pass++; else fail++;
        System.out.printf("  [%s] actual=%s expected=%s%n", ok ? "PASS" : "FAIL", actual, expected);
    }
}

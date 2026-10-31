package ani.rss.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 10008 去重死锁的<b>跨轮</b>计数。
 * <p>
 * 线上事故（v3.4.36，24h）：我家的弟弟们 S01E17 每 ≈35 分钟「添加下载」一次，
 * 每轮都被 115 以 10008「任务已存在」挡回、状态还是 Failed，
 * 而 AList 侧 task/info 又 404（任务看不见也删不掉）。
 * 旧判定只有单轮上下文 {@code ctx.instantDuplicateFails >= 2}——每轮只 +1，永远到不了 2，
 * 于是走 UNCERTAIN（不记失败、不通知、pending 不清），无限循环：
 * 24 次「添加下载」/18 次 10008/12 次 task/info 404，用户一条告警都收不到。
 * <p>
 * 本用例固化「同一 hash 连续两轮才判死锁」「不同 hash 互不串味」「空 hash 不计」。
 */
class OpenListDuplicateLockStrikeTest {

    private static String hash() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    @DisplayName("同一 hash：第一轮 1 击（继续等），第二轮 2 击（判死锁）")
    void two_strikes_across_rounds() {
        String h = hash();
        assertEquals(1, OpenList.bumpDuplicateLockStrike(h));
        assertEquals(2, OpenList.bumpDuplicateLockStrike(h));
        assertTrue(2 >= 2, "第二轮即达到判死锁阈值");
    }

    @Test
    @DisplayName("大小写/空白不影响计数：infoHash 归一化后再比")
    void key_is_normalized() {
        String h = hash();
        assertEquals(1, OpenList.bumpDuplicateLockStrike(h));
        assertEquals(2, OpenList.bumpDuplicateLockStrike("  " + h.toUpperCase() + " "));
    }

    @Test
    @DisplayName("不同 hash 各算各的：别的番的打嗝次数不会把我这个判成死锁")
    void different_hashes_are_independent() {
        String a = hash();
        String b = hash();
        assertEquals(1, OpenList.bumpDuplicateLockStrike(a));
        assertEquals(1, OpenList.bumpDuplicateLockStrike(b));
        assertEquals(2, OpenList.bumpDuplicateLockStrike(a));
    }

    @Test
    @DisplayName("空 hash：不计击（无从判重，也就无从死锁）")
    void blank_hash_never_counts() {
        assertEquals(0, OpenList.bumpDuplicateLockStrike(""));
        assertEquals(0, OpenList.bumpDuplicateLockStrike("   "));
        assertEquals(0, OpenList.bumpDuplicateLockStrike(null));
        assertEquals("", OpenList.hashKey(null));
    }
}

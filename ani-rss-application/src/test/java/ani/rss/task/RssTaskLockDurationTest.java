package ani.rss.task;

import ani.rss.entity.Config;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RssTaskLockDurationTest {

    @Test
    void lock_duration_follows_offline_timeout_with_buffer() {
        Config config = new Config().setAlistDownloadTimeout(60);
        long ms = RssTask.resolveMaxDownloadDurationMs(config);
        // max(60+10, 90) = 90
        assertEquals(TimeUnit.MINUTES.toMillis(90), ms);

        config.setAlistDownloadTimeout(120);
        ms = RssTask.resolveMaxDownloadDurationMs(config);
        // 120+10 = 130
        assertEquals(TimeUnit.MINUTES.toMillis(130), ms);
    }

    @Test
    void lock_duration_null_config_uses_fallback() {
        long ms = RssTask.resolveMaxDownloadDurationMs(null);
        assertEquals(TimeUnit.MINUTES.toMillis(90), ms);
    }
}
package com.gathin.tvhome;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;

public class CountdownTextTest {
    private static final long HOUR = 60L * 60 * 1000;
    private static final long DAY = 24 * HOUR;
    private static final LocalDate DAY_ONE = LocalDate.of(2027, 1, 15);
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Noon local on DAY_ONE: every assertion below stays on its intended calendar day in any TZ. */
    private static long at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(ZONE).toInstant().toEpochMilli();
    }

    private static final long NOW = at(DAY_ONE, 12);

    @Test public void mediaSameDayIsToday() {
        assertEquals("today", CountdownText.pill(at(DAY_ONE, 18), NOW, false));
        assertEquals("today", CountdownText.pill(at(DAY_ONE, 0), NOW, false)); // midnight release counts
    }

    @Test public void mediaNextDayIsTomorrow() {
        assertEquals("tomorrow", CountdownText.pill(at(DAY_ONE.plusDays(1), 9), NOW, false));
    }

    @Test public void mediaFurtherOutIsCeilingDays() {
        assertEquals("in 3d", CountdownText.pill(at(DAY_ONE.plusDays(3), 9), NOW, false));
        assertEquals("in 2d", CountdownText.pill(at(DAY_ONE.plusDays(2), 23), NOW, false));
    }

    @Test public void mediaPastDateAndNoAnchorAreEmpty() {
        assertEquals("", CountdownText.pill(at(DAY_ONE.minusDays(1), 18), NOW, false));
        assertEquals("", CountdownText.pill(0, NOW, false));
    }

    @Test public void sportsWithinADayIsCeilingHours() {
        assertEquals("in 5h", CountdownText.pill(NOW + 5 * HOUR, NOW, true));
        assertEquals("in 6h", CountdownText.pill(NOW + 5 * HOUR + 1, NOW, true));
        assertEquals("in 24h", CountdownText.pill(NOW + 24 * HOUR, NOW, true));
    }

    @Test public void sportsBeyondADayIsCeilingDays() {
        assertEquals("in 2d", CountdownText.pill(NOW + DAY + HOUR, NOW, true));
        assertEquals("in 3d", CountdownText.pill(NOW + 3 * DAY, NOW, true));
    }

    @Test public void sportsFinalLiveAndUnanchoredAreEmpty() {
        assertEquals("", CountdownText.pill(NOW - 4 * HOUR, NOW, true)); // long past: FINAL's job
        assertEquals("", CountdownText.pill(NOW - HOUR, NOW, true));     // live: the state says it
        assertEquals("", CountdownText.pill(0, NOW, true));
    }
}

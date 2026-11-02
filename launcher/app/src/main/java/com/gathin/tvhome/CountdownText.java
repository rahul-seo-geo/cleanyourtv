package com.gathin.tvhome;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * The countdown pill on a dashboard card. Pure: dynamic strings are built in code the way
 * StatsText builds its measurements; only the static labels (today/tomorrow/in) live in strings.xml.
 */
final class CountdownText {
    private static final long HOUR_MS = 60L * 60 * 1000;
    private static final long DAY_MS = 24 * HOUR_MS;

    private CountdownText() { }

    /**
     * Countdown wording for an anchored card; "" when there is nothing useful to say. Sports: a
     * kickoff more than three hours past is over (FINAL shows its own state) and a live one says
     * nothing; within a day "in 5h", beyond it "in 3d", both ceilings. Media: calendar days —
     * "today", "tomorrow", else "in 3d"; a date already past or no anchor at all is "".
     */
    static String pill(long whenMs, long nowMs, boolean sports) {
        if (sports) {
            if (whenMs <= 0 || whenMs <= nowMs - 3 * HOUR_MS || whenMs <= nowMs) return "";
            long diff = whenMs - nowMs;
            if (diff <= DAY_MS) return "in " + (diff + HOUR_MS - 1) / HOUR_MS + "h";
            return "in " + (diff + DAY_MS - 1) / DAY_MS + "d";
        }
        if (whenMs <= 0) return "";
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone);
        LocalDate thatDay = LocalDate.ofInstant(Instant.ofEpochMilli(whenMs), zone);
        long days = ChronoUnit.DAYS.between(today, thatDay);
        if (days < 0) return "";
        if (days == 0) return "today";
        if (days == 1) return "tomorrow";
        return "in " + days + "d";
    }
}

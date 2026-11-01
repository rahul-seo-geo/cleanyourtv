package com.gathin.news;

import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public class DashboardFormatTest {
    private static final long MINUTE = 60000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long NOW = 1_800_000_000_000L;

    @Test public void ageLabelCoversBoundaries() {
        assertEquals("", DashboardFormat.ageLabel(NOW, 0));
        assertEquals("", DashboardFormat.ageLabel(NOW, NOW + MINUTE));
        assertEquals("just now", DashboardFormat.ageLabel(NOW, NOW));
        assertEquals("5 min ago", DashboardFormat.ageLabel(NOW, NOW - 5 * MINUTE));
        assertEquals("3 h ago", DashboardFormat.ageLabel(NOW, NOW - 3 * HOUR));
        assertEquals("2 d ago", DashboardFormat.ageLabel(NOW, NOW - 2 * DAY));
    }

    @Test public void releaseLabelUsesLocale() {
        Locale locale = Locale.UK;
        assertEquals("", DashboardFormat.releaseLabel(0, locale));
        long release = NOW;
        String label = DashboardFormat.releaseLabel(release, locale);
        assertTrue(label, label.startsWith("releases "));
        assertTrue(label, label.matches("releases \\d{1,2} [A-Za-z]{3} \\d{4}"));
        assertEquals("cached · " + label, DashboardFormat.mediaMeta(release, true, locale));
        assertEquals("", DashboardFormat.mediaMeta(0, false, locale));
        assertEquals("cached", DashboardFormat.mediaMeta(0, true, locale));
    }

    @Test public void storyMetaMarksCachedBeforeAge() {
        assertEquals("ABC News · 5 min ago",
                DashboardFormat.storyMeta("ABC News", false, NOW, NOW - 5 * MINUTE));
        assertEquals("cached · ABC News · 3 h ago",
                DashboardFormat.storyMeta("ABC News", true, NOW, NOW - 3 * HOUR));
        assertEquals("ABC News", DashboardFormat.storyMeta("ABC News", false, NOW, 0));
        assertEquals("cached · ABC News", DashboardFormat.storyMeta("ABC News", true, NOW, 0));
        assertEquals("", DashboardFormat.storyMeta(null, false, NOW, 0));
        assertEquals("cached", DashboardFormat.storyMeta("  ", true, NOW, 0));
    }

    @Test public void sourceLabelsMapKnownKeys() {
        assertEquals("Feed 1", DashboardFormat.sourceLabel("feed1"));
        assertEquals("Feed 12", DashboardFormat.sourceLabel("feed12"));
        assertEquals("Film", DashboardFormat.sourceLabel("upcoming-movies"));
        assertEquals("Series", DashboardFormat.sourceLabel("upcoming-tv"));
        assertEquals("Matches", DashboardFormat.sourceLabel("game_day"));
        assertEquals("Weird Key", DashboardFormat.sourceLabel("weird_key"));
        assertEquals("", DashboardFormat.sourceLabel(null));
    }

    @Test public void chipStatesStayShort() {
        DashboardSnapshot.Freshness fresh = DashboardSnapshot.Freshness.FRESH;
        DashboardSnapshot.Freshness stale = DashboardSnapshot.Freshness.STALE;
        DashboardSnapshot.Freshness offline = DashboardSnapshot.Freshness.UNAVAILABLE;
        assertEquals("fresh", DashboardFormat.chipState(fresh, NOW, NOW));
        assertEquals("cached now", DashboardFormat.chipState(stale, NOW, NOW - 20_000));
        assertEquals("cached 5m", DashboardFormat.chipState(stale, NOW, NOW - 5 * MINUTE));
        assertEquals("cached 3h", DashboardFormat.chipState(stale, NOW, NOW - 3 * HOUR));
        assertEquals("cached 2d", DashboardFormat.chipState(stale, NOW, NOW - 2 * DAY));
        assertEquals("cached", DashboardFormat.chipState(stale, NOW, 0));
        assertEquals("offline", DashboardFormat.chipState(offline, NOW, NOW));
        assertEquals("Feed 1 · fresh", DashboardFormat.chipLabel("feed1", fresh, NOW, NOW));
        assertEquals("Matches · offline", DashboardFormat.chipLabel("game_day", offline, NOW, 0));
    }

    @Test public void fixtureStatusDistinguishesPhases() {
        long start = NOW + 12 * MINUTE;
        long end = start + 2 * HOUR;
        DashboardSnapshot.MatchSlot live = new DashboardSnapshot.MatchSlot("k", "A v B",
                DashboardSnapshot.MatchPhase.LIVE, "2-1", start, end, 0xFFFFFFFF,
                DashboardSnapshot.Freshness.FRESH);
        DashboardSnapshot.MatchSlot liveNoScore = new DashboardSnapshot.MatchSlot("k", "A v B",
                DashboardSnapshot.MatchPhase.LIVE, null, start, end, 0xFFFFFFFF,
                DashboardSnapshot.Freshness.FRESH);
        DashboardSnapshot.MatchSlot upcoming = new DashboardSnapshot.MatchSlot("k", "A v B",
                DashboardSnapshot.MatchPhase.UPCOMING, null, NOW + 12 * MINUTE, end, 0xFFFFFFFF,
                DashboardSnapshot.Freshness.FRESH);
        DashboardSnapshot.MatchSlot upcomingHours = new DashboardSnapshot.MatchSlot("k", "A v B",
                DashboardSnapshot.MatchPhase.UPCOMING, null, NOW + 200 * MINUTE, end, 0xFFFFFFFF,
                DashboardSnapshot.Freshness.FRESH);
        DashboardSnapshot.MatchSlot upcomingDays = new DashboardSnapshot.MatchSlot("k", "A v B",
                DashboardSnapshot.MatchPhase.UPCOMING, null, NOW + 53 * HOUR, end, 0xFFFFFFFF,
                DashboardSnapshot.Freshness.FRESH);
        DashboardSnapshot.MatchSlot upcomingStale = new DashboardSnapshot.MatchSlot("k", "A v B",
                DashboardSnapshot.MatchPhase.UPCOMING, null, NOW + 200 * MINUTE, end, 0xFFFFFFFF,
                DashboardSnapshot.Freshness.STALE);
        DashboardSnapshot.MatchSlot finalWithScore = new DashboardSnapshot.MatchSlot("k", "A v B",
                DashboardSnapshot.MatchPhase.FINAL, "2-1", start, end, 0xFFFFFFFF,
                DashboardSnapshot.Freshness.FRESH);
        DashboardSnapshot.MatchSlot finalNoScore = new DashboardSnapshot.MatchSlot("k", "A v B",
                DashboardSnapshot.MatchPhase.FINAL, null, start, end, 0xFFFFFFFF,
                DashboardSnapshot.Freshness.FRESH);
        DashboardSnapshot.MatchSlot unknown = new DashboardSnapshot.MatchSlot("k", "A v B",
                DashboardSnapshot.MatchPhase.UNAVAILABLE, null, start, end, 0xFFFFFFFF,
                DashboardSnapshot.Freshness.STALE);
        assertEquals("2-1", DashboardFormat.fixtureStatus(live, NOW));
        assertEquals("in play", DashboardFormat.fixtureStatus(liveNoScore, NOW));
        assertEquals("in 12m", DashboardFormat.fixtureStatus(upcoming, NOW));
        assertEquals("in 3h 20m", DashboardFormat.fixtureStatus(upcomingHours, NOW));
        assertEquals("in 2d", DashboardFormat.fixtureStatus(upcomingDays, NOW));
        assertEquals("in 3h 20m · cached", DashboardFormat.fixtureStatus(upcomingStale, NOW));
        assertEquals("FT 2-1", DashboardFormat.fixtureStatus(finalWithScore, NOW));
        assertEquals("full time", DashboardFormat.fixtureStatus(finalNoScore, NOW));
        assertEquals("score unknown · cached", DashboardFormat.fixtureStatus(unknown, NOW));
    }

    @Test public void countdownNeverInventsNegativeTime() {
        assertEquals("soon", DashboardFormat.countdownLabel(0, NOW));
        assertEquals("soon", DashboardFormat.countdownLabel(NOW, NOW));
        assertEquals("soon", DashboardFormat.countdownLabel(NOW - MINUTE, NOW));
    }

    @Test public void matchStripLabelSeparatesEmptyFromUnavailable() {
        assertEquals("Match data unavailable", DashboardFormat.matchStripLabel(true));
        assertEquals("No matches scheduled today", DashboardFormat.matchStripLabel(false));
    }

    @Test public void truncateCutsOnWordBoundaries() {
        assertEquals("", DashboardFormat.truncate(null, 10));
        assertEquals("Short title", DashboardFormat.truncate("  Short   title ", 20));
        assertEquals("Long headline that…",
                DashboardFormat.truncate("Long headline that runs past the limit", 22));
        assertEquals("Supercalifragilis…",
                DashboardFormat.truncate("Supercalifragilisticexpialidocious", 17));
    }
}

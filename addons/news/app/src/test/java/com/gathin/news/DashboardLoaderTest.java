package com.gathin.news;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class DashboardLoaderTest {
    private static final long NOW = 1_800_000_000_000L;
    private final Map<String, byte[]> responses = new HashMap<>();
    private final Map<String, byte[]> cached = new HashMap<>();
    private final Map<String, Long> ages = new HashMap<>();
    private GameDayReader.GameDayResult games = new GameDayReader.GameDayResult(
            GameDayReader.State.UNAVAILABLE, List.of(), 30);

    private DashboardLoader loader() {
        return new DashboardLoader((url, headers) -> responses.get(url), new DashboardLoader.Store() {
            @Override public byte[] read(String key) { return cached.get(key); }
            @Override public void put(String key, byte[] value) { cached.put(key, value); ages.put(key, 0L); }
            @Override public long ageSeconds(String key) { return ages.getOrDefault(key, -1L); }
        }, (pkg, nowMs) -> games);
    }

    private static byte[] rss(String title) {
        return ("<rss><channel><item><title>" + title + "</title><link>https://example.com/" + title
                + "</link><pubDate>Tue, 14 Nov 2023 22:13:20 GMT</pubDate></item></channel></rss>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] media(String title) {
        return ("{\"results\":[{\"title\":\"" + title + "\",\"releaseDate\":\"2027-01-01\"}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static DashboardSnapshot.StorySlot story(DashboardSnapshot snapshot, String title) {
        for (DashboardSnapshot.StorySlot slot : snapshot.stories)
            if (title.equals(slot.title)) return slot;
        fail("Missing story: " + title);
        return null;
    }

    @Test public void mixedSourcesRefreshIndependentlyAndFailedFeedRetainsCache() {
        responses.put("https://abc", rss("Fresh ABC"));
        cached.put("feed2", rss("Cached BBC"));
        ages.put("feed2", 120L);
        responses.put("https://seerr/api/v1/discover/movies/upcoming", media("Fresh movie"));
        Settings settings = Settings.parse("feed1=https://abc|ABC News\nfeed2=https://bbc|BBC News\n"
                + "jellyseerr_url=https://seerr/\njellyseerr_key=secret\n");
        DashboardSnapshot snapshot = loader().load(null, settings, NOW);
        assertFalse(story(snapshot, "Fresh ABC").cached);
        assertTrue(story(snapshot, "Cached BBC").cached);
        assertTrue(story(snapshot, "Cached BBC").stale);
        assertEquals(DashboardSnapshot.ContentKind.UPCOMING_MOVIE, story(snapshot, "Fresh movie").contentKind);
        assertEquals(DashboardSnapshot.Freshness.FRESH, snapshot.sources.get("feed1").freshness);
        assertEquals(DashboardSnapshot.Freshness.STALE, snapshot.sources.get("feed2").freshness);
        assertEquals(NOW - 120_000, snapshot.sources.get("feed2").updatedMs);
        assertEquals(DashboardSnapshot.Freshness.FRESH, snapshot.sources.get("upcoming-movies").freshness);
        assertEquals(DashboardSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("upcoming-tv").freshness);
        assertEquals(DashboardSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("game_day").freshness);
        assertTrue(snapshot.matches.isEmpty());
    }

    @Test public void subsequentSuccessReplacesStaleItemWithoutMovingOtherIdentity() {
        cached.put("feed1", rss("Old ABC"));
        ages.put("feed1", 3600L);
        responses.put("https://bbc", rss("BBC"));
        Settings settings = Settings.parse("feed1=https://abc|ABC News\nfeed2=https://bbc|BBC News");
        DashboardLoader loader = loader();
        DashboardSnapshot previous = loader.load(null, settings, NOW);
        String bbcSlot = story(previous, "BBC").slotId;
        responses.put("https://abc", rss("New ABC"));
        DashboardSnapshot next = loader.load(previous, settings, NOW + 1000);
        assertEquals(bbcSlot, story(next, "BBC").slotId);
        assertFalse(story(next, "New ABC").cached);
        assertEquals(DashboardSnapshot.Freshness.FRESH, next.sources.get("feed1").freshness);
        for (DashboardSnapshot.StorySlot slot : next.stories) assertNotEquals("Old ABC", slot.title);
    }

    @Test public void previousContentSurvivesOnlyItsOwnFailureAndLiveMatchIsNeverCachedAsLive() {
        responses.put("https://abc", rss("ABC"));
        responses.put("https://bbc", rss("BBC"));
        Settings settings = Settings.parse("feed1=https://abc|ABC News\nfeed2=https://bbc|BBC News\ngame_day=com.example.game");
        games = new GameDayReader.GameDayResult(GameDayReader.State.AVAILABLE,
                List.of(new DashboardSnapshot.MatchSlot("fixture", "Match", DashboardSnapshot.MatchPhase.LIVE,
                        "1-0", NOW - 1000, NOW + 100_000, 0xff000000, DashboardSnapshot.Freshness.FRESH)), 30);
        DashboardLoader loader = loader();
        DashboardSnapshot previous = loader.load(null, settings, NOW);
        responses.remove("https://abc");
        responses.put("https://bbc", rss("New BBC"));
        cached.remove("feed1");
        games = new GameDayReader.GameDayResult(GameDayReader.State.UNAVAILABLE, List.of(), 30);
        DashboardSnapshot next = loader.load(previous, settings, NOW + 1000);
        assertTrue(story(next, "ABC").stale);
        assertTrue(story(next, "ABC").cached);
        assertFalse(story(next, "New BBC").stale);
        assertEquals(DashboardSnapshot.Freshness.STALE, next.sources.get("feed1").freshness);
        assertEquals(DashboardSnapshot.Freshness.FRESH, next.sources.get("feed2").freshness);
        assertEquals(DashboardSnapshot.Freshness.STALE, next.sources.get("game_day").freshness);
        assertEquals(DashboardSnapshot.Freshness.STALE, next.matches.get(0).freshness);
        assertNotEquals(DashboardSnapshot.MatchPhase.LIVE, next.matches.get(0).phase);
        assertNull(next.matches.get(0).scoreOrCountdown);
    }

    @Test public void unconfiguredGameDayIsUnavailableWithoutReusingMatchData() {
        DashboardSnapshot snapshot = loader().load(null, Settings.parse("game_day="), NOW);
        assertEquals(DashboardSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("game_day").freshness);
        assertTrue(snapshot.matches.isEmpty());
    }
}

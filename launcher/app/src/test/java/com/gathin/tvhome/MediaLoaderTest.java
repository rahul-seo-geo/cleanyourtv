package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaLoaderTest {
    private static final long NOW = 1_800_000_000_000L;

    private final Map<String, byte[]> responses = new HashMap<>();
    private final Map<String, byte[]> stored = new HashMap<>();
    private final Map<String, Long> ages = new HashMap<>();
    private List<GameDay.Turn> turns = List.of();

    private MediaLoader loader() {
        return new MediaLoader((url, headers) -> responses.get(url), new MediaLoader.Store() {
            @Override public byte[] read(String key) { return stored.get(key); }
            @Override public void put(String key, byte[] value) { stored.put(key, value); ages.put(key, 0L); }
            @Override public long ageSeconds(String key) { return ages.getOrDefault(key, -1L); }
        }, () -> turns, () -> NOW);
    }

    private static MediaSettings settings() {
        return MediaSettings.parse(
                "seerr_url=https://seerr.example\nseerr_key=k\nm3u_url=http://nas/p.m3u\ngame_day_pkg=com.gathin.game");
    }

    private static byte[] json(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test public void healthySourcesProduceFreshSnapshotPerCategory() {
        responses.put("https://seerr.example/api/v1/request",
                json("[{\"id\":1,\"status\":3,\"media\":{\"mediaType\":\"movie\",\"tmdbId\":550,\"title\":\"Fight Club\"}}]"));
        responses.put("https://seerr.example/api/v1/discover/movies/upcoming",
                json("{\"results\":[{\"title\":\"Next Film\",\"release_date\":\"2030-01-01\"}]}"));
        responses.put("https://seerr.example/api/v1/discover/tv/upcoming", json("{\"results\":[]}"));
        responses.put("http://nas/p.m3u", json("#EXTM3U\n#EXTINF:-1 group-title=\"News\",ABC\nhttp://nas/abc.ts\n"));
        turns = List.of(new GameDay.Turn("AFL", "Puck drop", NOW - 1000, NOW + 1000, 0xFF112233));

        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals(2, snapshot.movies.size()); // request + upcoming movie
        assertEquals("Fight Club", snapshot.movies.get(0).title);
        assertEquals(1, snapshot.live.size());
        assertEquals(1, snapshot.sports.size());
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("seerr").freshness);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("playlist").freshness);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("sports").freshness);
    }

    @Test public void failedSeerrFallsBackToCacheMarkedStaleAndOthersStayFresh() {
        responses.put("http://nas/p.m3u", json("#EXTM3U\n#EXTINF:-1,ABC\nhttp://nas/abc.ts\n"));
        stored.put("seerr-request", json(
                "[{\"id\":1,\"status\":3,\"media\":{\"mediaType\":\"movie\",\"tmdbId\":550,\"title\":\"Old\"}]"));
        ages.put("seerr-request", 900L);
        stored.put("seerr-upcoming-movie", json("{\"results\":[]}"));
        stored.put("seerr-upcoming-series", json("{\"results\":[]}"));
        ages.put("seerr-upcoming-movie", 900L);
        ages.put("seerr-upcoming-series", 900L);

        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals(MediaSnapshot.Freshness.STALE, snapshot.sources.get("seerr").freshness);
        assertEquals("Old", snapshot.movies.get(0).title);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("playlist").freshness);
    }

    @Test public void unavailableSourcesAreMarkedNotBorrowed() {
        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals(MediaSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("seerr").freshness);
        assertEquals(MediaSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("playlist").freshness);
        assertEquals(MediaSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("sports").freshness);
        assertFalse(snapshot.anyContent());
    }

    @Test public void unconfiguredSourcesAreAbsentFromStatuses() {
        MediaSnapshot snapshot = loader().load(MediaSettings.parse(""), MediaSnapshot.empty());
        assertTrue(snapshot.sources.isEmpty());
    }

    @Test public void previousSnapshotHeldOnTotalFailureThenPartialRefreshKeepsIt() {
        responses.put("http://nas/p.m3u", json("#EXTM3U\n#EXTINF:-1,ABC\nhttp://nas/abc.ts\n"));
        MediaLoader unit = loader();
        MediaSnapshot first = unit.load(settings(), MediaSnapshot.empty());
        responses.clear();
        MediaSnapshot degraded = unit.load(settings(), first);
        assertEquals(1, degraded.live.size()); // last-known retained from previous snapshot
        assertEquals(MediaSnapshot.Freshness.STALE, degraded.sources.get("playlist").freshness);
    }
}

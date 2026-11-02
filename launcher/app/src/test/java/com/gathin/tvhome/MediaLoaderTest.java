package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private final List<String> fetchCalls = new ArrayList<>();
    private List<GameDay.Turn> turns = List.of();

    private MediaLoader loader() {
        return loader((url, headers) -> {
            fetchCalls.add(url);
            return responses.get(url);
        });
    }

    private MediaLoader loader(MediaLoader.Fetcher fetcher) {
        return new MediaLoader(fetcher, new MediaLoader.Store() {
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
        responses.put("https://seerr.example/api/v1/discover/movies/upcoming?limit=5",
                json("{\"results\":[{\"title\":\"Next Film\",\"release_date\":\"2030-01-01\"}]}"));
        responses.put("https://seerr.example/api/v1/discover/tv/upcoming?limit=5", json("{\"results\":[]}"));
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
                "[{\"id\":1,\"status\":3,\"media\":{\"mediaType\":\"movie\",\"tmdbId\":550,\"title\":\"Old\"}}]"));
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

    @Test public void emptyCachedSeerrResponsesAreUnavailable() {
        stored.put("seerr-request", json("[]"));
        stored.put("seerr-upcoming-movie", json("{\"results\":[]}"));
        stored.put("seerr-upcoming-series", json("{\"results\":[]}"));

        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals(MediaSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("seerr").freshness);
        assertTrue(snapshot.movies.isEmpty());
        assertTrue(snapshot.series.isEmpty());
    }

    @Test public void emptyCachedPlaylistIsUnavailable() {
        stored.put("playlist", json("#EXTM3U\n"));

        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals(MediaSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("playlist").freshness);
        assertTrue(snapshot.live.isEmpty());
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

    @Test public void requestDetailAddsArtWithoutAffectingFreshness() {
        responses.put("https://seerr.example/api/v1/request", json("[{\"status\":2,\"media\":{"
                + "\"mediaType\":\"movie\",\"tmdbId\":550,\"title\":\"Fight Club\"}}]"));
        responses.put("https://seerr.example/api/v1/movie/550", json("{\"posterPath\":\"/fight.jpg\"}"));
        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals("https://image.tmdb.org/t/p/w342/fight.jpg", snapshot.movies.get(0).artUrl);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("seerr").freshness);
        assertFalse(stored.containsKey("seerr-detail"));
    }

    @Test public void detailFailureKeepsTextCardAndSeerrFresh() {
        responses.put("https://seerr.example/api/v1/request", json("[{\"status\":2,\"media\":{"
                + "\"mediaType\":\"movie\",\"tmdbId\":550,\"title\":\"Fight Club\"}}]"));
        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals("", snapshot.movies.get(0).artUrl);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("seerr").freshness);
    }

    @Test public void throwingDetailFetcherKeepsOriginalCardAndFreshness() {
        responses.put("https://seerr.example/api/v1/request", json("[{\"status\":2,\"media\":{"
                + "\"mediaType\":\"movie\",\"tmdbId\":550,\"title\":\"Fight Club\"}}]"));
        MediaLoader unit = loader((url, headers) -> {
            if (url.endsWith("/api/v1/movie/550")) throw new IllegalStateException("detail unavailable");
            return responses.get(url);
        });
        MediaSnapshot snapshot = unit.load(settings(), MediaSnapshot.empty());
        assertEquals("Fight Club", snapshot.movies.get(0).title);
        assertEquals("", snapshot.movies.get(0).artUrl);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("seerr").freshness);
    }

    @Test public void detailFetchesBoundedAtEight() {
        StringBuilder requests = new StringBuilder("[");
        for (int i = 1; i <= 10; i++) {
            if (i > 1) requests.append(',');
            requests.append("{\"media\":{\"mediaType\":\"")
                    .append(i <= 2 ? "movie" : "tv").append("\",\"tmdbId\":")
                    .append(i).append(",\"title\":\"Request\"}}");
        }
        requests.append(']');
        responses.put("https://seerr.example/api/v1/request", json(requests.toString()));
        StringBuilder upcomingMovies = new StringBuilder("{\"results\":[");
        StringBuilder upcomingSeries = new StringBuilder("{\"results\":[");
        for (int i = 101; i <= 106; i++) {
            if (i > 101) { upcomingMovies.append(','); upcomingSeries.append(','); }
            upcomingMovies.append("{\"id\":").append(i).append(",\"title\":\"Film\"}");
            upcomingSeries.append("{\"id\":").append(i).append(",\"name\":\"Show\"}");
        }
        responses.put("https://seerr.example/api/v1/discover/movies/upcoming?limit=5",
                json(upcomingMovies.append("]}").toString()));
        responses.put("https://seerr.example/api/v1/discover/tv/upcoming?limit=5",
                json(upcomingSeries.append("]}").toString()));

        loader().load(settings(), MediaSnapshot.empty());
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 8; i++) expected.add("https://seerr.example/api/v1/"
                + (i <= 2 ? "movie/" : "tv/") + i);
        List<String> details = new ArrayList<>();
        for (String url : fetchCalls) {
            if (url.startsWith("https://seerr.example/api/v1/movie/")
                    || url.startsWith("https://seerr.example/api/v1/tv/")) details.add(url);
        }
        assertEquals(expected, details); // Eight request IDs, not upcoming IDs or the two extra requests.
    }

    @Test public void tvRequestsUseTvDetailPath() {
        responses.put("https://seerr.example/api/v1/request", json("[{\"media\":{"
                + "\"mediaType\":\"tv\",\"tmdbId\":1399,\"title\":\"Show\"}}]"));
        loader().load(settings(), MediaSnapshot.empty());
        assertTrue(fetchCalls.contains("https://seerr.example/api/v1/tv/1399"));
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

    private static MediaSettings jellySettings() {
        return MediaSettings.parse("jellyfin_url=https://jf.example\njellyfin_key=k\njellyfin_user=alice");
    }

    @Test public void jellyfinFillsContinueAndDownloadsFromTheNamedUser() {
        responses.put("https://jf.example/Users", json("[{\"Id\":\"u1\",\"Name\":\"Alice\"}]"));
        responses.put("https://jf.example/Users/u1/Items?Recursive=true&Filters=IsResumable"
                        + "&SortBy=LastPlayedDate&SortOrder=Descending&Limit=6",
                json("{\"Items\":[{\"Id\":\"a1\",\"Name\":\"Resume Me\",\"Type\":\"Movie\",\"ProductionYear\":2021}]}"));
        responses.put("https://jf.example/Users/u1/Items/Latest?Limit=5",
                json("[{\"Id\":\"b2\",\"Name\":\"Just Added\",\"Type\":\"Series\",\"ProductionYear\":2024}]"));
        MediaSnapshot snapshot = loader().load(jellySettings(), MediaSnapshot.empty());
        assertEquals(1, snapshot.continueCards.size());
        assertEquals("jf-a1", snapshot.continueCards.get(0).id);
        assertEquals(1, snapshot.downloadCards.size());
        assertEquals("jf-b2", snapshot.downloadCards.get(0).id);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("jellyfin").freshness);
    }

    @Test public void jellyfinUnavailableReusesPreviousListsMarkedStale() {
        responses.put("https://jf.example/Users", json("[{\"Id\":\"u1\",\"Name\":\"Alice\"}]"));
        responses.put("https://jf.example/Users/u1/Items?Recursive=true&Filters=IsResumable"
                        + "&SortBy=LastPlayedDate&SortOrder=Descending&Limit=6",
                json("{\"Items\":[{\"Id\":\"a1\",\"Name\":\"Resume Me\",\"Type\":\"Movie\"}]}"));
        responses.put("https://jf.example/Users/u1/Items/Latest?Limit=5", json("[]"));
        MediaLoader unit = loader();
        MediaSnapshot first = unit.load(jellySettings(), MediaSnapshot.empty());
        responses.clear();
        MediaSnapshot degraded = unit.load(jellySettings(), first);
        assertEquals(1, degraded.continueCards.size());
        assertEquals(MediaSnapshot.Freshness.STALE, degraded.sources.get("jellyfin").freshness);
    }

    @Test public void sportsdbFillsHeroSportsAndDedupesSharedFixtures() {
        String event = "{\"idEvent\":\"9\",\"strStatus\":\"\",\"dateEvent\":\"2027-01-16\",\"strTime\":\"15:00:00\","
                + "\"strLeague\":\"L\",\"strHomeTeam\":\"Home FC\",\"idHomeTeam\":\"100\","
                + "\"strAwayTeam\":\"Away FC\",\"idAwayTeam\":\"200\"}";
        responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextteam.php?id=100",
                json("{\"events\":[" + event + "]}"));
        responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextteam.php?id=200",
                json("{\"events\":[" + event + "]}"));
        MediaSettings settings = MediaSettings.parse("sportsdb_key=k\nsportsdb_teams=100, 200");
        MediaSnapshot snapshot = loader().load(settings, MediaSnapshot.empty());
        assertEquals(1, snapshot.sportsHero.size()); // same fixture from both clubs' listings
        assertEquals("sdb-9", snapshot.sportsHero.get(0).id);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("sportsdb").freshness);
    }

    @Test public void heroMediaCategoriesShareSixFiveFiveBudget() {
        responses.put("https://jf.example/Users", json("[{\"Id\":\"u1\",\"Name\":\"Alice\"}]"));
        StringBuilder continuing = new StringBuilder("{\"Items\":[");
        StringBuilder downloads = new StringBuilder("[");
        StringBuilder upcomingMovies = new StringBuilder("{\"results\":[");
        StringBuilder upcomingSeries = new StringBuilder("{\"results\":[");
        for (int i = 1; i <= 9; i++) {
            if (i > 1) {
                continuing.append(',');
                downloads.append(',');
                upcomingMovies.append(',');
                upcomingSeries.append(',');
            }
            continuing.append("{\"Id\":\"c").append(i).append("\",\"Name\":\"Continue\",\"Type\":\"Movie\"}");
            downloads.append("{\"Id\":\"d").append(i).append("\",\"Name\":\"Download\",\"Type\":\"Movie\"}");
            upcomingMovies.append("{\"id\":").append(i).append(",\"title\":\"Film\"}");
            upcomingSeries.append("{\"id\":").append(i).append(",\"name\":\"Show\"}");
        }
        responses.put("https://jf.example/Users/u1/Items?Recursive=true&Filters=IsResumable"
                        + "&SortBy=LastPlayedDate&SortOrder=Descending&Limit=6",
                json(continuing.append("]}").toString()));
        responses.put("https://jf.example/Users/u1/Items/Latest?Limit=5",
                json(downloads.append(']').toString()));
        responses.put("https://seerr.example/api/v1/discover/movies/upcoming?limit=5",
                json(upcomingMovies.append("]}").toString()));
        responses.put("https://seerr.example/api/v1/discover/tv/upcoming?limit=5",
                json(upcomingSeries.append("]}").toString()));
        MediaSnapshot snapshot = loader().load(MediaSettings.parse(
                "jellyfin_url=https://jf.example\njellyfin_key=k\nseerr_url=https://seerr.example\nseerr_key=k"),
                MediaSnapshot.empty());
        assertEquals(6, snapshot.continueCards.size());
        assertEquals(5, snapshot.downloadCards.size());
        assertEquals(5, snapshot.upcomingCards.size());
        assertEquals(16, snapshot.continueCards.size() + snapshot.downloadCards.size()
                + snapshot.upcomingCards.size());
        assertEquals("tmdb:1", snapshot.upcomingCards.get(0).id);
        assertEquals("tmdb:5", snapshot.upcomingCards.get(4).id);
    }

    @Test public void sportsdbHeroCapsAfterSortingAcrossTeams() {
        StringBuilder events = new StringBuilder("{\"events\":[");
        for (int i = 20; i >= 1; i--) {
            if (i < 20) events.append(',');
            events.append("{\"idEvent\":\"").append(i)
                    .append("\",\"strHomeTeam\":\"Home\",\"strAwayTeam\":\"Away\","
                            + "\"idHomeTeam\":\"100\",\"idAwayTeam\":\"200\","
                            + "\"dateEvent\":\"2027-01-16\",\"strTime\":\"")
                    .append(String.format(java.util.Locale.ROOT, "%02d:00:00", i)).append("\"}");
        }
        responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextteam.php?id=100",
                json(events.append("]}").toString()));
        MediaSnapshot snapshot = loader().load(MediaSettings.parse("sportsdb_key=k\nsportsdb_teams=100"),
                MediaSnapshot.empty());
        assertEquals(16, snapshot.sportsHero.size());
        assertEquals("sdb-1", snapshot.sportsHero.get(0).id);
        assertEquals("sdb-16", snapshot.sportsHero.get(15).id);
    }

    @Test public void sportsdbStaleReuseKeepsOnlyFixturesStillInTheSevenDayWindow() {
        String event = "{\"idEvent\":\"9\",\"dateEvent\":\"2027-01-16\",\"strTime\":\"15:00:00\","
                + "\"strHomeTeam\":\"Home\",\"idHomeTeam\":\"100\","
                + "\"strAwayTeam\":\"Away\",\"idAwayTeam\":\"200\"}";
        responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextteam.php?id=100",
                json("{\"events\":[" + event + "]}"));
        MediaSettings settings = MediaSettings.parse("sportsdb_key=k\nsportsdb_teams=100");
        MediaLoader unit = loader();
        MediaSnapshot first = unit.load(settings, MediaSnapshot.empty());
        responses.clear();
        MediaSnapshot stale = unit.load(settings, first);
        assertEquals(1, stale.sportsHero.size());
        assertEquals(MediaSnapshot.Freshness.STALE, stale.sources.get("sportsdb").freshness);
    }

    @Test public void sportsdbSkippedWhenKeyOrTeamsBlank() {
        MediaSnapshot noKey = loader().load(MediaSettings.parse("sportsdb_teams=100"), MediaSnapshot.empty());
        MediaSnapshot noTeams = loader().load(MediaSettings.parse("sportsdb_key=k"), MediaSnapshot.empty());
        assertTrue(noKey.sportsHero.isEmpty());
        assertTrue(noTeams.sportsHero.isEmpty());
        assertFalse(noKey.sources.containsKey("sportsdb"));
        assertFalse(noTeams.sources.containsKey("sportsdb"));
    }
}

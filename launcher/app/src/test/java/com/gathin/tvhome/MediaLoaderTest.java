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
        }, () -> NOW);
    }

    private static MediaSettings settings() {
        return MediaSettings.parse("seerr_url=https://seerr.example\nseerr_key=k");
    }

    private static byte[] json(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test public void seerrUpcomingFillsPoolsPerKind() {
        responses.put("https://seerr.example/api/v1/discover/movies/upcoming?limit=8",
                json("{\"results\":[{\"id\":1,\"title\":\"Next Film\",\"release_date\":\"2030-01-01\"}]}"));
        responses.put("https://seerr.example/api/v1/discover/tv/upcoming?limit=8",
                json("{\"results\":[{\"id\":2,\"name\":\"Next Show\",\"first_air_date\":\"2030-01-01\"}]}"));

        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals(1, snapshot.moviePool.size());
        assertEquals("Next Film", snapshot.moviePool.get(0).title);
        assertEquals(1, snapshot.seriesPool.size());
        assertEquals("Next Show", snapshot.seriesPool.get(0).title);
        assertTrue(snapshot.sportsPool.isEmpty());
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("seerr").freshness);
    }

    @Test public void seerrRequestsEndpointIsNoLongerFetched() {
        loader().load(settings(), MediaSnapshot.empty());
        assertFalse(fetchCalls.contains("https://seerr.example/api/v1/request"));
    }

    @Test public void failedSeerrFallsBackToCacheMarkedStale() {
        stored.put("seerr-upcoming-movie", json(
                "{\"results\":[{\"id\":1,\"title\":\"Old\",\"release_date\":\"2030-01-01\"}]}"));
        stored.put("seerr-upcoming-series", json("{\"results\":[]}"));
        ages.put("seerr-upcoming-movie", 900L);
        ages.put("seerr-upcoming-series", 900L);

        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals(MediaSnapshot.Freshness.STALE, snapshot.sources.get("seerr").freshness);
        assertEquals("Old", snapshot.moviePool.get(0).title);
    }

    @Test public void emptyCachedSeerrResponsesAreUnavailable() {
        stored.put("seerr-upcoming-movie", json("{\"results\":[]}"));
        stored.put("seerr-upcoming-series", json("{\"results\":[]}"));

        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals(MediaSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("seerr").freshness);
        assertTrue(snapshot.moviePool.isEmpty());
        assertTrue(snapshot.seriesPool.isEmpty());
    }

    @Test public void unavailableSourcesAreMarkedNotBorrowed() {
        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals(MediaSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("seerr").freshness);
        assertFalse(snapshot.anyContent());
    }

    @Test public void unconfiguredSourcesAreAbsentFromStatuses() {
        MediaSnapshot snapshot = loader().load(MediaSettings.parse(""), MediaSnapshot.empty());
        assertTrue(snapshot.sources.isEmpty());
    }

    private static MediaSettings jellySettings() {
        return MediaSettings.parse("jellyfin_url=https://jf.example\njellyfin_key=k\njellyfin_user=alice");
    }

    @Test public void poolsInterleaveContinueDownloadsAndUpcomingFilteredByKind() {
        responses.put("https://jf.example/Users", json("[{\"Id\":\"u1\",\"Name\":\"Alice\"}]"));
        responses.put("https://jf.example/Users/u1/Items?Recursive=true&Filters=IsResumable"
                        + "&SortBy=LastPlayedDate&SortOrder=Descending&Limit=8",
                json("{\"Items\":["
                        + "{\"Id\":\"c1\",\"Name\":\"Resume Film\",\"Type\":\"Movie\"},"
                        + "{\"Id\":\"c2\",\"Name\":\"Resume Show\",\"Type\":\"Series\"}]}"));
        responses.put("https://jf.example/Users/u1/Items/Latest?Limit=8",
                json("[{\"Id\":\"d1\",\"Name\":\"Just Added Film\",\"Type\":\"Movie\"}]"));
        responses.put("https://seerr.example/api/v1/discover/movies/upcoming?limit=8",
                json("{\"results\":[{\"id\":1,\"title\":\"Next Film\",\"release_date\":\"2030-01-01\"}]}"));
        responses.put("https://seerr.example/api/v1/discover/tv/upcoming?limit=8",
                json("{\"results\":[{\"id\":2,\"name\":\"Next Show\",\"first_air_date\":\"2030-01-01\"}]}"));
        MediaSettings mixed = MediaSettings.parse(
                "jellyfin_url=https://jf.example\njellyfin_key=k\njellyfin_user=alice"
                        + "\nseerr_url=https://seerr.example\nseerr_key=k");

        MediaSnapshot snapshot = loader().load(mixed, MediaSnapshot.empty());
        assertEquals(List.of("jf-c1", "jf-d1", "tmdb:1"),
                snapshot.moviePool.stream().map(c -> c.id).toList());
        assertEquals(List.of("jf-c2", "tmdb:2"),
                snapshot.seriesPool.stream().map(c -> c.id).toList());
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("jellyfin").freshness);
    }

    @Test public void seriesPoolInterleavesNextUpAndNewEpisodesBetweenContinueAndUpcoming() {
        responses.put("https://jf.example/Users", json("[{\"Id\":\"u1\",\"Name\":\"Alice\"}]"));
        responses.put("https://jf.example/Users/u1/Items?Recursive=true&Filters=IsResumable"
                        + "&SortBy=LastPlayedDate&SortOrder=Descending&Limit=8",
                json("{\"Items\":[{\"Id\":\"c1\",\"Name\":\"Resume Show\",\"Type\":\"Series\"}]}"));
        responses.put("https://jf.example/Shows/NextUp?userId=u1&Limit=8",
                json("{\"Items\":[{\"Id\":\"n1\",\"SeriesId\":\"s1\",\"SeriesName\":\"Next Show\","
                        + "\"Name\":\"Next Ep\"}]}"));
        responses.put("https://jf.example/Users/u1/Items/Latest?Limit=24&IncludeItemTypes=Episode",
                json("[{\"Id\":\"w1\",\"SeriesId\":\"s2\",\"SeriesName\":\"Fresh Show\","
                        + "\"Name\":\"Fresh Ep\"}]"));
        responses.put("https://jf.example/Users/u1/Items/Latest?Limit=8",
                json("[{\"Id\":\"d1\",\"Name\":\"Added Show\",\"Type\":\"Series\"}]"));
        responses.put("https://seerr.example/api/v1/discover/tv/upcoming?limit=8",
                json("{\"results\":[{\"id\":2,\"name\":\"Upcoming Show\",\"first_air_date\":\"2030-01-01\"}]}"));
        MediaSettings mixed = MediaSettings.parse(
                "jellyfin_url=https://jf.example\njellyfin_key=k\njellyfin_user=alice"
                        + "\nseerr_url=https://seerr.example\nseerr_key=k");

        MediaSnapshot snapshot = loader().load(mixed, MediaSnapshot.empty());
        assertEquals(List.of("jf-c1", "jf-n1", "jf-w1", "jf-d1", "tmdb:2"),
                snapshot.seriesPool.stream().map(c -> c.id).toList());
    }

    @Test public void poolsCapAtSixteen() {
        responses.put("https://jf.example/Users", json("[{\"Id\":\"u1\",\"Name\":\"Alice\"}]"));
        StringBuilder continuing = new StringBuilder("{\"Items\":[");
        StringBuilder downloads = new StringBuilder("[");
        for (int i = 1; i <= 8; i++) {
            if (i > 1) { continuing.append(','); downloads.append(','); }
            continuing.append("{\"Id\":\"c").append(i).append("\",\"Name\":\"Continue\",\"Type\":\"Movie\"}");
            downloads.append("{\"Id\":\"d").append(i).append("\",\"Name\":\"Download\",\"Type\":\"Movie\"}");
        }
        responses.put("https://jf.example/Users/u1/Items?Recursive=true&Filters=IsResumable"
                        + "&SortBy=LastPlayedDate&SortOrder=Descending&Limit=8",
                json(continuing.append("]}").toString()));
        responses.put("https://jf.example/Users/u1/Items/Latest?Limit=8", json(downloads.append(']').toString()));
        StringBuilder upcoming = new StringBuilder("{\"results\":[");
        for (int i = 1; i <= 8; i++) {
            if (i > 1) upcoming.append(',');
            upcoming.append("{\"id\":").append(i).append(",\"title\":\"Film\"}");
        }
        responses.put("https://seerr.example/api/v1/discover/movies/upcoming?limit=8",
                json(upcoming.append("]}").toString()));
        responses.put("https://seerr.example/api/v1/discover/tv/upcoming?limit=8",
                json("{\"results\":[]}"));
        MediaSettings mixed = MediaSettings.parse(
                "jellyfin_url=https://jf.example\njellyfin_key=k"
                        + "\nseerr_url=https://seerr.example\nseerr_key=k");

        MediaSnapshot snapshot = loader().load(mixed, MediaSnapshot.empty());
        assertEquals(16, snapshot.moviePool.size()); // 8 + 8 + 8 mixed, capped
        assertEquals("jf-c1", snapshot.moviePool.get(0).id);
        assertEquals("jf-c6", snapshot.moviePool.get(15).id); // c1,d1,u1,c2,d2,u2,...: slot 16 is c6
    }

    @Test public void jellyfinUnavailableReusesPreviousPoolMarkedStale() {
        responses.put("https://jf.example/Users", json("[{\"Id\":\"u1\",\"Name\":\"Alice\"}]"));
        responses.put("https://jf.example/Users/u1/Items?Recursive=true&Filters=IsResumable"
                        + "&SortBy=LastPlayedDate&SortOrder=Descending&Limit=8",
                json("{\"Items\":[{\"Id\":\"a1\",\"Name\":\"Resume Me\",\"Type\":\"Movie\"}]}"));
        responses.put("https://jf.example/Users/u1/Items/Latest?Limit=8", json("[]"));
        MediaLoader unit = loader();
        MediaSnapshot first = unit.load(jellySettings(), MediaSnapshot.empty());
        assertEquals(1, first.moviePool.size());
        responses.clear();
        MediaSnapshot degraded = unit.load(jellySettings(), first);
        assertEquals(1, degraded.moviePool.size()); // last-known pool contribution retained
        assertEquals(MediaSnapshot.Freshness.STALE, degraded.sources.get("jellyfin").freshness);
    }

    @Test public void sportsdbFillsSportsPoolAndDedupesSharedFixtures() {
        String event = "{\"idEvent\":\"9\",\"strStatus\":\"\",\"dateEvent\":\"2027-01-16\",\"strTime\":\"15:00:00\","
                + "\"strLeague\":\"L\",\"strHomeTeam\":\"Home FC\",\"idHomeTeam\":\"100\","
                + "\"strAwayTeam\":\"Away FC\",\"idAwayTeam\":\"200\"}";
        responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextteam.php?id=100",
                json("{\"events\":[" + event + "]}"));
        responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextteam.php?id=200",
                json("{\"events\":[" + event + "]}"));
        MediaSettings settings = MediaSettings.parse("sportsdb_key=k\nsportsdb_teams=100, 200");
        MediaSnapshot snapshot = loader().load(settings, MediaSnapshot.empty());
        assertEquals(1, snapshot.sportsPool.size()); // same fixture from both clubs' listings
        assertEquals("sdb-9", snapshot.sportsPool.get(0).id);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("sportsdb").freshness);
    }

    @Test public void sportsdbPoolCapsAfterSortingAcrossTeams() {
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
        assertEquals(16, snapshot.sportsPool.size());
        assertEquals("sdb-1", snapshot.sportsPool.get(0).id);
        assertEquals("sdb-16", snapshot.sportsPool.get(15).id);
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
        assertEquals(1, stale.sportsPool.size());
        assertEquals(MediaSnapshot.Freshness.STALE, stale.sources.get("sportsdb").freshness);
    }

    @Test public void sportsdbSkippedWhenKeyOrTeamsBlank() {
        MediaSnapshot noKey = loader().load(MediaSettings.parse("sportsdb_teams=100"), MediaSnapshot.empty());
        MediaSnapshot noTeams = loader().load(MediaSettings.parse("sportsdb_key=k"), MediaSnapshot.empty());
        assertTrue(noKey.sportsPool.isEmpty());
        assertTrue(noTeams.sportsPool.isEmpty());
        assertFalse(noKey.sources.containsKey("sportsdb"));
        assertFalse(noTeams.sources.containsKey("sportsdb"));
    }

    @Test public void sportsdbRunsWithLeaguesOnlyWhenTeamsBlank() {
        responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextleague.php?id=4328",
                json("{\"events\":[{\"idEvent\":\"7\",\"strEvent\":\"Home FC vs Away FC\","
                        + "\"strStatus\":\"NS\",\"strLeague\":\"L\",\"strHomeTeam\":\"Home FC\","
                        + "\"strAwayTeam\":\"Away FC\",\"dateEvent\":\"2027-01-16\",\"strTime\":\"15:00:00\"}]}"));
        MediaSnapshot snapshot = loader().load(MediaSettings.parse("sportsdb_key=k\nsportsdb_leagues=4328"),
                MediaSnapshot.empty());
        assertEquals(1, snapshot.sportsPool.size());
        assertEquals("sdb-7", snapshot.sportsPool.get(0).id);
        assertEquals("Home FC vs Away FC", snapshot.sportsPool.get(0).title);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("sportsdb").freshness);
    }

    @Test public void sportsdbLeagueCardYieldedToTeamCardForTheSameFixture() {
        String teamEvent = "{\"idEvent\":\"9\",\"strStatus\":\"\",\"dateEvent\":\"2027-01-16\","
                + "\"strTime\":\"15:00:00\",\"strLeague\":\"L\",\"strHomeTeam\":\"Home FC\","
                + "\"idHomeTeam\":\"100\",\"strAwayTeam\":\"Away FC\",\"idAwayTeam\":\"200\","
                + "\"strAwayTeamBadge\":\"/pic/away.png\"}";
        String leagueEvent = "{\"idEvent\":\"9\",\"strEvent\":\"Home FC vs Away FC\","
                + "\"strThumb\":\"https://cdn.example/thumb.jpg\",\"strStatus\":\"NS\","
                + "\"strLeague\":\"L\",\"strHomeTeam\":\"Home FC\",\"strAwayTeam\":\"Away FC\","
                + "\"dateEvent\":\"2027-01-16\",\"strTime\":\"15:00:00\"}";
        responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextteam.php?id=100",
                json("{\"events\":[" + teamEvent + "]}"));
        responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextleague.php?id=4328",
                json("{\"events\":[" + leagueEvent + ","
                        + leagueEvent.replace("\"9\"", "\"10\"") + "]}"));
        MediaSettings settings = MediaSettings.parse("sportsdb_key=k\nsportsdb_teams=100\nsportsdb_leagues=4328");
        MediaSnapshot snapshot = loader().load(settings, MediaSnapshot.empty());
        assertEquals(2, snapshot.sportsPool.size());
        MediaCard teamCard = snapshot.sportsPool.get(0);
        assertEquals("sdb-9", teamCard.id);
        assertEquals("Away FC", teamCard.title); // the team card's opponent title, not the fixture title
        assertEquals("https://www.thesportsdb.com/pic/away.png", teamCard.artUrl);
        assertEquals("sdb-10", snapshot.sportsPool.get(1).id); // the league-only fixture still joins
        assertEquals("Home FC vs Away FC", snapshot.sportsPool.get(1).title);
    }

    @Test public void sportsdbLeagueListsMergeAndCapAtSixteen() {
        for (int league : new int[] {4328, 4480}) {
            StringBuilder events = new StringBuilder("{\"events\":[");
            for (int i = 1; i <= 10; i++) {
                if (i > 1) events.append(',');
                int id = league + i;
                events.append("{\"idEvent\":\"").append(id)
                        .append("\",\"strEvent\":\"Home vs Away\",\"strStatus\":\"NS\","
                                + "\"strLeague\":\"L\",\"strHomeTeam\":\"Home\",\"strAwayTeam\":\"Away\","
                                + "\"dateEvent\":\"2027-01-16\",\"strTime\":\"")
                        .append(String.format(java.util.Locale.ROOT, "%02d:00:00", i)).append("\"}");
            }
            events.append("]}");
            responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextleague.php?id=" + league,
                    json(events.toString()));
        }
        MediaSnapshot snapshot = loader().load(MediaSettings.parse("sportsdb_key=k\nsportsdb_leagues=4328,4480"),
                MediaSnapshot.empty());
        assertEquals(16, snapshot.sportsPool.size());
        assertEquals("sdb-4329", snapshot.sportsPool.get(0).id); // earliest kickoff first
        assertEquals("sdb-4488", snapshot.sportsPool.get(15).id);
    }

    @Test public void sportsdbFailedLeagueDoesNotFailSourceWhenOthersSucceed() {
        responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextleague.php?id=4480",
                json("{\"events\":[{\"idEvent\":\"7\",\"strEvent\":\"Home FC vs Away FC\","
                        + "\"strStatus\":\"NS\",\"strLeague\":\"L\",\"strHomeTeam\":\"Home FC\","
                        + "\"strAwayTeam\":\"Away FC\",\"dateEvent\":\"2027-01-16\",\"strTime\":\"15:00:00\"}]}"));
        MediaSettings settings = MediaSettings.parse("sportsdb_key=k\nsportsdb_teams=100\nsportsdb_leagues=4328,4480");
        MediaSnapshot snapshot = loader().load(settings, MediaSnapshot.empty());
        assertEquals(1, snapshot.sportsPool.size());
        assertEquals("sdb-7", snapshot.sportsPool.get(0).id);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("sportsdb").freshness);
    }

    @Test public void sportsdbTotalFailureAcrossTeamsAndLeaguesReusesStale() {
        responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextteam.php?id=100",
                json("{\"events\":[{\"idEvent\":\"9\",\"strStatus\":\"\",\"dateEvent\":\"2027-01-16\","
                        + "\"strTime\":\"15:00:00\",\"strLeague\":\"L\",\"strHomeTeam\":\"Home\","
                        + "\"idHomeTeam\":\"100\",\"strAwayTeam\":\"Away\",\"idAwayTeam\":\"200\"}]}"));
        responses.put("https://www.thesportsdb.com/api/v1/json/k/eventsnextleague.php?id=4328",
                json("{\"events\":[{\"idEvent\":\"10\",\"strEvent\":\"Home vs Away\",\"strStatus\":\"NS\","
                        + "\"strLeague\":\"L\",\"strHomeTeam\":\"Home\",\"strAwayTeam\":\"Away\","
                        + "\"dateEvent\":\"2027-01-16\",\"strTime\":\"16:00:00\"}]}"));
        MediaSettings settings = MediaSettings.parse("sportsdb_key=k\nsportsdb_teams=100\nsportsdb_leagues=4328");
        MediaLoader unit = loader();
        MediaSnapshot first = unit.load(settings, MediaSnapshot.empty());
        assertEquals(2, first.sportsPool.size());
        responses.clear();
        MediaSnapshot stale = unit.load(settings, first);
        assertEquals(2, stale.sportsPool.size());
        assertEquals(MediaSnapshot.Freshness.STALE, stale.sources.get("sportsdb").freshness);
    }
}

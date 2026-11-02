package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class JellyfinSourceTest {
    private static final String BASE = "https://jf.example";

    private final Map<String, byte[]> responses = new HashMap<>();
    private final Map<String, Map<String, String>> seenHeaders = new HashMap<>();
    private final MediaLoader.Fetcher fetcher = (url, headers) -> {
        seenHeaders.put(url, headers);
        return responses.get(url);
    };

    private static byte[] json(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static final String USERS = "[{\"Id\":\"u1\",\"Name\":\"Alice\"},{\"Id\":\"u2\",\"Name\":\"bob\"}]";

    @Test public void resolvesConfiguredUserByNameCaseInsensitively() {
        responses.put(BASE + "/Users", json(USERS));
        assertEquals("u2", JellyfinSource.resolveUser(fetcher, BASE, "k", "BOB"));
        assertEquals("u2", JellyfinSource.resolveUser(fetcher, BASE, "k", "bob"));
    }

    @Test public void fallsBackToFirstUserWhenUnsetOrUnmatched() {
        responses.put(BASE + "/Users", json(USERS));
        assertEquals("u1", JellyfinSource.resolveUser(fetcher, BASE, "k", ""));
        assertEquals("u1", JellyfinSource.resolveUser(fetcher, BASE, "k", "nobody"));
        assertEquals("u1", JellyfinSource.resolveUser(fetcher, BASE, "k", null));
    }

    @Test public void resolveUserSendsTokenHeaderAndIsNullOnGarbage() {
        assertNull(JellyfinSource.resolveUser(fetcher, BASE, "k", ""));
        responses.put(BASE + "/Users", json("]]garbage"));
        assertNull(JellyfinSource.resolveUser(fetcher, BASE, "k", "alice"));
        responses.put(BASE + "/Users", json(USERS));
        JellyfinSource.resolveUser(fetcher, BASE, "k", "alice");
        assertEquals("MediaBrowser Token=\"k\"", seenHeaders.get(BASE + "/Users").get("Authorization"));
    }

    @Test public void continueAndLatestUrlsTargetTheResolvedUser() {
        assertEquals(BASE + "/Users/u1/Items?Recursive=true&Filters=IsResumable"
                        + "&SortBy=LastPlayedDate&SortOrder=Descending&Limit=6",
                JellyfinSource.continueUrl(BASE, "u1", 6));
        assertEquals(BASE + "/Users/u1/Items/Latest?Limit=5", JellyfinSource.latestUrl(BASE, "u1", 5));
    }

    @Test public void parsesItemsEnvelopeIntoCards() {
        byte[] body = json("{\"Items\":["
                + "{\"Id\":\"a1\",\"Name\":\"A Film\",\"Type\":\"Movie\",\"ProductionYear\":2024},"
                + "{\"Id\":\"b2\",\"Name\":\"A Show\",\"Type\":\"Series\",\"ProductionYear\":0},"
                + "{\"Id\":\"c3\",\"Name\":\"An Episode\",\"Type\":\"Episode\",\"ProductionYear\":2020},"
                + "{\"Id\":\"\",\"Name\":\"No Id\",\"Type\":\"Movie\"},"
                + "null,\"]]garbage\"]}");
        List<MediaCard> cards = JellyfinSource.parseItems(body, BASE, 6);
        assertEquals(2, cards.size());
        MediaCard movie = cards.get(0);
        assertEquals("jf-a1", movie.id);
        assertEquals(MediaCard.Kind.MOVIE, movie.kind);
        assertEquals("A Film", movie.title);
        assertEquals("2024 · Movie", movie.line);
        assertEquals(MediaCard.State.AVAILABLE, movie.state);
        assertEquals("", movie.tmdbId);
        assertEquals("", movie.streamUrl);
        assertEquals(BASE + "/Items/a1/Images/Primary?maxWidth=342&quality=90", movie.artUrl);
        MediaCard series = cards.get(1);
        assertEquals(MediaCard.Kind.SERIES, series.kind);
        assertEquals("Series", series.line);
    }

    @Test public void parsesLatestBareArray() {
        byte[] body = json("[{\"Id\":\"d4\",\"Name\":\"New Show\",\"Type\":\"Series\",\"ProductionYear\":2023}]");
        List<MediaCard> cards = JellyfinSource.parseLatest(body, BASE, 5);
        assertEquals(1, cards.size());
        assertEquals("jf-d4", cards.get(0).id);
        assertEquals("2023 · Series", cards.get(0).line);
    }

    @Test public void garbageBodiesParseToEmpty() {
        assertTrue(JellyfinSource.parseItems(null, BASE, 6).isEmpty());
        assertTrue(JellyfinSource.parseItems(json("]]garbage"), BASE, 6).isEmpty());
        assertTrue(JellyfinSource.parseItems(json("{}"), BASE, 6).isEmpty());
        assertTrue(JellyfinSource.parseLatest(json("{\"Items\":[]}"), BASE, 5).isEmpty());
    }
}

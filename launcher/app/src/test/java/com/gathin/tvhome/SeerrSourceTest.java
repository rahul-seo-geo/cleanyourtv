package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SeerrSourceTest {
    private static byte[] json(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static final String REQUESTS = "["
            + "{\"id\":1,\"status\":2,\"media\":{\"mediaType\":\"movie\",\"tmdbId\":550,\"title\":\"Fight Club\"}},"
            + "{\"id\":2,\"status\":5,\"media\":{\"mediaType\":\"tv\",\"tmdbId\":1399,\"title\":\"Game of Thrones\"}},"
            + "{\"id\":3,\"status\":1,\"media\":{\"mediaType\":\"movie\",\"tmdbId\":680,\"title\":\"Pulp Fiction\"}},"
            + "{\"id\":4,\"status\":3,\"media\":{\"mediaType\":\"tv\",\"tmdbId\":0}}"
            + "]";

    @Test public void splitsRequestsIntoMoviesAndSeriesWithStates() {
        List<MediaCard> cards = SeerrSource.parseRequests(json(REQUESTS));
        assertEquals(2, cards.stream().filter(c -> c.kind == MediaCard.Kind.MOVIE).count());
        assertEquals(2, cards.stream().filter(c -> c.kind == MediaCard.Kind.SERIES).count());
        MediaCard fightClub = cards.get(0);
        assertEquals("Fight Club", fightClub.title);
        assertEquals(MediaCard.State.APPROVED, fightClub.state);
        assertEquals("tmdb:550", fightClub.id);
        assertEquals("550", fightClub.tmdbId);
        assertEquals(MediaCard.State.AVAILABLE, cards.get(1).state);
        assertEquals(MediaCard.State.REQUESTED, cards.get(2).state);
    }

    @Test public void defensiveAgainstMissingTitleAndTmdb() {
        List<MediaCard> cards = SeerrSource.parseRequests(json(REQUESTS));
        MediaCard odd = cards.get(3);
        assertEquals(MediaCard.State.UNAVAILABLE, odd.state);
        assertTrue(odd.title.startsWith("TMDB "));
    }

    @Test public void acceptsPagedRequestsWrapper() {
        String body = "{\"pageInfo\":{\"page\":1,\"results\":1},\"results\":["
                + "{\"id\":9,\"status\":5,\"media\":{\"mediaType\":\"movie\",\"tmdbId\":157336}}"
                + "]}";
        List<MediaCard> cards = SeerrSource.parseRequests(json(body));
        assertEquals(1, cards.size());
        assertEquals("TMDB 157336", cards.get(0).title);
        assertEquals(MediaCard.State.AVAILABLE, cards.get(0).state);
    }

    @Test public void garbageBodyYieldsEmpty() {
        assertTrue(SeerrSource.parseRequests(json("not json")).isEmpty());
        assertTrue(SeerrSource.parseRequests(null).isEmpty());
        assertTrue(SeerrSource.parseUpcoming(json("{\"results\":[]}"), MediaCard.Kind.MOVIE).isEmpty());
    }

    @Test public void upcomingParsesTitleReleaseDateAndKind() {
        String body = "{\"results\":["
                + "{\"title\":\"Upcoming Film\",\"release_date\":\"2027-01-15\"},"
                + "{\"name\":\"Upcoming Show\",\"first_air_date\":\"2027-02-01\",\"tmdbId\":123}]"
                + "}";
        List<MediaCard> movies = SeerrSource.parseUpcoming(json(body), MediaCard.Kind.MOVIE);
        List<MediaCard> series = SeerrSource.parseUpcoming(json(body), MediaCard.Kind.SERIES);
        assertEquals("Upcoming Film", movies.get(0).title);
        assertEquals(MediaCard.State.UPCOMING, movies.get(0).state);
        assertTrue(movies.get(0).whenMs > 0);
        assertEquals("Upcoming Show", series.get(0).title);
        assertEquals(179_000_000_000L < series.get(0).whenMs, true);
    }

    @Test public void upcomingReadsCamelCaseDates() {
        String body = "{\"results\":[{\"title\":\"Doomsday\",\"releaseDate\":\"2026-12-16\"},"
                + "{\"name\":\"Potter\",\"firstAirDate\":\"2026-12-25\"}]}";
        List<MediaCard> movies = SeerrSource.parseUpcoming(json(body), MediaCard.Kind.MOVIE);
        List<MediaCard> series = SeerrSource.parseUpcoming(json(body), MediaCard.Kind.SERIES);
        assertEquals("Doomsday", movies.get(0).title);
        assertTrue(movies.get(0).whenMs > 0);
        assertTrue(series.get(0).whenMs > 0);
    }

    @Test public void upcomingCardsCarryTmdbPosterUrl() {
        List<MediaCard> cards = SeerrSource.parseUpcoming(json("{\"results\":[{\"title\":\"Film\","
                + "\"posterPath\":\"/jzPwsojjFStf5lR5Nm07w2hH56G.jpg\"}]}"), MediaCard.Kind.MOVIE);
        assertEquals("https://image.tmdb.org/t/p/w342/jzPwsojjFStf5lR5Nm07w2hH56G.jpg",
                cards.get(0).artUrl);
    }

    @Test public void upcomingWithoutPosterHasNoArt() {
        List<MediaCard> cards = SeerrSource.parseUpcoming(json("{\"results\":[{\"title\":\"Absent\"},"
                + "{\"title\":\"Null\",\"posterPath\":null},"
                + "{\"title\":\"String null\",\"posterPath\":\"null\"}]}"), MediaCard.Kind.MOVIE);
        for (MediaCard card : cards) assertEquals("", card.artUrl);
    }

    @Test public void detailUrlBuildsMovieAndTvPaths() {
        assertEquals("https://seerr.example/api/v1/movie/550",
                SeerrSource.detailUrl("https://seerr.example", MediaCard.Kind.MOVIE, "550"));
        assertEquals("https://seerr.example/api/v1/tv/1399",
                SeerrSource.detailUrl("https://seerr.example", MediaCard.Kind.SERIES, "1399"));
    }

    @Test public void parsePosterReadsDetailBody() {
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg",
                SeerrSource.parsePoster(json("{\"posterPath\":\"/a.jpg\"}")));
    }

    @Test public void parsePosterDefensiveOnGarbage() {
        assertEquals("", SeerrSource.parsePoster(json("[]")));
        assertEquals("", SeerrSource.parsePoster(null));
        assertEquals("", SeerrSource.parsePoster(json("garbage")));
        assertEquals("", SeerrSource.parsePoster(json("{\"posterPath\":\"relative.jpg\"}")));
    }

    @Test public void urlsAndHeadersMatchNewsAddonContract() {
        assertEquals("https://seerr.example/api/v1/request", SeerrSource.requestsUrl("https://seerr.example"));
        assertEquals("https://seerr.example/api/v1/discover/movies/upcoming?limit=5",
                SeerrSource.upcomingUrl("https://seerr.example", MediaCard.Kind.MOVIE, 5));
        assertEquals("https://seerr.example/api/v1/discover/tv/upcoming?limit=5",
                SeerrSource.upcomingUrl("https://seerr.example", MediaCard.Kind.SERIES, 5));
        assertEquals("secret", SeerrSource.headers("secret").get("X-Api-Key"));
    }
}

package com.gathin.tvhome;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaSnapshotTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 60L * 60 * 1000;

    private static MediaCard movie(String id, String title, long when) {
        return new MediaCard(id, MediaCard.Kind.MOVIE, title, "", MediaCard.State.UPCOMING,
                when, 0, "550", "", "");
    }

    @Test public void emptySnapshotHasNoContent() {
        MediaSnapshot snapshot = MediaSnapshot.empty();
        assertTrue(snapshot.moviePool.isEmpty() && snapshot.seriesPool.isEmpty());
        assertTrue(snapshot.sportsPool.isEmpty());
        assertFalse(snapshot.anyContent());
    }

    @Test public void anyContentLooksAtAllPools() {
        MediaSnapshot snapshot = new MediaSnapshot(List.of(), List.of(),
                List.of(new MediaCard("sdb-1", MediaCard.Kind.SPORTS, "AFL", "",
                        MediaCard.State.UPCOMING, NOW + HOUR, 0, "", "", "")),
                java.util.Map.of("sportsdb",
                        new MediaSnapshot.SourceState(MediaSnapshot.Freshness.FRESH, NOW)));
        assertTrue(snapshot.anyContent());
    }

    @Test public void dropExpiredRemovesPastAnchoredCardsButKeepsAvailableOnes() {
        MediaCard past = movie("past", "Old Release", NOW - 2 * HOUR);
        MediaCard future = movie("future", "New Release", NOW + 2 * HOUR);
        MediaCard undated = new MediaCard("undated", MediaCard.Kind.MOVIE, "Requested",
                "", MediaCard.State.REQUESTED, 0, 0, "551", "", "");
        List<MediaCard> kept = MediaSnapshot.dropExpired(List.of(past, future, undated), NOW + HOUR);
        assertEquals(List.of("future", "undated"), kept.stream().map(c -> c.id).toList());
    }

    @Test public void liveAndFinalSportsCardsSurviveExpiry() {
        MediaCard live = new MediaCard("live", MediaCard.Kind.SPORTS, "AFL", "",
                MediaCard.State.LIVE, NOW - HOUR, 0, "", "", "");
        MediaCard done = new MediaCard("done", MediaCard.Kind.SPORTS, "NRL", "",
                MediaCard.State.FINAL, NOW - 3 * HOUR, 0, "", "", "");
        assertEquals(2, MediaSnapshot.dropExpired(List.of(live, done), NOW).size());
    }

    @Test public void ofKindKeepsOrderAndSkipsOtherKinds() {
        MediaCard m1 = movie("m1", "One", 0);
        MediaCard s1 = new MediaCard("s1", MediaCard.Kind.SERIES, "Show", "",
                MediaCard.State.AVAILABLE, 0, 0, "", "", "");
        MediaCard m2 = movie("m2", "Two", 0);
        List<MediaCard> movies = MediaSnapshot.ofKind(List.of(m1, s1, m2), MediaCard.Kind.MOVIE);
        assertEquals(List.of("m1", "m2"), movies.stream().map(c -> c.id).toList());
    }
}

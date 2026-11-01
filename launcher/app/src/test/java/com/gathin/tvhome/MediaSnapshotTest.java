package com.gathin.tvhome;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaSnapshotTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 60L * 60 * 1000;

    private static MediaCard movie(String id, String title, long when) {
        return new MediaCard(id, MediaCard.Kind.MOVIE, title, "", MediaCard.State.UPCOMING,
                when, 0, "550", "");
    }

    @Test public void emptySnapshotHasNoContent() {
        MediaSnapshot snapshot = MediaSnapshot.empty();
        assertTrue(snapshot.movies.isEmpty() && snapshot.series.isEmpty());
        assertTrue(snapshot.sports.isEmpty() && snapshot.live.isEmpty());
        assertFalse(snapshot.anyContent());
    }

    @Test public void dropExpiredRemovesPastAnchoredCardsButKeepsAvailableOnes() {
        MediaCard past = movie("past", "Old Release", NOW - 2 * HOUR);
        MediaCard future = movie("future", "New Release", NOW + 2 * HOUR);
        MediaCard undated = new MediaCard("undated", MediaCard.Kind.MOVIE, "Requested",
                "", MediaCard.State.REQUESTED, 0, 0, "551", "");
        List<MediaCard> kept = MediaSnapshot.dropExpired(List.of(past, future, undated), NOW + HOUR);
        assertEquals(List.of("future", "undated"), kept.stream().map(c -> c.id).toList());
    }

    @Test public void liveAndFinalSportsCardsSurviveExpiry() {
        MediaCard live = new MediaCard("live", MediaCard.Kind.SPORTS, "AFL", "",
                MediaCard.State.LIVE, NOW - HOUR, 0, "", "");
        MediaCard done = new MediaCard("done", MediaCard.Kind.SPORTS, "NRL", "",
                MediaCard.State.FINAL, NOW - 3 * HOUR, 0, "", "");
        assertEquals(2, MediaSnapshot.dropExpired(List.of(live, done), NOW).size());
    }

    @Test public void anyContentLooksAtAllFourCategories() {
        MediaSnapshot snapshot = new MediaSnapshot(List.of(), List.of(), List.of(),
                List.of(new MediaCard("ch", MediaCard.Kind.LIVE, "Channel", "News",
                        MediaCard.State.AVAILABLE, 0, 0, "", "http://nas/ch.m3u8")),
                java.util.Map.of("playlist",
                        new MediaSnapshot.SourceState(MediaSnapshot.Freshness.FRESH, NOW)));
        assertTrue(snapshot.anyContent());
    }
}

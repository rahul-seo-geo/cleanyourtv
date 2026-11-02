package com.gathin.tvhome;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaMixTest {
    private static MediaCard card(String id) {
        return new MediaCard(id, MediaCard.Kind.MOVIE, id, "", MediaCard.State.AVAILABLE,
                0, 0, "", "", "");
    }

    private static List<MediaCard> ids(String... ids) {
        List<MediaCard> cards = new java.util.ArrayList<>();
        for (String id : ids) cards.add(card(id));
        return cards;
    }

    private static List<String> names(List<MediaCard> cards) {
        List<String> names = new java.util.ArrayList<>();
        for (MediaCard card : cards) names.add(card.id);
        return names;
    }

    @Test public void roundRobinTakesOneCardFromEachSourceInOrder() {
        List<MediaCard> mixed = MediaMix.interleave(
                List.of(ids("m1", "m2"), ids("s1", "s2"), ids("x1")), 16);
        assertEquals(List.of("m1", "s1", "x1", "m2", "s2"), names(mixed));
    }

    @Test public void emptySourcesAreSkippedWithoutBreakingTheOrder() {
        List<MediaCard> mixed = MediaMix.interleave(
                List.of(ids("m1", "m2"), List.of(), ids("s1")), 16);
        assertEquals(List.of("m1", "s1", "m2"), names(mixed));
    }

    @Test public void totalIsCapped() {
        List<MediaCard> mixed = MediaMix.interleave(
                List.of(ids("m1", "m2", "m3"), ids("s1", "s2", "s3")), 4);
        assertEquals(List.of("m1", "s1", "m2", "s2"), names(mixed));
    }

    @Test public void capOfZeroYieldsNothing() {
        assertTrue(MediaMix.interleave(List.of(ids("m1")), 0).isEmpty());
    }

    @Test public void singleSourcePassesThroughWithIdsIntact() {
        List<MediaCard> only = ids("a", "b");
        List<MediaCard> mixed = MediaMix.interleave(List.of(only, List.of()), 16);
        assertEquals(List.of("a", "b"), names(mixed));
        assertSame(only.get(0), mixed.get(0)); // stable ids: the very same card objects
    }

    @Test public void allEmptyYieldsNothing() {
        assertTrue(MediaMix.interleave(List.of(List.of(), List.of()), 16).isEmpty());
    }
}

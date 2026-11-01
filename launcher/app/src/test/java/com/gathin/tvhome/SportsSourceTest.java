package com.gathin.tvhome;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SportsSourceTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 60L * 60 * 1000;

    private static GameDay.Turn turn(String top, long start, long end, int colour) {
        return new GameDay.Turn(top, "Puck drop", start, end, colour);
    }

    @Test public void mapsPhaseByTimeWindow() {
        assertEquals(MediaCard.State.UPCOMING, SportsSource.phase(turn("AFL", NOW + HOUR, NOW + 3 * HOUR, 0xFF00FF00), NOW));
        assertEquals(MediaCard.State.LIVE, SportsSource.phase(turn("AFL", NOW - HOUR, NOW + HOUR, 0xFF00FF00), NOW));
        assertEquals(MediaCard.State.FINAL, SportsSource.phase(turn("AFL", NOW - 3 * HOUR, NOW - HOUR, 0xFF00FF00), NOW));
    }

    @Test public void cardsCarryIdentityTitleWordAndColour() {
        List<MediaCard> cards = SportsSource.cards(
                List.of(turn("Richmond vs Carlton", NOW + HOUR, NOW + 3 * HOUR, 0xFFEEDDCC)), NOW);
        MediaCard card = cards.get(0);
        assertEquals("Richmond vs Carlton|" + (NOW + HOUR), card.id);
        assertEquals(MediaCard.Kind.SPORTS, card.kind);
        assertEquals("Richmond vs Carlton", card.title);
        assertEquals("Puck drop", card.line);
        assertEquals(0xFFEEDDCC, card.accent);
        assertEquals(NOW + HOUR, card.whenMs);
        assertEquals(MediaCard.State.UPCOMING, card.state);
    }

    @Test public void sortsByStartAndCapsAtTwelve() {
        java.util.List<GameDay.Turn> turns = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) turns.add(turn("G" + i, NOW + i * HOUR, NOW + (i + 2) * HOUR, 0));
        List<MediaCard> cards = SportsSource.cards(turns, NOW);
        assertEquals(12, cards.size());
        assertTrue(cards.get(0).whenMs <= cards.get(1).whenMs);
        assertEquals("G1", cards.get(0).title);
    }

    @Test public void emptyTurnsYieldEmptyCards() {
        assertTrue(SportsSource.cards(List.of(), NOW).isEmpty());
        assertTrue(SportsSource.cards(null, NOW).isEmpty());
    }
}

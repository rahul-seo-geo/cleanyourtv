package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Game Day turns as fixture cards. Reads the existing contract; adds no provider access of its own. */
final class SportsSource {
    private static final int MAX_FIXTURES = 12;

    static MediaCard.State phase(GameDay.Turn turn, long nowMs) {
        if (nowMs < turn.startMs) return MediaCard.State.UPCOMING;
        return nowMs <= turn.endMs ? MediaCard.State.LIVE : MediaCard.State.FINAL;
    }

    static List<MediaCard> cards(List<GameDay.Turn> turns, long nowMs) {
        List<MediaCard> cards = new ArrayList<>();
        if (turns == null) return cards;
        List<GameDay.Turn> sorted = new ArrayList<>(turns);
        sorted.sort(Comparator.comparingLong((GameDay.Turn t) -> t.startMs));
        for (GameDay.Turn turn : sorted) {
            if (cards.size() >= MAX_FIXTURES) break;
            cards.add(new MediaCard(turn.topLine + "|" + turn.startMs, MediaCard.Kind.SPORTS,
                    turn.topLine, turn.word, phase(turn, nowMs), turn.startMs, turn.colour, "", ""));
        }
        return cards;
    }
}

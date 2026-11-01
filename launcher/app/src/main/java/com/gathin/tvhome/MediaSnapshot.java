package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** One complete dashboard state, applied to the view atomically; the view never fetches. */
final class MediaSnapshot {
    enum Freshness { FRESH, STALE, UNAVAILABLE }

    static final class SourceState {
        final Freshness freshness;
        final long updatedMs;
        SourceState(Freshness freshness, long updatedMs) { this.freshness = freshness; this.updatedMs = updatedMs; }
    }

    final List<MediaCard> movies;
    final List<MediaCard> series;
    final List<MediaCard> sports;
    final List<MediaCard> live;
    final Map<String, SourceState> sources;

    MediaSnapshot(List<MediaCard> movies, List<MediaCard> series, List<MediaCard> sports,
                  List<MediaCard> live, Map<String, SourceState> sources) {
        this.movies = Collections.unmodifiableList(new ArrayList<>(movies));
        this.series = Collections.unmodifiableList(new ArrayList<>(series));
        this.sports = Collections.unmodifiableList(new ArrayList<>(sports));
        this.live = Collections.unmodifiableList(new ArrayList<>(live));
        this.sources = Collections.unmodifiableMap(new java.util.HashMap<>(sources));
    }

    static MediaSnapshot empty() {
        return new MediaSnapshot(List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    boolean anyContent() {
        return !movies.isEmpty() || !series.isEmpty() || !sports.isEmpty() || !live.isEmpty();
    }

    /**
     * Deterministic expiry: an anchored card (whenMs > 0) is dropped once its moment passed by more
     * than an hour, except sports cards whose own lifecycle states (LIVE/FINAL) keep them truthful.
     */
    static List<MediaCard> dropExpired(List<MediaCard> cards, long nowMs) {
        List<MediaCard> kept = new ArrayList<>();
        for (MediaCard card : cards) {
            if (card.kind == MediaCard.Kind.SPORTS) { kept.add(card); continue; }
            if (card.whenMs > 0 && card.whenMs + 60L * 60 * 1000 < nowMs) continue;
            kept.add(card);
        }
        return kept;
    }
}

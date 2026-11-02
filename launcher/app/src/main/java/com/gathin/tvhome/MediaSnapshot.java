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

    /** Per-kind card pools for the dashboard carousel, each built by MediaMix.interleave (movies
     *  and series) or SportsDbSource.heroCards (sports) and capped at sixteen cards. */
    final List<MediaCard> moviePool;
    final List<MediaCard> seriesPool;
    final List<MediaCard> sportsPool;
    final Map<String, SourceState> sources;

    MediaSnapshot(List<MediaCard> moviePool, List<MediaCard> seriesPool, List<MediaCard> sportsPool,
                  Map<String, SourceState> sources) {
        this.moviePool = Collections.unmodifiableList(new ArrayList<>(moviePool));
        this.seriesPool = Collections.unmodifiableList(new ArrayList<>(seriesPool));
        this.sportsPool = Collections.unmodifiableList(new ArrayList<>(sportsPool));
        this.sources = Collections.unmodifiableMap(new java.util.HashMap<>(sources));
    }

    static MediaSnapshot empty() {
        return new MediaSnapshot(List.of(), List.of(), List.of(), Map.of());
    }

    boolean anyContent() {
        return !moviePool.isEmpty() || !seriesPool.isEmpty() || !sportsPool.isEmpty();
    }

    /** The cards of one kind, in their incoming order; the pool inputs stay in source order. */
    static List<MediaCard> ofKind(List<MediaCard> cards, MediaCard.Kind kind) {
        List<MediaCard> kept = new ArrayList<>();
        for (MediaCard card : cards) {
            if (card.kind == kind) kept.add(card);
        }
        return kept;
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

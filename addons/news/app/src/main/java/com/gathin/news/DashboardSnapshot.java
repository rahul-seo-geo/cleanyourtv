package com.gathin.news;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DashboardSnapshot {
    enum ContentKind { HEADLINE, TEAM, UPCOMING_MOVIE, UPCOMING_SERIES }
    enum MatchPhase { UPCOMING, LIVE, FINAL, UNAVAILABLE }
    enum Freshness { FRESH, STALE, UNAVAILABLE }

    static final class SourceStatus {
        final Freshness freshness;
        final long updatedMs;
        final boolean cached;

        SourceStatus(Freshness freshness, long updatedMs, boolean cached) {
            this.freshness = freshness;
            this.updatedMs = updatedMs;
            this.cached = cached;
        }
    }

    static final class StorySlot {
        final String slotId;
        final String contentIdentity;
        final String title;
        final String source;
        final String destinationUrl;
        final long publishedMs;
        final ContentKind contentKind;
        final boolean cached;
        final boolean stale;

        StorySlot(String slotId, String contentIdentity, String title, String source,
                  String destinationUrl, long publishedMs, ContentKind contentKind,
                  boolean cached, boolean stale) {
            this.slotId = slotId;
            this.contentIdentity = contentKind == null ? null
                    : contentIdentity == null || contentIdentity.isEmpty()
                    ? identity(destinationUrl, source, title, publishedMs) : contentIdentity;
            this.title = title;
            this.source = source;
            this.destinationUrl = destinationUrl;
            this.publishedMs = publishedMs;
            this.contentKind = contentKind;
            this.cached = cached;
            this.stale = stale;
        }

        StorySlot withSlotId(String id) {
            return new StorySlot(id, contentIdentity, title, source, destinationUrl,
                    publishedMs, contentKind, cached, stale);
        }

        static StorySlot vacant(String id) {
            return new StorySlot(id, null, null, null, null, 0, null, false, false);
        }

        boolean isVacant() {
            return contentKind == null;
        }

        private static String identity(String url, String source, String title, long publishedMs) {
            if (url != null && !url.isBlank()) return url.trim();
            return normalize(source) + "|" + normalize(title) + "|" + publishedMs;
        }

        private static String normalize(String text) {
            return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }
    }

    static final class MatchSlot {
        final String fixtureKey;
        final String topLine;
        final MatchPhase phase;
        final String scoreOrCountdown;
        final long startMs;
        final long endMs;
        final int colorArgb;
        final Freshness freshness;

        MatchSlot(String fixtureKey, String topLine, MatchPhase phase, String scoreOrCountdown,
                  long startMs, long endMs, int colorArgb, Freshness freshness) {
            if (phase == MatchPhase.LIVE && freshness != Freshness.FRESH)
                throw new IllegalArgumentException("live matches require fresh scores");
            this.fixtureKey = fixtureKey;
            this.topLine = topLine;
            this.phase = phase;
            this.scoreOrCountdown = scoreOrCountdown;
            this.startMs = startMs;
            this.endMs = endMs;
            this.colorArgb = colorArgb;
            this.freshness = freshness;
        }
    }

    final List<StorySlot> stories;
    final List<MatchSlot> matches;
    final Map<String, SourceStatus> sources;

    DashboardSnapshot(List<StorySlot> stories, List<MatchSlot> matches,
                      Map<String, SourceStatus> sources) {
        this.stories = Collections.unmodifiableList(new ArrayList<>(stories));
        this.matches = Collections.unmodifiableList(new ArrayList<>(matches));
        this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    }
}

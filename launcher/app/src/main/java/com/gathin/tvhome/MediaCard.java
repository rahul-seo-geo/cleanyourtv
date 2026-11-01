package com.gathin.tvhome;

/** One card in the band. Title is always present; every other text field may be "". */
final class MediaCard {
    enum Kind { MOVIE, SERIES, SPORTS, LIVE }
    enum State { AVAILABLE, REQUESTED, APPROVED, UPCOMING, LIVE, FINAL, UNAVAILABLE }

    /** Stable identity for in-place view updates; sources derive it deterministically. */
    final String id;
    final Kind kind;
    final String title;
    /** Supporting line: status, channel group, or the turn's word ("Puck drop"). */
    final String line;
    final State state;
    /** Anchor time (release, kickoff); 0 when the card has none. */
    final long whenMs;
    /** ARGB accent: the Game Day bar colour, or 0. */
    final int accent;
    /** Seerr media id for Jellyfin resolution; "" when none. */
    final String tmdbId;
    /** Stream URL for LIVE channels; "" when none. */
    final String streamUrl;

    MediaCard(String id, Kind kind, String title, String line, State state,
              long whenMs, int accent, String tmdbId, String streamUrl) {
        this.id = id;
        this.kind = kind;
        this.title = title;
        this.line = line == null ? "" : line;
        this.state = state;
        this.whenMs = whenMs;
        this.accent = accent;
        this.tmdbId = tmdbId == null ? "" : tmdbId;
        this.streamUrl = streamUrl == null ? "" : streamUrl;
    }
}

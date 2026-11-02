package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** TheSportsDB next events for followed teams. Defensive parsing only: a bad entry is skipped, never fatal. */
final class SportsDbSource {
    private static final String HOST = "https://www.thesportsdb.com";
    /** The hero window: kickoffs from three hours ago to a week out. */
    private static final long HERO_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;
    /** An event without a full-time status is FINAL once its kickoff is this far in the past. */
    private static final long FINAL_GRACE_MS = 3L * 60 * 60 * 1000;
    private static final DateTimeFormatter KICKOFF_LINE =
            DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.ENGLISH);

    private SportsDbSource() { }

    static String eventsUrl(String apiKey, String teamId) {
        return HOST + "/api/v1/json/" + apiKey + "/eventsnextteam.php?id=" + teamId;
    }

    /** "/pic/x.png" to an absolute URL; "" when missing. */
    static String absoluteBadge(String badge) {
        if (badge == null || badge.isEmpty() || badge.startsWith("//")) return "";
        return badge.startsWith("/") ? HOST + badge : badge;
    }

    /** dateEvent + strTime as epoch ms (UTC); 0 when either part will not parse. */
    static long kickoffMs(String date, String time) {
        if (date == null || time == null) return 0;
        String t = time.trim();
        if (t.length() < 8) return 0;
        try {
            return LocalDateTime.parse(date.trim() + "T" + t.substring(0, 8))
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    static MediaCard.State state(String status, long kickoffMs, long nowMs) {
        if ("FT".equalsIgnoreCase(status) || "AET".equalsIgnoreCase(status)
                || "PEN".equalsIgnoreCase(status)) return MediaCard.State.FINAL;
        if (kickoffMs > 0 && kickoffMs + FINAL_GRACE_MS < nowMs) return MediaCard.State.FINAL;
        if (kickoffMs > 0 && kickoffMs <= nowMs) return MediaCard.State.LIVE;
        return MediaCard.State.UPCOMING;
    }

    /** Select fixtures in the hero window; LIVE first, then kickoff ascending, capped after sorting. */
    static List<MediaCard> heroCards(Collection<MediaCard> cards, long nowMs, int limit) {
        List<MediaCard> selected = new ArrayList<>();
        for (MediaCard card : cards) {
            if (card.whenMs > 0 && card.whenMs >= nowMs - FINAL_GRACE_MS
                    && card.whenMs <= nowMs + HERO_WINDOW_MS) selected.add(card);
        }
        selected.sort(Comparator.comparingInt((MediaCard card) -> card.state == MediaCard.State.LIVE ? 0 : 1)
                .thenComparingLong(card -> card.whenMs));
        return selected.subList(0, Math.min(limit, selected.size()));
    }

    /** League and kickoff for the card's supporting line; a part that is missing is omitted. */
    static String line(String league, long kickoffMs) {
        String when = kickoffMs > 0
                ? KICKOFF_LINE.format(java.time.Instant.ofEpochMilli(kickoffMs).atZone(ZoneId.systemDefault()))
                : "";
        if (league.isEmpty()) return when;
        return when.isEmpty() ? league : league + " · " + when;
    }

    /** The followed team's next events; the opponent (the side that is not the team) is the card. */
    static List<MediaCard> parseTeam(byte[] body, String teamId, long nowMs) {
        List<MediaCard> cards = new ArrayList<>();
        JSONObject page = object(body);
        JSONArray events = page == null ? null : page.optJSONArray("events");
        if (events == null && page != null && page.has("idEvent")) events = new JSONArray().put(page);
        if (events == null) return cards;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            String idEvent = event.optString("idEvent", "");
            if (idEvent.isEmpty()) continue;
            // Without idHomeTeam, a non-matching idAwayTeam defaults to the away side as opponent.
            boolean followedIsAway = teamId.equals(event.optString("idAwayTeam", ""));
            String opponent = event.optString(followedIsAway ? "strHomeTeam" : "strAwayTeam", "");
            if (opponent.isEmpty()) continue;
            long kickoff = kickoffMs(event.optString("dateEvent", ""), event.optString("strTime", ""));
            String badge = absoluteBadge(event.optString(followedIsAway ? "strHomeTeamBadge" : "strAwayTeamBadge", ""));
            if (badge.isEmpty()) {
                badge = absoluteBadge(event.optString(followedIsAway ? "strAwayTeamBadge" : "strHomeTeamBadge", ""));
            }
            cards.add(new MediaCard("sdb-" + idEvent, MediaCard.Kind.SPORTS, opponent,
                    line(event.optString("strLeague", ""), kickoff),
                    state(event.optString("strStatus", ""), kickoff, nowMs),
                    kickoff, 0, "", "", badge));
        }
        return cards;
    }

    private static JSONObject object(byte[] body) {
        try {
            return new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}

package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Seerr requests and upcoming releases. Defensive parsing only: a bad entry is skipped, never fatal. */
final class SeerrSource {
    private static final int MAX_TITLE = 96;
    private static final String TMDB_POSTER = "https://image.tmdb.org/t/p/w342";

    static Map<String, String> headers(String apiKey) { return Map.of("X-Api-Key", apiKey); }

    static String requestsUrl(String base) { return base + "/api/v1/request"; }

    /** "/xx.jpg" to a full w342 URL; "" when missing or not a root-relative path. */
    static String posterUrl(String posterPath) {
        return posterPath != null && posterPath.startsWith("/") && posterPath.length() > 1
                && !posterPath.startsWith("//") ? TMDB_POSTER + posterPath : "";
    }

    static String detailUrl(String base, MediaCard.Kind kind, String tmdbId) {
        return base + "/api/v1/" + (kind == MediaCard.Kind.SERIES ? "tv" : "movie") + "/" + tmdbId;
    }

    static String parsePoster(byte[] body) {
        JSONObject detail = object(body);
        return detail == null ? "" : posterUrl(detail.optString("posterPath", ""));
    }

    static String upcomingUrl(String base, MediaCard.Kind kind, int limit) {
        return base + (kind == MediaCard.Kind.SERIES
                ? "/api/v1/discover/tv/upcoming" : "/api/v1/discover/movies/upcoming") + "?limit=" + limit;
    }

    static List<MediaCard> parseRequests(byte[] body) {
        List<MediaCard> cards = new ArrayList<>();
        JSONArray entries = array(body);
        if (entries == null) {
            JSONObject page = object(body);
            entries = page == null ? null : page.optJSONArray("results");
        }
        if (entries == null) return cards;
        for (int i = 0; i < entries.length() && cards.size() < 64; i++) {
            JSONObject entry = entries.optJSONObject(i);
            JSONObject media = entry == null ? null : entry.optJSONObject("media");
            if (media == null) continue;
            String tmdb = String.valueOf(media.optLong("tmdbId", 0));
            String title = bound(media.optString("title", ""));
            if (title.isEmpty()) title = "TMDB " + tmdb;
            MediaCard.Kind kind = "tv".equals(media.optString("mediaType"))
                    ? MediaCard.Kind.SERIES : MediaCard.Kind.MOVIE;
            cards.add(new MediaCard("tmdb:" + tmdb, kind, title, "", state(entry.optInt("status", -1)),
                    0, 0, tmdb, "", ""));
        }
        return cards;
    }

    static List<MediaCard> parseUpcoming(byte[] body, MediaCard.Kind kind) {
        List<MediaCard> cards = new ArrayList<>();
        JSONObject page = object(body);
        JSONArray entries = page == null ? null : page.optJSONArray("results");
        if (entries == null) return cards;
        for (int i = 0; i < entries.length() && cards.size() < 32; i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String tmdb = String.valueOf(entry.optLong("id", 0));
            String title = bound(entry.optString(kind == MediaCard.Kind.SERIES ? "name" : "title", ""));
            if (title.isEmpty()) continue;
            long when = epochDay(entry.optString(kind == MediaCard.Kind.SERIES ? "firstAirDate" : "releaseDate",
                    entry.optString(kind == MediaCard.Kind.SERIES ? "first_air_date" : "release_date", "")));
            cards.add(new MediaCard("tmdb:" + tmdb, kind, title, "", MediaCard.State.UPCOMING,
                    when, 0, tmdb, "", posterUrl(entry.optString("posterPath", ""))));
        }
        return cards;
    }

    private static MediaCard.State state(int code) {
        switch (code) {
            case 1: return MediaCard.State.REQUESTED;
            case 2: return MediaCard.State.APPROVED;
            case 5: return MediaCard.State.AVAILABLE;
            default: return MediaCard.State.UNAVAILABLE;
        }
    }

    /** "2027-01-15" to epoch ms (UTC); 0 when the text is not a plain yyyy-MM-dd date. */
    static long epochDay(String text) {
        if (text == null || text.length() != 10 || text.charAt(4) != '-' || text.charAt(7) != '-') return 0;
        try {
            return java.time.LocalDate.of(Integer.parseInt(text.substring(0, 4)),
                    Integer.parseInt(text.substring(5, 7)), Integer.parseInt(text.substring(8, 10)))
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return 0;
        }
    }

    private static JSONArray array(byte[] body) {
        try {
            return new JSONArray(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject object(byte[] body) {
        try {
            return new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static String bound(String value) {
        return value.length() <= MAX_TITLE ? value : value.substring(0, MAX_TITLE - 1) + "…";
    }
}

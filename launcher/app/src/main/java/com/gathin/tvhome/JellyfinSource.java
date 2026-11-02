package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Jellyfin Continue Watching and latest-added items. Defensive parsing only: a bad entry is skipped, never fatal. */
final class JellyfinSource {
    private JellyfinSource() { }

    static Map<String, String> headers(String apiKey) {
        return Map.of("Authorization", "MediaBrowser Token=\"" + apiKey + "\"");
    }

    /** One /Users call per refresh: the configured user name wins (case-insensitive), otherwise the first user. */
    static String resolveUser(MediaLoader.Fetcher fetcher, String base, String apiKey, String userName) {
        byte[] body = fetcher.get(base + "/Users", headers(apiKey));
        JSONArray users = array(body);
        if (users == null) return null;
        String first = null;
        for (int i = 0; i < users.length(); i++) {
            JSONObject user = users.optJSONObject(i);
            if (user == null) continue;
            String id = user.optString("Id", "");
            if (id.isEmpty()) continue;
            if (first == null) first = id;
            if (userName != null && !userName.isEmpty()
                    && userName.equalsIgnoreCase(user.optString("Name", ""))) return id;
        }
        return first;
    }

    static String continueUrl(String base, String userId, int limit) {
        return base + "/Users/" + userId
                + "/Items?Recursive=true&Filters=IsResumable&SortBy=LastPlayedDate&SortOrder=Descending&Limit=" + limit;
    }

    static String nextUpUrl(String base, String userId, int limit) {
        return base + "/Shows/NextUp?userId=" + userId + "&Limit=" + limit;
    }

    /** The Latest endpoint answers a bare JSON array, unlike the Items envelope. */
    static String latestUrl(String base, String userId, int limit) {
        return base + "/Users/" + userId + "/Items/Latest?Limit=" + limit;
    }

    static String latestEpisodesUrl(String base, String userId, int limit) {
        return base + "/Users/" + userId + "/Items/Latest?Limit=" + limit + "&IncludeItemTypes=Episode";
    }

    static String posterUrl(String base, String itemId) {
        return base + "/Items/" + itemId + "/Images/Primary?maxWidth=342&quality=90";
    }

    static List<MediaCard> parseItems(byte[] body, String base, int limit) {
        JSONObject page = object(body);
        return cards(page == null ? null : page.optJSONArray("Items"), base, limit);
    }

    static List<MediaCard> parseNextUp(byte[] body, String base, int limit) {
        JSONObject page = object(body);
        return episodeCards(page == null ? null : page.optJSONArray("Items"), base, limit, false);
    }

    static List<MediaCard> parseNewEpisodes(byte[] body, String base, int limit) {
        return episodeCards(array(body), base, limit, true);
    }

    static List<MediaCard> parseLatest(byte[] body, String base, int limit) {
        return cards(array(body), base, limit);
    }

    private static List<MediaCard> episodeCards(JSONArray items, String base, int limit, boolean deduplicateBySeries) {
        List<MediaCard> cards = new ArrayList<>();
        if (items == null) return cards;
        java.util.Set<String> seenSeries = new java.util.HashSet<>();
        for (int i = 0; i < items.length() && cards.size() < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("Id", "");
            String seriesId = item.optString("SeriesId", "");
            String seriesName = item.optString("SeriesName", "");
            if (id.isEmpty()) continue;
            if (seriesName.isEmpty()) seriesName = item.optString("Name", "");
            if (seriesName.isEmpty()) continue;
            if (deduplicateBySeries && !seriesId.isEmpty()) {
                if (!seenSeries.add(seriesId)) continue;
            }
            String line = parseEpisodeLine(item);
            if (deduplicateBySeries) line = line.isEmpty() ? "New" : "New: " + line;
            String artId = seriesId.isEmpty() ? id : seriesId;
            cards.add(new MediaCard("jf-" + id, MediaCard.Kind.SERIES, seriesName,
                    line, MediaCard.State.AVAILABLE, 0, 0, "", "", posterUrl(base, artId)));
        }
        return cards;
    }

    private static String parseEpisodeLine(JSONObject item) {
        int season = item.optInt("ParentIndexNumber", 0);
        int episode = item.optInt("IndexNumber", 0);
        String epName = item.optString("Name", "");
        StringBuilder sb = new StringBuilder();
        if (season > 0 && episode > 0) {
            sb.append("S").append(season).append(" E").append(episode);
        } else if (season > 0) {
            sb.append("S").append(season);
        } else if (episode > 0) {
            sb.append("E").append(episode);
        }
        if (!epName.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(epName);
        }
        return sb.toString();
    }

    private static List<MediaCard> cards(JSONArray items, String base, int limit) {
        List<MediaCard> cards = new ArrayList<>();
        if (items == null) return cards;
        for (int i = 0; i < items.length() && cards.size() < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("Id", "");
            String title = item.optString("Name", "");
            if (id.isEmpty() || title.isEmpty()) continue;
            String word;
            MediaCard.Kind kind;
            if ("Movie".equals(item.optString("Type", ""))) {
                kind = MediaCard.Kind.MOVIE;
                word = "Movie";
            } else if ("Series".equals(item.optString("Type", ""))) {
                kind = MediaCard.Kind.SERIES;
                word = "Series";
            } else {
                continue;
            }
            int year = item.optInt("ProductionYear", 0);
            cards.add(new MediaCard("jf-" + id, kind, title,
                    year > 0 ? year + " · " + word : word,
                    MediaCard.State.AVAILABLE, 0, 0, "", "", posterUrl(base, id)));
        }
        return cards;
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
}

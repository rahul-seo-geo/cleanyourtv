package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Map;

/** Resolves a TMDB id to a Jellyfin item id; null when the item is absent or the server unreachable. */
final class JellyfinResolve {
    private JellyfinResolve() { }

    static String itemId(MediaLoader.Fetcher fetcher, String base, String apiKey, String tmdbId) {
        if (base.isEmpty() || apiKey.isEmpty() || tmdbId == null || tmdbId.isEmpty()) return null;
        String url = base + "/Items?Recursive=true&AnyProviderIdEquals=Tmdb." + tmdbId + "&Limit=1";
        byte[] body = fetcher.get(url,
                Map.of("Authorization", "MediaBrowser Token=\"" + apiKey + "\""));
        if (body == null) return null;
        try {
            JSONArray items = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                    .optJSONArray("Items");
            if (items == null || items.length() == 0) return null;
            String id = items.optJSONObject(0).optString("Id", "");
            return id.isEmpty() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }
}

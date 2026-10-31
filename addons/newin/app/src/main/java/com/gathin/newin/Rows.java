package com.gathin.newin;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class Rows {
    static final class Item {
        final String title, subtitle, poster, intent;
        final int progress;
        Item(String title, String subtitle, String poster, String intent, int progress) {
            this.title = title; this.subtitle = subtitle; this.poster = poster; this.intent = intent; this.progress = progress;
        }
    }
    static final class Shelf {
        final String title, source;
        final List<Item> items;
        String message = "";
        Shelf(String title, String source, List<Item> items) { this.title = title; this.source = source; this.items = items; }
    }

    static List<Shelf> latest(String json, String base) throws Exception {
        List<Item> movies = new ArrayList<>(), series = new ArrayList<>();
        JSONArray array = new JSONArray(json);
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("Type");
            if (type.equals("Movie")) movies.add(jellyfin(item, base, false));
            else if (type.equals("Series") || type.equals("Episode")) series.add(jellyfin(item, base, false));
        }
        List<Shelf> shelves = new ArrayList<>();
        if (!movies.isEmpty()) shelves.add(new Shelf("Movies", "latest", movies));
        if (!series.isEmpty()) shelves.add(new Shelf("Series", "latest", series));
        return shelves;
    }

    static List<Shelf> resume(String json, String base) throws Exception {
        JSONArray array = json.trim().startsWith("[") ? new JSONArray(json) : new JSONObject(json).optJSONArray("Items");
        if (array == null) return List.of();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) items.add(jellyfin(item, base, true));
        }
        return items.isEmpty() ? List.of() : List.of(new Shelf("Continue watching", "resume", items));
    }

    private static Item jellyfin(JSONObject object, String base, boolean resume) {
        String id = object.optString("Id");
        int progress = resume ? (int) Math.max(0, Math.min(100, object.optJSONObject("UserData") == null ? 0
                : object.optJSONObject("UserData").optDouble("PlayedPercentage", 0))) : -1;
        return new Item(object.optString("Name", "Untitled"), object.optString("SeriesName"),
                id.isEmpty() ? "" : base + "/Items/" + id + "/Images/Primary?maxWidth=400", "jellyfin", progress);
    }

    static String badge(int status) {
        switch (status) {
            case 1: return "Pending";
            case 2: return "Approved";
            case 3: return "Processing";
            case 4: return "Available";
            default: return "Unknown";
        }
    }

    static List<Shelf> seerr(String json) throws Exception {
        JSONArray array = new JSONObject(json).optJSONArray("results");
        if (array == null) return List.of();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject request = array.optJSONObject(i);
            if (request == null) continue;
            JSONObject media = request.optJSONObject("media");
            if (media == null) continue;
            String path = media.optString("posterPath");
            String title = seerrTitle(request);
            items.add(new Item(title, badge(request.optInt("status")),
                    path.startsWith("/") ? "https://image.tmdb.org/t/p/w342" + path : "", "", -1));
        }
        return items.isEmpty() ? List.of() : List.of(new Shelf("On request", "seerr", items));
    }

    static String seerrTitle(JSONObject request) {
        JSONObject media = request.optJSONObject("media");
        if (media == null) return "Media request";
        JSONObject info = media.optJSONObject("info");
        JSONObject subject = request.optJSONObject("subject");
        String[] candidates = {
                media.optString("title"), media.optString("name"),
                info == null ? "" : info.optString("title"),
                info == null ? "" : info.optString("name"),
                subject == null ? "" : subject.optString("title"),
                subject == null ? "" : subject.optString("name"),
                request.optString("title")
        };
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty() && !"null".equals(candidate)) return candidate.trim();
        }
        String type = media.optString("mediaType", request.optString("type"));
        if ("tv".equalsIgnoreCase(type) || "season".equalsIgnoreCase(type)) return "TV request";
        if ("movie".equalsIgnoreCase(type)) return "Movie request";
        return "Media request";
    }

    static boolean ownTv(String packageName, String intent) {
        return "tv.own.owntv".equals(packageName) && intent != null && !intent.isEmpty();
    }
}

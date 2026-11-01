package com.gathin.news;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

final class Slides {
    enum Kind { HEADLINE, TEAM, UPCOMING_MOVIE, UPCOMING_SERIES }

    static final class Slide {
        Kind kind;
        String title;
        String source;
        long dateMs;
        String posterUrl;
        String link;
    }

    private static String seerrUrl = "";
    private static String seerrKey = "";

    static void setSeerr(String url, String key) { seerrUrl = url.replaceAll("/+$", ""); seerrKey = key; }

    static List<Slide> fromSnapshot(DashboardSnapshot snapshot) {
        List<Slide> slides = new ArrayList<>();
        for (DashboardSnapshot.StorySlot slot : snapshot.stories) {
            if (slot.isVacant()) continue;
            Slide slide = new Slide();
            slide.kind = Kind.valueOf(slot.contentKind.name());
            slide.title = slot.title;
            slide.source = slot.source;
            slide.dateMs = slot.publishedMs;
            if (slide.kind == Kind.UPCOMING_MOVIE || slide.kind == Kind.UPCOMING_SERIES)
                slide.posterUrl = slot.destinationUrl;
            else slide.link = slot.destinationUrl;
            slides.add(slide);
        }
        return slides;
    }

    static List<Slide> merge(List<Feeds.Item> abc, List<Feeds.Item> bbc,
                             Map<String, List<Feeds.Item>> teamFeeds, String upcomingJson) {
        List<List<Slide>> sections = new ArrayList<>();
        sections.add(feed(abc, "ABC News", Kind.HEADLINE));
        sections.add(feed(bbc, "BBC News", Kind.HEADLINE));
        sections.add(feed(teamFeeds.get("Arsenal"), "Arsenal", Kind.TEAM));
        sections.add(feed(teamFeeds.get("Real Madrid"), "Real Madrid", Kind.TEAM));
        List<Slide> movies = new ArrayList<>(), series = new ArrayList<>();
        if (upcomingJson != null && !upcomingJson.isBlank()) {
            try {
                JSONObject json = new JSONObject(upcomingJson);
                upcoming(json.optJSONArray("movies"), movies, Kind.UPCOMING_MOVIE, "title", "releaseDate", "Upcoming film");
                upcoming(json.optJSONArray("tv"), series, Kind.UPCOMING_SERIES, "name", "firstAirDate", "Upcoming series");
            } catch (Exception ignored) { movies.clear(); series.clear(); }
        }
        sections.add(trim(movies));
        sections.add(trim(series));
        List<Slide> result = new ArrayList<>();
        for (int i = 0; i < 6; i++) for (List<Slide> section : sections) if (i < section.size()) result.add(section.get(i));
        return result;
    }

    private static List<Slide> feed(List<Feeds.Item> items, String source, Kind kind) {
        List<Slide> slides = new ArrayList<>();
        if (items == null) return slides;
        for (Feeds.Item item : items) {
            if (item.title == null || item.title.isBlank()) continue;
            Slide slide = new Slide();
            slide.kind = kind; slide.title = item.title; slide.source = source;
            slide.dateMs = item.pubDateMs; slide.link = item.link;
            slides.add(slide);
        }
        return trim(slides);
    }

    private static List<Slide> trim(List<Slide> slides) {
        slides.sort(Comparator.comparingLong((Slide s) -> s.dateMs).reversed());
        return new ArrayList<>(slides.subList(0, Math.min(6, slides.size())));
    }

    private static void upcoming(JSONArray entries, List<Slide> slides, Kind kind, String titleKey,
                                 String dateKey, String source) {
        if (entries == null) return;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || entry.optString(titleKey).isBlank()) continue;
            Slide slide = new Slide();
            slide.kind = kind; slide.title = entry.optString(titleKey); slide.source = source;
            try { slide.dateMs = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(entry.optString(dateKey)).getTime(); }
            catch (ParseException ignored) { slide.dateMs = 0; }
            String path = entry.optString("posterPath");
            if (!path.isBlank() && !seerrUrl.isBlank() && !seerrKey.isBlank())
                slide.posterUrl = seerrUrl + "/image?poster_path=" + path + "&api_key=" + seerrKey + "&width=400";
            slides.add(slide);
        }
    }
}

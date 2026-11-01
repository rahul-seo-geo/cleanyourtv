package com.gathin.news;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

final class DashboardLoader {
    interface Fetcher { byte[] get(String url, Map<String, String> headers); }
    interface Store {
        byte[] read(String key);
        void put(String key, byte[] value);
        long ageSeconds(String key);
    }
    interface Games { GameDayReader.GameDayResult read(String pkg, long nowMs); }

    private static final String[] DEFAULT_URLS = {
            "https://www.abc.net.au/news/feed/51120/rss.xml",
            "https://feeds.bbci.co.uk/news/rss.xml",
            "https://feeds.bbci.co.uk/sport/football/teams/arsenal/rss.xml",
            "https://feeds.bbci.co.uk/sport/football/teams/real-madrid/rss.xml"};
    private static final String[] DEFAULT_NAMES = {"ABC News", "BBC News", "Arsenal", "Real Madrid"};
    private static final long STORY_MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final DashboardSlots.Policy STORY_POLICY = new DashboardSlots.Policy(6, STORY_MAX_AGE_MS);
    private final Fetcher fetcher;
    private final Store store;
    private final Games games;

    DashboardLoader(Fetcher fetcher, Store store, Games games) {
        this.fetcher = fetcher;
        this.store = store;
        this.games = games;
    }

    DashboardSnapshot load(DashboardSnapshot previous, Settings settings, long nowMs) {
        Map<String, DashboardSnapshot.SourceStatus> statuses = new LinkedHashMap<>();
        List<DashboardSnapshot.StorySlot> candidates = new ArrayList<>();
        List<String> keys = settings.keysWithPrefix("feed");
        boolean defaults = keys.isEmpty();
        if (defaults) keys = List.of("feed1", "feed2", "feed3", "feed4");
        for (int i = 0; i < keys.size() && !Thread.currentThread().isInterrupted(); i++) {
            String key = keys.get(i);
            String value = defaults ? DEFAULT_URLS[i] + "|" + DEFAULT_NAMES[i] : settings.get(key);
            int separator = value.indexOf('|');
            if (separator <= 0) continue;
            String name = value.substring(separator + 1).trim();
            String url = value.substring(0, separator).trim();
            String identityKey = key + "-url";
            boolean sameSource = url.equals(readIdentity(identityKey));
            byte[] response = fetcher.get(url, Collections.emptyMap());
            List<Feeds.Item> items = parseFeed(response);
            boolean fresh = !items.isEmpty();
            if (fresh) {
                store.put(key, response);
                store.put(identityKey, url.getBytes(StandardCharsets.UTF_8));
            } else if (sameSource) items = parseFeed(store.read(key));
            long updated = fresh ? nowMs : sameSource ? cachedTime(key, previous, nowMs) : 0;
            if (items.isEmpty() && !fresh) {
                if (sameSource && previousStatus(previous, key) > 0) {
                    for (DashboardSnapshot.StorySlot slot : priorStories(previous, name, kind(name)))
                        candidates.add(stale(slot));
                    updated = previousStatus(previous, key);
                } else updated = 0;
            } else {
                for (Feeds.Item item : items) {
                    if (item.title == null || item.title.isBlank()) continue;
                    candidates.add(new DashboardSnapshot.StorySlot(null, null, item.title, name,
                            item.link, item.pubDateMs, kind(name), !fresh, !fresh));
                }
            }
            statuses.put(key, new DashboardSnapshot.SourceStatus(fresh ? DashboardSnapshot.Freshness.FRESH
                    : updated > 0 ? DashboardSnapshot.Freshness.STALE : DashboardSnapshot.Freshness.UNAVAILABLE,
                    updated, !fresh && updated > 0));
        }
        String base = settings.get("jellyseerr_url").replaceAll("/+$", "");
        String key = settings.get("jellyseerr_key");
        if (!base.isBlank() && !key.isBlank() && !Thread.currentThread().isInterrupted()) {
            Slides.setSeerr(base, key);
            Map<String, String> headers = Map.of("X-Api-Key", key);
            addMedia(previous, statuses, candidates, "upcoming-movies", "Upcoming film",
                    DashboardSnapshot.ContentKind.UPCOMING_MOVIE, base + "/api/v1/discover/movies/upcoming", headers, nowMs);
            addMedia(previous, statuses, candidates, "upcoming-tv", "Upcoming series",
                    DashboardSnapshot.ContentKind.UPCOMING_SERIES, base + "/api/v1/discover/tv/upcoming", headers, nowMs);
        }
        String provider = settings.get("game_day").trim();
        GameDayReader.GameDayResult result = games.read(provider, nowMs);
        boolean sameProvider = !provider.isEmpty() && provider.equals(readIdentity("game_day-provider"));
        List<DashboardSnapshot.MatchSlot> matches = new ArrayList<>();
        if (result != null && result.state != GameDayReader.State.UNAVAILABLE) {
            matches.addAll(result.matches);
            if (!provider.isEmpty()) store.put("game_day-provider", provider.getBytes(StandardCharsets.UTF_8));
            statuses.put("game_day", new DashboardSnapshot.SourceStatus(DashboardSnapshot.Freshness.FRESH, nowMs, false));
        } else {
            if (sameProvider && previous != null) for (DashboardSnapshot.MatchSlot match : previous.matches) {
                if (match.endMs <= nowMs) continue;
                matches.add(new DashboardSnapshot.MatchSlot(match.fixtureKey, match.topLine,
                        match.phase == DashboardSnapshot.MatchPhase.LIVE ? DashboardSnapshot.MatchPhase.UNAVAILABLE : match.phase,
                        null, match.startMs, match.endMs, match.colorArgb, DashboardSnapshot.Freshness.STALE));
            }
            long updated = matches.isEmpty() ? 0 : previousStatus(previous, "game_day");
            statuses.put("game_day", new DashboardSnapshot.SourceStatus(matches.isEmpty()
                    ? DashboardSnapshot.Freshness.UNAVAILABLE : DashboardSnapshot.Freshness.STALE, updated, !matches.isEmpty()));
        }
        List<DashboardSnapshot.StorySlot> slots = DashboardSlots.reconcile(
                previous == null ? List.of() : previous.stories, candidates, nowMs, STORY_POLICY);
        return new DashboardSnapshot(slots, matches, statuses);
    }

    private void addMedia(DashboardSnapshot previous, Map<String, DashboardSnapshot.SourceStatus> statuses,
                          List<DashboardSnapshot.StorySlot> candidates, String cacheKey, String name,
                          DashboardSnapshot.ContentKind kind, String url, Map<String, String> headers, long nowMs) {
        byte[] response = fetcher.get(url, headers);
        JSONArray entries = results(response);
        boolean fresh = entries != null;
        String cacheHit = cacheKey;
        if (fresh) store.put(cacheKey, response);
        else {
            entries = results(store.read(cacheKey));
            if (entries == null && (entries = legacyEntries(store.read("upcoming"), kind)) != null)
                cacheHit = "upcoming";
        }
        long updated = fresh ? nowMs : cachedTime(cacheHit, previous, nowMs);
        if (entries != null) {
            try {
                JSONObject combined = new JSONObject();
                combined.put(kind == DashboardSnapshot.ContentKind.UPCOMING_MOVIE ? "movies" : "tv", entries);
                List<Slides.Slide> slides = Slides.merge(List.of(), List.of(), Map.of(), combined.toString());
                for (Slides.Slide slide : slides) candidates.add(new DashboardSnapshot.StorySlot(null, null,
                        slide.title, name, slide.posterUrl, slide.dateMs, kind, !fresh, !fresh));
            } catch (Exception ignored) { }
        } else if (previousStatus(previous, cacheKey) > 0) {
            for (DashboardSnapshot.StorySlot slot : priorStories(previous, name, kind)) candidates.add(stale(slot));
            updated = previousStatus(previous, cacheKey);
        }
        statuses.put(cacheKey, new DashboardSnapshot.SourceStatus(fresh ? DashboardSnapshot.Freshness.FRESH
                : updated > 0 ? DashboardSnapshot.Freshness.STALE : DashboardSnapshot.Freshness.UNAVAILABLE,
                updated, !fresh && updated > 0));
    }

    private static JSONArray results(byte[] bytes) {
        if (bytes == null) return null;
        try { return new JSONObject(new String(bytes, StandardCharsets.UTF_8)).optJSONArray("results"); }
        catch (Exception ignored) { return null; }
    }

    private static JSONArray legacyEntries(byte[] bytes, DashboardSnapshot.ContentKind kind) {
        if (bytes == null) return null;
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8))
                    .optJSONArray(kind == DashboardSnapshot.ContentKind.UPCOMING_MOVIE ? "movies" : "tv");
        } catch (Exception ignored) { return null; }
    }

    private static List<Feeds.Item> parseFeed(byte[] bytes) {
        if (bytes == null) return List.of();
        try { return Feeds.parse(new ByteArrayInputStream(bytes)); }
        catch (Exception ignored) { return List.of(); }
    }

    private long cachedTime(String key, DashboardSnapshot previous, long nowMs) {
        long age = store.ageSeconds(key);
        if (age >= 0) return Math.max(1, nowMs - age * 1000);
        return previousStatus(previous, key);
    }

    String readIdentity(String key) {
        byte[] stored = store.read(key);
        return stored == null ? null : new String(stored, StandardCharsets.UTF_8);
    }

    private static long previousStatus(DashboardSnapshot previous, String key) {
        DashboardSnapshot.SourceStatus status = previous == null ? null : previous.sources.get(key);
        return status == null ? 0 : status.updatedMs;
    }

    private static DashboardSnapshot.ContentKind kind(String name) {
        return "ABC News".equals(name) || "BBC News".equals(name)
                ? DashboardSnapshot.ContentKind.HEADLINE : DashboardSnapshot.ContentKind.TEAM;
    }

    private static List<DashboardSnapshot.StorySlot> priorStories(DashboardSnapshot previous, String name,
                                                                   DashboardSnapshot.ContentKind kind) {
        List<DashboardSnapshot.StorySlot> result = new ArrayList<>();
        if (previous != null) for (DashboardSnapshot.StorySlot slot : previous.stories)
            if (!slot.isVacant() && name.equals(slot.source) && slot.contentKind == kind) result.add(slot);
        return result;
    }

    private static DashboardSnapshot.StorySlot stale(DashboardSnapshot.StorySlot slot) {
        return new DashboardSnapshot.StorySlot(null, slot.contentIdentity, slot.title, slot.source,
                slot.destinationUrl, slot.publishedMs, slot.contentKind, true, true);
    }
}

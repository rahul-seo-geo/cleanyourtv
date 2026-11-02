package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

final class MediaLoader {
    private static final int CONTINUE_LIMIT = 8;
    private static final int DOWNLOADS_LIMIT = 8;
    private static final int UPCOMING_LIMIT = 8;
    private static final int SPORTS_POOL_LIMIT = 16;
    private static final int POOL_CAP = 16;

    interface Fetcher { byte[] get(String url, Map<String, String> headers); }
    interface Store { byte[] read(String key); void put(String key, byte[] value); long ageSeconds(String key); }
    interface Listener { void onSnapshot(MediaSnapshot snapshot); }

    private final Fetcher fetcher;
    private final Store store;
    private final LongSupplier clock;
    private final Handler main;
    private final Listener listener;
    private final MediaSettings settings;
    private final long refreshMs;
    private final String jellyUrl;
    private final String jellyKey;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable refreshLoop = this::refresh;
    private MediaSnapshot last = MediaSnapshot.empty();
    // Last-known raw source lists: an unavailable source's pool contribution is rebuilt from these.
    private List<MediaCard> lastContinue = List.of();
    private List<MediaCard> lastDownloads = List.of();
    private List<MediaCard> lastUpcoming = List.of();
    private boolean running;

    MediaLoader(Fetcher fetcher, Store store, LongSupplier clock) {
        this(fetcher, store, clock, null, null, null, 15L * 60 * 1000, "", "");
    }

    private MediaLoader(Fetcher fetcher, Store store, LongSupplier clock, MediaSettings settings,
                        Handler main, Listener listener, long refreshMs, String jellyUrl,
                        String jellyKey) {
        this.fetcher = fetcher;
        this.store = store;
        this.clock = clock;
        this.settings = settings;
        this.main = main;
        this.listener = listener;
        this.refreshMs = refreshMs;
        this.jellyUrl = jellyUrl;
        this.jellyKey = jellyKey;
    }

    static MediaLoader create(Context context, MediaSettings settings, Handler main, Listener listener) {
        return new MediaLoader(new Fetch(), new MediaStore(context), System::currentTimeMillis,
                settings, main, listener,
                settings.refreshMinutes() * 60_000L,
                settings.get("jellyfin_url"), settings.get("jellyfin_key"));
    }

    void start() {
        if (running) return;
        running = true;
        refresh();
    }

    void stop() {
        running = false;
        if (main != null) main.removeCallbacks(refreshLoop);
    }

    void shutdown() {
        stop();
    }

    /** Off-thread Jellyfin resolution; callback fires on the main thread, null when unresolvable. */
    void resolve(String tmdbId, java.util.function.Consumer<String> onResolved) {
        try {
            io.execute(() -> {
                String id = JellyfinResolve.itemId(fetcher, jellyUrl, jellyKey, tmdbId);
                main.post(() -> onResolved.accept(id));
            });
        } catch (RejectedExecutionException e) {
            main.post(() -> onResolved.accept(null));
        }
    }

    private void refresh() {
        if (!running) return;
        try {
            io.execute(() -> {
                MediaSnapshot snapshot = load(settings, last);
                main.post(() -> {
                    if (running) {
                        last = snapshot;
                        listener.onSnapshot(snapshot);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) { }
        main.postDelayed(refreshLoop, refreshMs);
    }

    MediaSnapshot load(MediaSettings settings, MediaSnapshot previous) {
        long now = clock.getAsLong();
        Map<String, MediaSnapshot.SourceState> statuses = new HashMap<>();
        List<MediaCard> continueCards = new ArrayList<>();
        List<MediaCard> downloadCards = new ArrayList<>();
        List<MediaCard> upcomingCards = new ArrayList<>();
        List<MediaCard> sportsPool = new ArrayList<>();

        String seerrUrl = settings.get("seerr_url");
        if (!seerrUrl.isEmpty()) {
            loadSeerr(seerrUrl, settings.get("seerr_key"), now, statuses, upcomingCards);
            if (statuses.get("seerr").freshness == MediaSnapshot.Freshness.UNAVAILABLE
                    && !lastUpcoming.isEmpty()) {
                upcomingCards.addAll(lastUpcoming);
                statuses.put("seerr", new MediaSnapshot.SourceState(MediaSnapshot.Freshness.STALE, now));
            }
        }
        String jellyBase = settings.get("jellyfin_url");
        String jellyApiKey = settings.get("jellyfin_key");
        if (!jellyBase.isEmpty() && !jellyApiKey.isEmpty()) {
            loadJellyfin(jellyBase, jellyApiKey, settings.get("jellyfin_user"), now,
                    statuses, continueCards, downloadCards);
            if (statuses.get("jellyfin").freshness == MediaSnapshot.Freshness.UNAVAILABLE
                    && (!lastContinue.isEmpty() || !lastDownloads.isEmpty())) {
                continueCards.addAll(lastContinue);
                downloadCards.addAll(lastDownloads);
                statuses.put("jellyfin", new MediaSnapshot.SourceState(MediaSnapshot.Freshness.STALE, now));
            }
        }
        String sportsdbTeams = settings.get("sportsdb_teams");
        String sportsdbLeagues = settings.get("sportsdb_leagues");
        if (!settings.get("sportsdb_key").isEmpty()
                && (!sportsdbTeams.isEmpty() || !sportsdbLeagues.isEmpty())) {
            loadSportsDb(settings.get("sportsdb_key"), sportsdbTeams, sportsdbLeagues, now,
                    statuses, sportsPool);
            if (statuses.get("sportsdb").freshness == MediaSnapshot.Freshness.UNAVAILABLE
                    && !previous.sportsPool.isEmpty()) {
                sportsPool.addAll(SportsDbSource.heroCards(previous.sportsPool, now, SPORTS_POOL_LIMIT));
                if (!sportsPool.isEmpty())
                    statuses.put("sportsdb", new MediaSnapshot.SourceState(MediaSnapshot.Freshness.STALE, now));
            }
        }
        lastContinue = MediaSnapshot.dropExpired(continueCards, now);
        lastDownloads = MediaSnapshot.dropExpired(downloadCards, now);
        lastUpcoming = MediaSnapshot.dropExpired(upcomingCards, now);
        List<MediaCard> moviePool = MediaMix.interleave(List.of(
                MediaSnapshot.ofKind(lastContinue, MediaCard.Kind.MOVIE),
                MediaSnapshot.ofKind(lastDownloads, MediaCard.Kind.MOVIE),
                MediaSnapshot.ofKind(lastUpcoming, MediaCard.Kind.MOVIE)), POOL_CAP);
        List<MediaCard> seriesPool = MediaMix.interleave(List.of(
                MediaSnapshot.ofKind(lastContinue, MediaCard.Kind.SERIES),
                MediaSnapshot.ofKind(lastDownloads, MediaCard.Kind.SERIES),
                MediaSnapshot.ofKind(lastUpcoming, MediaCard.Kind.SERIES)), POOL_CAP);
        return new MediaSnapshot(moviePool, seriesPool, sportsPool, statuses);
    }

    private void loadSeerr(String base, String key, long now,
                           Map<String, MediaSnapshot.SourceState> statuses,
                           List<MediaCard> upcomingCards) {
        Map<String, String> headers = SeerrSource.headers(key);
        boolean fresh = false;
        boolean cached = false;
        String[] keys = {"seerr-upcoming-movie", "seerr-upcoming-series"};
        String[] urls = {SeerrSource.upcomingUrl(base, MediaCard.Kind.MOVIE, UPCOMING_LIMIT),
                SeerrSource.upcomingUrl(base, MediaCard.Kind.SERIES, UPCOMING_LIMIT)};
        for (int i = 0; i < keys.length; i++) {
            byte[] body = fetcher.get(urls[i], headers);
            if (body == null) {
                body = store.read(keys[i]);
            } else {
                store.put(keys[i], body);
                fresh = true;
            }
            if (body == null) continue;
            List<MediaCard> cards = SeerrSource.parseUpcoming(body,
                    i == 0 ? MediaCard.Kind.MOVIE : MediaCard.Kind.SERIES);
            if (!fresh && !cards.isEmpty()) cached = true;
            // Movies first, then series, and the two responses together stop at the total limit.
            for (int j = 0; j < cards.size() && upcomingCards.size() < UPCOMING_LIMIT; j++) {
                upcomingCards.add(cards.get(j));
            }
        }
        statuses.put("seerr", new MediaSnapshot.SourceState(fresh
                ? MediaSnapshot.Freshness.FRESH
                : (cached ? MediaSnapshot.Freshness.STALE : MediaSnapshot.Freshness.UNAVAILABLE), now));
    }

    private void loadJellyfin(String base, String apiKey, String userName, long now,
                              Map<String, MediaSnapshot.SourceState> statuses,
                              List<MediaCard> continueCards, List<MediaCard> downloadCards) {
        Map<String, String> headers = JellyfinSource.headers(apiKey);
        String userId = JellyfinSource.resolveUser(fetcher, base, apiKey, userName);
        boolean fresh = false;
        boolean cached = false;
        String[] keys = {"jellyfin-continue", "jellyfin-latest"};
        String[] urls = userId == null ? new String[0] : new String[] {
                JellyfinSource.continueUrl(base, userId, CONTINUE_LIMIT),
                JellyfinSource.latestUrl(base, userId, DOWNLOADS_LIMIT)};
        for (int i = 0; i < urls.length; i++) {
            byte[] body = fetcher.get(urls[i], headers);
            if (body == null) {
                body = store.read(keys[i]);
            } else {
                store.put(keys[i], body);
                fresh = true;
            }
            if (body == null) continue;
            List<MediaCard> cards = i == 0 ? JellyfinSource.parseItems(body, base, CONTINUE_LIMIT)
                    : JellyfinSource.parseLatest(body, base, DOWNLOADS_LIMIT);
            if (!fresh && !cards.isEmpty()) cached = true;
            (i == 0 ? continueCards : downloadCards).addAll(cards);
        }
        statuses.put("jellyfin", new MediaSnapshot.SourceState(fresh
                ? MediaSnapshot.Freshness.FRESH
                : (cached ? MediaSnapshot.Freshness.STALE : MediaSnapshot.Freshness.UNAVAILABLE), now));
    }

    /** One eventsnextteam call per team and one eventsnextleague call per league per refresh. */
    private void loadSportsDb(String apiKey, String teams, String leagues, long now,
                              Map<String, MediaSnapshot.SourceState> statuses,
                              List<MediaCard> sportsPool) {
        Map<String, MediaCard> byEvent = new LinkedHashMap<>();
        boolean fresh = false;
        for (String team : teams.split(",")) {
            team = team.trim();
            if (team.isEmpty()) continue;
            byte[] body = fetcher.get(SportsDbSource.eventsUrl(apiKey, team), Map.of());
            if (body == null) continue;
            fresh = true;
            for (MediaCard card : SportsDbSource.parseTeam(body, team, now)) {
                byEvent.putIfAbsent(card.id, card); // the same fixture can appear for both clubs
            }
        }
        for (String league : leagues.split(",")) {
            league = league.trim();
            if (league.isEmpty()) continue;
            byte[] body = fetcher.get(SportsDbSource.eventsLeagueUrl(apiKey, league), Map.of());
            if (body == null) continue;
            fresh = true;
            for (MediaCard card : SportsDbSource.parseLeague(body, now)) {
                if (byEvent.containsKey(card.id)) continue; // team cards win: opponent title + badge art
                byEvent.put(card.id, card);
            }
        }
        sportsPool.addAll(SportsDbSource.heroCards(byEvent.values(), now, SPORTS_POOL_LIMIT));
        statuses.put("sportsdb", new MediaSnapshot.SourceState(fresh
                ? MediaSnapshot.Freshness.FRESH : MediaSnapshot.Freshness.UNAVAILABLE, now));
    }
}

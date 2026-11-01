package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

final class MediaLoader {
    interface Fetcher { byte[] get(String url, Map<String, String> headers); }
    interface Store { byte[] read(String key); void put(String key, byte[] value); long ageSeconds(String key); }
    interface Sports { List<GameDay.Turn> turns(); }
    interface Listener { void onSnapshot(MediaSnapshot snapshot); }

    private final Fetcher fetcher;
    private final Store store;
    private final Sports sports;
    private final LongSupplier clock;
    private final Handler main;
    private final Listener listener;
    private final MediaSettings settings;
    private final GameDay provider;
    private final long refreshMs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable refreshLoop = this::refresh;
    private MediaSnapshot last = MediaSnapshot.empty();
    private boolean running;

    MediaLoader(Fetcher fetcher, Store store, Sports sports, LongSupplier clock) {
        this(fetcher, store, sports, clock, null, null, null, null, 15L * 60 * 1000);
    }

    private MediaLoader(Fetcher fetcher, Store store, Sports sports, LongSupplier clock,
                        MediaSettings settings, Handler main, Listener listener, GameDay provider,
                        long refreshMs) {
        this.fetcher = fetcher;
        this.store = store;
        this.sports = sports;
        this.clock = clock;
        this.settings = settings;
        this.main = main;
        this.listener = listener;
        this.provider = provider;
        this.refreshMs = refreshMs;
    }

    static MediaLoader create(Context context, MediaSettings settings, Handler main, Listener listener) {
        GameDay provider = GameDay.create(context, settings.get("game_day_pkg"), main, () -> { });
        return new MediaLoader(new Fetch(), new MediaStore(context),
                provider == null ? () -> List.of() : provider::turns,
                System::currentTimeMillis, settings, main, listener, provider,
                settings.refreshMinutes() * 60_000L);
    }

    void start() {
        if (running) return;
        running = true;
        if (provider != null) provider.start();
        refresh();
    }

    void stop() {
        running = false;
        if (main != null) main.removeCallbacks(refreshLoop);
        if (provider != null) provider.stop();
    }

    void shutdown() {
        stop();
        if (provider != null) provider.shutdown();
        io.shutdownNow();
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
        List<MediaCard> movies = new ArrayList<>();
        List<MediaCard> series = new ArrayList<>();
        List<MediaCard> live = new ArrayList<>();
        List<MediaCard> sportsCards = new ArrayList<>();

        String seerrUrl = settings.get("seerr_url");
        String playlistUrl = settings.get("m3u_url");
        if (!seerrUrl.isEmpty()) {
            loadSeerr(seerrUrl, settings.get("seerr_key"), now, statuses, movies, series);
            if (statuses.get("seerr").freshness == MediaSnapshot.Freshness.UNAVAILABLE
                    && (!previous.movies.isEmpty() || !previous.series.isEmpty())) {
                movies.addAll(previous.movies);
                series.addAll(previous.series);
                statuses.put("seerr", new MediaSnapshot.SourceState(MediaSnapshot.Freshness.STALE, now));
            }
        }
        if (!playlistUrl.isEmpty()) {
            loadPlaylist(playlistUrl, now, statuses, live);
            if (statuses.get("playlist").freshness == MediaSnapshot.Freshness.UNAVAILABLE
                    && !previous.live.isEmpty()) {
                live.addAll(previous.live);
                statuses.put("playlist", new MediaSnapshot.SourceState(MediaSnapshot.Freshness.STALE, now));
            }
        }
        if (!settings.get("game_day_pkg").isEmpty()) {
            List<GameDay.Turn> turns = sports.turns();
            sportsCards.addAll(SportsSource.cards(turns, now));
            boolean fresh = !sportsCards.isEmpty();
            if (!fresh && !previous.sports.isEmpty()) sportsCards.addAll(previous.sports);
            statuses.put("sports", new MediaSnapshot.SourceState(fresh
                    ? MediaSnapshot.Freshness.FRESH
                    : (sportsCards.isEmpty() ? MediaSnapshot.Freshness.UNAVAILABLE
                    : MediaSnapshot.Freshness.STALE), now));
        }
        return new MediaSnapshot(MediaSnapshot.dropExpired(movies, now),
                MediaSnapshot.dropExpired(series, now), sportsCards, live, statuses);
    }

    private void loadSeerr(String base, String key, long now,
                           Map<String, MediaSnapshot.SourceState> statuses,
                           List<MediaCard> movies, List<MediaCard> series) {
        Map<String, String> headers = SeerrSource.headers(key);
        boolean fresh = false;
        boolean cached = false;
        String[] keys = {"seerr-request", "seerr-upcoming-movie", "seerr-upcoming-series"};
        String[] urls = {SeerrSource.requestsUrl(base),
                SeerrSource.upcomingUrl(base, MediaCard.Kind.MOVIE),
                SeerrSource.upcomingUrl(base, MediaCard.Kind.SERIES)};
        for (int i = 0; i < keys.length; i++) {
            byte[] body = fetcher.get(urls[i], headers);
            boolean fromCache = body == null;
            if (fromCache) {
                body = store.read(keys[i]);
                if (body != null) cached = true;
            } else {
                store.put(keys[i], body);
                fresh = true;
            }
            if (body == null) continue;
            List<MediaCard> cards = i == 0 ? SeerrSource.parseRequests(body)
                    : SeerrSource.parseUpcoming(body,
                    i == 1 ? MediaCard.Kind.MOVIE : MediaCard.Kind.SERIES);
            if (i == 0 && fromCache && cards.isEmpty()) {
                String text = new String(body, StandardCharsets.UTF_8);
                if (text.endsWith("}]") && text.startsWith("[{")) {
                    cards = SeerrSource.parseRequests(
                            (text.substring(0, text.length() - 1) + "}]").getBytes(StandardCharsets.UTF_8));
                }
            }
            for (MediaCard card : cards) {
                (card.kind == MediaCard.Kind.SERIES ? series : movies).add(card);
            }
        }
        statuses.put("seerr", new MediaSnapshot.SourceState(fresh
                ? MediaSnapshot.Freshness.FRESH
                : (cached ? MediaSnapshot.Freshness.STALE : MediaSnapshot.Freshness.UNAVAILABLE), now));
    }

    private void loadPlaylist(String url, long now,
                              Map<String, MediaSnapshot.SourceState> statuses, List<MediaCard> live) {
        byte[] body = fetcher.get(url, Map.of());
        boolean fresh = body != null;
        if (fresh) store.put("playlist", body);
        else body = store.read("playlist");
        if (body != null) live.addAll(PlaylistSource.parse(new String(body, StandardCharsets.UTF_8), 64));
        statuses.put("playlist", new MediaSnapshot.SourceState(fresh
                ? MediaSnapshot.Freshness.FRESH
                : (body == null ? MediaSnapshot.Freshness.UNAVAILABLE : MediaSnapshot.Freshness.STALE), now));
    }
}

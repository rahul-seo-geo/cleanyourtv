package com.gathin.news;

import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public final class NewsDream extends DreamService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Thread loader;
    private Cache cache;
    private DashboardSnapshot dashboardSnapshot;
    private DashboardView dashboard;
    private boolean active;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            dashboard.setClockText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        active = true;
        cache = new Cache(this);
        dashboard = new DashboardView(this);
        dashboard.setPosterLoader(url ->
                fetchPoster("poster-" + Integer.toHexString(url.hashCode()), url, Collections.emptyMap()));
        setContentView(dashboard);
        handler.post(tick);
        loader = new Thread(this::load, "news-loader");
        loader.start();
    }

    private void load() {
        Settings settings = Settings.read(this);
        DashboardLoader dashboardLoader = new DashboardLoader(Fetch::get, new DashboardLoader.Store() {
            @Override public byte[] read(String cacheKey) { return cache.read(cacheKey); }
            @Override public void put(String cacheKey, byte[] data) { cache.put(cacheKey, data); }
            @Override public long ageSeconds(String cacheKey) { return cache.ageSeconds(cacheKey); }
        }, (pkg, nowMs) -> GameDayReader.read(this, nowMs));
        DashboardSnapshot snapshot =
                dashboardLoader.load(dashboardSnapshot, settings, System.currentTimeMillis());
        if (Thread.currentThread().isInterrupted()) return;
        handler.post(() -> {
            if (!active) return;
            dashboardSnapshot = snapshot;
            dashboard.render(snapshot);
        });
    }

    private byte[] fetchPoster(String cacheKey, String url, Map<String, String> headers) {
        if (Thread.currentThread().isInterrupted()) return cache.read(cacheKey);
        byte[] data = Fetch.get(url, headers);
        if (data != null) cache.put(cacheKey, data);
        return data != null ? data : cache.read(cacheKey);
    }

    @Override public void onDetachedFromWindow() {
        active = false;
        if (loader != null) loader.interrupt();
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}

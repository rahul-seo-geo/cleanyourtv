package com.gathin.news;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class NewsDream extends DreamService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Thread loader;
    private Cache cache;
    private volatile DashboardSnapshot dashboardSnapshot;
    private DashboardView dashboard;
    private boolean active;
    private RefreshScheduler refresh;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            dashboard.setClockText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };
    private final Runnable refreshDue = new Runnable() {
        @Override public void run() {
            if (refresh != null) refresh.refreshDue();
        }
    };
    private final RefreshScheduler.Commands refreshCommands = new RefreshScheduler.Commands() {
        @Override public void runLoad(long requestId) {
            loader = new Thread(() -> load(requestId), "news-loader");
            loader.start();
        }
        @Override public void scheduleRefresh(long delayMs) { handler.postDelayed(refreshDue, delayMs); }
        @Override public void cancelScheduledRefresh() { handler.removeCallbacks(refreshDue); }
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        active = true;
        cache = new Cache(this);
        dashboard = new DashboardView(this);
        dashboard.setPosterLoader(url ->
                fetchPoster("poster-" + Integer.toHexString(url.hashCode()), url, Collections.emptyMap()));
        setContentView(dashboard);
        refresh = new RefreshScheduler(refreshCommands,
                RefreshScheduler.intervalMs(Settings.read(this).get("refresh_minutes")));
        refresh.attach();
        handler.post(tick);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (dashboard != null && dashboard.onDreamKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private void load(long requestId) {
        try {
            Settings settings = Settings.read(this);
            DashboardLoader dashboardLoader = new DashboardLoader(Fetch::get, new DashboardLoader.Store() {
                @Override public byte[] read(String cacheKey) { return cache.read(cacheKey); }
                @Override public void put(String cacheKey, byte[] data) { cache.put(cacheKey, data); }
                @Override public long ageSeconds(String cacheKey) { return cache.ageSeconds(cacheKey); }
            }, (pkg, nowMs) -> GameDayReader.read(this, nowMs));
            DashboardSnapshot snapshot =
                    dashboardLoader.load(dashboardSnapshot, settings, System.currentTimeMillis());
            List<LauncherTargets.Target> configured =
                    LauncherTargets.parse(settings, DashboardView.LAUNCHER_SLOTS);
            List<DashboardView.LaunchItem> launchers = launchItems(LauncherTargets.filter(configured,
                    pkg -> getPackageManager().getLaunchIntentForPackage(pkg) != null));
            if (Thread.currentThread().isInterrupted()) return;
            handler.post(() -> {
                if (!active || !refresh.shouldPublish(requestId)) return;
                dashboardSnapshot = snapshot;
                dashboard.render(snapshot);
                dashboard.setLauncherTargets(launchers, !configured.isEmpty());
            });
        } finally {
            refresh.loadFinished(requestId);
        }
    }

    private List<DashboardView.LaunchItem> launchItems(List<LauncherTargets.Target> targets) {
        PackageManager pm = getPackageManager();
        List<DashboardView.LaunchItem> items = new ArrayList<>();
        for (LauncherTargets.Target target : targets) {
            Intent intent = pm.getLaunchIntentForPackage(target.pkg);
            if (intent == null) continue;
            items.add(new DashboardView.LaunchItem(launchLabel(pm, target), intent));
        }
        return items;
    }

    private static String launchLabel(PackageManager pm, LauncherTargets.Target target) {
        if (target.label != null) return target.label;
        try {
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(target.pkg, 0));
            if (label != null && !label.toString().isEmpty()) return label.toString();
        } catch (PackageManager.NameNotFoundException e) { }
        return target.pkg;
    }

    private byte[] fetchPoster(String cacheKey, String url, Map<String, String> headers) {
        if (Thread.currentThread().isInterrupted()) return cache.read(cacheKey);
        byte[] data = Fetch.get(url, headers);
        if (data != null) cache.put(cacheKey, data);
        return data != null ? data : cache.read(cacheKey);
    }

    @Override public void onDetachedFromWindow() {
        active = false;
        if (refresh != null) refresh.detach();
        if (loader != null) loader.interrupt();
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}

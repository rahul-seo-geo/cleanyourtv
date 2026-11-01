package com.gathin.news;

import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NewsDream extends DreamService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextView clock;
    private FrameLayout current;
    private Thread loader;
    private ExecutorService posters;
    private Cache cache;
    private DashboardSnapshot dashboardSnapshot;
    private List<Slides.Slide> slides = new ArrayList<>();
    private int index;
    private int interval = 18;
    private boolean active;
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (slides.size() > 1) show((index + 1) % slides.size());
            handler.postDelayed(this, interval * 1000L);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        active = true;
        cache = new Cache(this);
        posters = Executors.newSingleThreadExecutor();
        root = new FrameLayout(this);
        root.setBackgroundResource(R.drawable.screen);
        setContentView(root);
        clock = new TextView(this);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(36);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        clockParams.leftMargin = 120; clockParams.topMargin = 40;
        root.addView(clock, clockParams);
        Slides.Slide offline = new Slides.Slide();
        offline.title = "News"; offline.source = "offline — no cached feeds";
        offline.kind = Slides.Kind.HEADLINE;
        slides = List.of(offline);
        show(0);
        handler.post(tick);
        handler.postDelayed(rotate, interval * 1000L);
        loader = new Thread(this::load, "news-loader");
        loader.start();
    }

    private void load() {
        Settings settings = Settings.read(this);
        try { interval = Math.max(8, Math.min(120, Integer.parseInt(settings.get("slide_seconds")))); }
        catch (NumberFormatException ignored) { interval = 18; }
        DashboardLoader dashboardLoader = new DashboardLoader(Fetch::get, new DashboardLoader.Store() {
            @Override public byte[] read(String cacheKey) { return cache.read(cacheKey); }
            @Override public void put(String cacheKey, byte[] data) { cache.put(cacheKey, data); }
            @Override public long ageSeconds(String cacheKey) { return cache.ageSeconds(cacheKey); }
        }, (pkg, nowMs) -> GameDayReader.read(this, nowMs));
        DashboardSnapshot snapshot = dashboardLoader.load(dashboardSnapshot, settings, System.currentTimeMillis());
        if (Thread.currentThread().isInterrupted()) return;
        List<Slides.Slide> loaded = Slides.fromSnapshot(snapshot);
        handler.post(() -> {
            if (!active) return;
            dashboardSnapshot = snapshot;
            if (!loaded.isEmpty()) slides = loaded;
            index = 0;
            show(0);
            handler.removeCallbacks(rotate);
            handler.postDelayed(rotate, interval * 1000L);
        });
    }

    private byte[] fetchCached(String cacheKey, String url, Map<String, String> headers) {
        if (Thread.currentThread().isInterrupted()) return cache.read(cacheKey);
        byte[] data = Fetch.get(url, headers);
        if (data != null) cache.put(cacheKey, data);
        return data != null ? data : cache.read(cacheKey);
    }

    private void show(int position) {
        index = position;
        Slides.Slide slide = slides.get(position);
        FrameLayout next = new FrameLayout(this);
        FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(-1, -1);
        root.addView(next, root.indexOfChild(clock), fill);
        TextView title = new TextView(this);
        title.setText(slide.title);
        title.setTextColor(Color.rgb(242, 247, 252));
        title.setTextSize(48);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        title.setPadding(120, 0, 120, 0);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, 300, Gravity.CENTER_VERTICAL);
        titleParams.rightMargin = slide.posterUrl == null ? 0 : 450;
        next.addView(title, titleParams);
        TextView source = new TextView(this);
        String meta = slide.source;
        if ((slide.kind == Slides.Kind.UPCOMING_MOVIE || slide.kind == Slides.Kind.UPCOMING_SERIES) && slide.dateMs > 0) {
            meta += " · releases " + new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(slide.dateMs));
        } else {
            long age = slide.dateMs > 0 ? Math.max(0, (System.currentTimeMillis() - slide.dateMs) / 1000) : -1;
            if (age >= 0) meta += " · " + Ago.text(age);
        }
        source.setText(meta);
        source.setTextColor(Color.rgb(79, 195, 247));
        source.setTextSize(28);
        FrameLayout.LayoutParams sourceParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        sourceParams.leftMargin = 120; sourceParams.bottomMargin = 110;
        next.addView(source, sourceParams);
        if (slide.posterUrl != null) {
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 12);
                }
            });
            poster.setClipToOutline(true);
            FrameLayout.LayoutParams posterParams = new FrameLayout.LayoutParams(420, 600, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            posterParams.rightMargin = 100;
            next.addView(poster, posterParams);
            String posterUrl = slide.posterUrl;
            posters.execute(() -> {
                byte[] bytes = fetchCached("poster-" + Integer.toHexString(posterUrl.hashCode()), posterUrl, Collections.emptyMap());
                if (bytes != null) {
                    android.graphics.Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) handler.post(() -> { if (active && next == current) poster.setImageBitmap(bitmap); });
                }
            });
        }
        next.setAlpha(0);
        FrameLayout previous = current;
        current = next;
        next.animate().alpha(1).setDuration(600).withEndAction(() -> {
            if (previous != null) root.removeView(previous);
        }).start();
    }

    @Override public void onDetachedFromWindow() {
        active = false;
        if (loader != null) loader.interrupt();
        if (posters != null) posters.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}

package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The dashboard: one rail of five poster cards per tab (movies, series, sports), auto-rotating
 * around the center slot; applied from snapshots. No I/O.
 */
public final class DashboardCarousel extends LinearLayout {
    public interface Host { void openCard(MediaCard card); }

    private static final long ROTATE_MS = 8_000;
    private static final long KEY_PAUSE_MS = 30_000;
    private static final int SLOTS = 5;
    private static final MediaCard.Kind[] ORDER =
            {MediaCard.Kind.MOVIE, MediaCard.Kind.SERIES, MediaCard.Kind.SPORTS};
    private static final int[] TAB_IDS = {R.id.tabMovies, R.id.tabSeries, R.id.tabSports};
    private static final float[] SLOT_SCALE = {0.72f, 0.86f, 1f, 0.86f, 0.72f};
    private static final float[] SLOT_ALPHA = {0.55f, 0.8f, 1f, 0.8f, 0.55f};

    private Host host;
    private PosterLoader posters;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::rotate;
    private MediaSnapshot snapshot = MediaSnapshot.empty();
    private int selected; // index into ORDER
    private CarouselRotation rotation = new CarouselRotation(1);
    private final List<View> slots = new ArrayList<>();
    private long lastKeyMs;
    private boolean rotating;

    public DashboardCarousel(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setVisibility(GONE);
        setFocusable(true);
        setClickable(true);
        LayoutInflater.from(context).inflate(R.layout.dashboard_carousel, this, true);
        LinearLayout row = findViewById(R.id.cardRow);
        for (int i = 0; i < SLOTS; i++) {
            View slot = LayoutInflater.from(context).inflate(R.layout.media_card, row, false);
            slot.setFocusable(false); // The rail moves as one unit; only the carousel itself takes focus.
            row.addView(slot);
            slots.add(slot);
        }
        setOnClickListener(v -> openCurrent());
        // D-pad steers the window while the carousel is focused; unhandled keys pass through so
        // focus search and clicks behave as usual. Interaction time is recorded either way.
        setOnKeyListener((v, keyCode, event) -> {
            notifyUserInteraction();
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { rotation.previous(); move(); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { rotation.advance(); move(); return true; }
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) { openCurrent(); return true; }
            return false;
        });
    }

    public void setHost(Host host) { this.host = host; }
    void setPosterLoader(PosterLoader p) { this.posters = p; }

    /** Any remote input pauses rotation for a while; the activity forwards keys it sees itself. */
    void notifyUserInteraction() { lastKeyMs = SystemClock.uptimeMillis(); }

    void apply(MediaSnapshot next) {
        snapshot = next;
        boolean any = next.anyContent();
        setVisibility(any ? VISIBLE : GONE);
        if (!any) { stopRotation(); return; }
        if (pool().isEmpty()) selected = firstNonEmptyTab();
        List<MediaCard> pool = pool();
        if (rotation.size() != pool.size()) rotation = new CarouselRotation(pool.size());
        bindTabs();
        bindRail();
        startRotation();
    }

    private List<MediaCard> pool(int tab) {
        switch (ORDER[tab]) {
            case MOVIE: return snapshot.moviePool;
            case SERIES: return snapshot.seriesPool;
            default: return snapshot.sportsPool;
        }
    }

    private List<MediaCard> pool() { return pool(selected); }

    private int firstNonEmptyTab() {
        for (int i = 0; i < ORDER.length; i++) {
            if (!pool(i).isEmpty()) return i;
        }
        return 0;
    }

    private void selectTab(int idx) {
        if (pool(idx).isEmpty() || idx == selected) return;
        selected = idx;
        rotation = new CarouselRotation(pool().size());
        bindTabs();
        bindRail();
    }

    private void bindTabs() {
        int visible = 0;
        for (int i = 0; i < ORDER.length; i++) {
            if (!pool(i).isEmpty()) visible++;
        }
        findViewById(R.id.tabRow).setVisibility(visible > 1 ? VISIBLE : GONE);
        for (int i = 0; i < TAB_IDS.length; i++) {
            final int idx = i;
            TextView tab = findViewById(TAB_IDS[i]);
            boolean hasCards = !pool(i).isEmpty();
            tab.setVisibility(hasCards ? VISIBLE : GONE);
            tab.setSelected(idx == selected);
            tab.setTextColor(idx == selected ? 0xB3FFFFFF : 0x66FFFFFF);
            tab.setOnClickListener(v -> selectTab(idx));
            tab.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) selectTab(idx);
            });
        }
    }

    /** Slots re-bind to the window around the center index on every advance. */
    private void bindRail() {
        List<MediaCard> pool = pool();
        if (pool.isEmpty()) return;
        int size = pool.size();
        int center = Math.min(rotation.center(), size - 1);
        // Short pools fill the middle slots only, so the focused card keeps the center slot
        // and no card repeats; offsets within |offset| < size wrap to distinct cards.
        for (int i = 0; i < SLOTS; i++) {
            View slot = slots.get(i);
            int offset = i - SLOTS / 2;
            if (size < SLOTS && Math.abs(offset) >= size) { slot.setVisibility(GONE); continue; }
            int index = (center + offset + size) % size;
            slot.setVisibility(VISIBLE);
            slot.setScaleX(SLOT_SCALE[i]);
            slot.setScaleY(SLOT_SCALE[i]);
            slot.setAlpha(SLOT_ALPHA[i]);
            bindCard(slot, pool.get(index));
        }
    }

    private void bindCard(View view, MediaCard card) {
        ((TextView) view.findViewById(R.id.cardTitle)).setText(card.title);
        ((TextView) view.findViewById(R.id.cardLine)).setText(card.line);
        TextView state = view.findViewById(R.id.cardState);
        state.setText(stateText(card));
        state.setTextColor(card.state ==
                MediaCard.State.LIVE ? 0xFFFF6E6E : 0x99FFFFFF);
        View accent = view.findViewById(R.id.cardAccent);
        accent.setVisibility(card.accent == 0 ? GONE : VISIBLE);
        if (card.accent != 0) accent.setBackgroundColor(card.accent);
        TextView pill = view.findViewById(R.id.cardPill);
        String countdown = CountdownText.pill(card.whenMs, System.currentTimeMillis(),
                card.kind == MediaCard.Kind.SPORTS);
        pill.setText(countdown);
        pill.setVisibility(countdown.isEmpty() ? GONE : VISIBLE);
        view.setOnClickListener(v -> { if (host != null) host.openCard(card); });
        ImageView art = view.findViewById(R.id.cardArt);
        View scrim = view.findViewById(R.id.cardScrim);
        if (posters == null || card.artUrl.isEmpty()) {
            art.setTag(null); // An old fetch must not put art back onto a text-only card.
            art.setVisibility(GONE);
            scrim.setVisibility(GONE);
        } else {
            posters.load(art, scrim, card.artUrl);
        }
    }

    static String stateText(MediaCard card) {
        switch (card.state) {
            case AVAILABLE: return "Available";
            case REQUESTED: return "Requested";
            case APPROVED: return "Approved";
            case UPCOMING: return "Upcoming";
            case LIVE: return "Live";
            case FINAL: return "Final";
            default: return "";
        }
    }

    private void move() {
        notifyUserInteraction();
        bindRail();
    }

    private void openCurrent() {
        List<MediaCard> pool = pool();
        if (host == null || pool.isEmpty()) return;
        host.openCard(pool.get(Math.min(rotation.center(), pool.size() - 1)));
    }

    private void rotate() {
        if (!rotating) return;
        // Paused within 30s of a key: the tick is skipped, never reset.
        boolean paused = SystemClock.uptimeMillis() - lastKeyMs < KEY_PAUSE_MS;
        if (!paused && !pool().isEmpty()) {
            rotation.advance();
            bindRail();
        }
        handler.postDelayed(tick, ROTATE_MS);
    }

    private void startRotation() {
        if (rotating) return;
        rotating = true;
        handler.postDelayed(tick, ROTATE_MS);
    }

    private void stopRotation() {
        rotating = false;
        handler.removeCallbacks(tick);
    }

    @Override protected void onDetachedFromWindow() {
        stopRotation();
        super.onDetachedFromWindow();
    }
}

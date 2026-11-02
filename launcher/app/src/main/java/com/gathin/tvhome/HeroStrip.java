package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** The dashboard hero: one large auto-rotating card above the band, applied from snapshots. No I/O. */
public final class HeroStrip extends LinearLayout {
    public interface Host { void openCard(MediaCard card); }

    private static final long ROTATE_MS = 10_000;
    private static final long KEY_PAUSE_MS = 30_000;

    private Host host;
    private PosterLoader posters;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::rotate;
    private MediaSnapshot snapshot = MediaSnapshot.empty();
    private int category = -1;
    private int index;
    private long lastKeyMs;
    private boolean rotating;

    public HeroStrip(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setVisibility(GONE);
        setFocusable(true);
        setClickable(true);
        LayoutInflater.from(context).inflate(R.layout.hero_strip, this, true);
        setOnClickListener(v -> {
            MediaCard card = current();
            if (host != null && card != null) host.openCard(card);
        });
        // Records interaction time only; it never consumes, so clicks and focus search behave as usual.
        setOnKeyListener((v, keyCode, event) -> {
            notifyUserInteraction();
            return false;
        });
    }

    public void setHost(Host host) { this.host = host; }
    void setPosterLoader(PosterLoader p) { this.posters = p; }

    /** Any remote input pauses rotation for a while; the activity forwards keys it sees itself. */
    void notifyUserInteraction() { lastKeyMs = SystemClock.uptimeMillis(); }

    void apply(MediaSnapshot next) {
        snapshot = next;
        boolean any = next.anyHeroContent();
        setVisibility(any ? VISIBLE : GONE);
        if (!any) { stopRotation(); return; }
        int[] sizes = sizes();
        if (category < 0 || category >= sizes.length || index >= sizes[category]) {
            int[] start = HeroRotation.advance(0, -1, sizes);
            category = start[0];
            index = start[1];
        }
        bind(false);
        startRotation();
    }

    private void rotate() {
        if (!rotating) return;
        // Paused while focused or within 30s of a key: the tick is skipped, never reset.
        boolean paused = hasFocus() || SystemClock.uptimeMillis() - lastKeyMs < KEY_PAUSE_MS;
        if (!paused) {
            int[] next = HeroRotation.advance(category, index, sizes());
            if (next != null) {
                category = next[0];
                index = next[1];
                bind(true);
            }
        }
        handler.postDelayed(tick, ROTATE_MS);
    }

    private void bind(boolean animate) {
        MediaCard card = current();
        if (card == null) return;
        ((TextView) findViewById(R.id.heroLabel)).setText(label(category));
        ((TextView) findViewById(R.id.heroTitle)).setText(card.title);
        ((TextView) findViewById(R.id.heroLine)).setText(card.line);
        ((TextView) findViewById(R.id.heroState)).setText(MediaBand.stateText(card));
        ImageView backdrop = findViewById(R.id.heroBackdrop);
        View scrim = findViewById(R.id.heroCardScrim);
        if (posters == null || card.artUrl.isEmpty()) {
            backdrop.setTag(null); // An old fetch must not put art back onto a text-only card.
            backdrop.setVisibility(GONE);
            scrim.setVisibility(GONE);
        } else {
            posters.load(backdrop, scrim, card.artUrl);
        }
        if (animate) {
            AlphaAnimation fade = new AlphaAnimation(0f, 1f);
            fade.setDuration(300);
            fade.setFillAfter(true);
            findViewById(R.id.heroContent).startAnimation(fade);
        }
    }

    private String label(int category) {
        int res;
        switch (category) {
            case 0: res = R.string.media_hero_continue; break;
            case 1: res = R.string.media_hero_downloads; break;
            case 2: res = R.string.media_hero_upcoming; break;
            default: res = R.string.media_hero_sports; break;
        }
        return getContext().getString(res);
    }

    private int[] sizes() {
        return new int[] {snapshot.continueCards.size(), snapshot.downloadCards.size(),
                snapshot.upcomingCards.size(), snapshot.sportsHero.size()};
    }

    private List<MediaCard> cards(int category) {
        switch (category) {
            case 0: return snapshot.continueCards;
            case 1: return snapshot.downloadCards;
            case 2: return snapshot.upcomingCards;
            default: return snapshot.sportsHero;
        }
    }

    private MediaCard current() {
        if (category < 0) return null;
        List<MediaCard> cards = cards(category);
        return cards.isEmpty() ? null : cards.get(Math.min(index, cards.size() - 1));
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

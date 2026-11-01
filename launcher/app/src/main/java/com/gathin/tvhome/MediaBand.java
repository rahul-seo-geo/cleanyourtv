package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The dashboard band: a category rail and one row of cards, applied from snapshots. No I/O. */
public final class MediaBand extends LinearLayout {
    public interface Host { void openCard(MediaCard card); }

    private static final MediaCard.Kind[] ORDER =
            {MediaCard.Kind.MOVIE, MediaCard.Kind.SERIES, MediaCard.Kind.SPORTS, MediaCard.Kind.LIVE};

    private Host host;
    private int selected; // index into ORDER
    private final Map<String, View> cardViews = new HashMap<>();
    private MediaSnapshot snapshot = MediaSnapshot.empty();

    public MediaBand(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setVisibility(GONE);
    }

    public void setHost(Host host) { this.host = host; }

    void apply(MediaSnapshot next) {
        snapshot = next;
        setVisibility(next.anyContent() ? VISIBLE : GONE);
        if (!next.anyContent()) { cardViews.clear(); return; }
        selected = Math.min(selected, ORDER.length - 1);
        bindRail();
        bindRow();
    }

    private List<MediaCard> cards() {
        switch (ORDER[selected]) {
            case MOVIE: return snapshot.movies;
            case SERIES: return snapshot.series;
            case SPORTS: return snapshot.sports;
            default: return snapshot.live;
        }
    }

    private void bindRail() {
        int[] tabIds = {R.id.tabMovies, R.id.tabSeries, R.id.tabSports, R.id.tabLive};
        for (int i = 0; i < tabIds.length; i++) {
            final int idx = i;
            TextView tab = findViewById(tabIds[idx]);
            tab.setSelected(idx == selected);
            tab.setTextColor(idx == selected ? 0xB3FFFFFF : 0x66FFFFFF);
            tab.setOnClickListener(v -> { selected = idx; bindRail(); bindRow(); });
        }
    }

    private void bindRow() {
        LinearLayout row = findViewById(R.id.cardRow);
        List<MediaCard> cards = cards();
        List<String> live = new ArrayList<>();
        Map<String, View> stale = new HashMap<>(cardViews);
        for (MediaCard card : cards) {
            live.add(card.id);
            View view = cardViews.get(card.id);
            if (view == null) {
                view = LayoutInflater.from(getContext()).inflate(R.layout.media_card, row, false);
                view.setOnClickListener(v -> { if (host != null) host.openCard(card); });
                row.addView(view);
                cardViews.put(card.id, view);
            }
            stale.remove(card.id);
            bindCard(view, card);
        }
        for (Map.Entry<String, View> gone : stale.entrySet()) {
            row.removeView(gone.getValue());
            cardViews.remove(gone.getKey());
        }
    }

    private void bindCard(View view, MediaCard card) {
        ((TextView) view.findViewById(R.id.cardTitle)).setText(card.title);
        ((TextView) view.findViewById(R.id.cardLine)).setText(card.line);
        TextView state = view.findViewById(R.id.cardState);
        state.setText(stateText(card));
        state.setTextColor(card.state == MediaCard.State.LIVE ? 0xFF6FCF3F : 0x99FFFFFF);
        View accent = view.findViewById(R.id.cardAccent);
        accent.setVisibility(card.accent == 0 ? GONE : VISIBLE);
        if (card.accent != 0) accent.setBackgroundColor(card.accent);
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
}

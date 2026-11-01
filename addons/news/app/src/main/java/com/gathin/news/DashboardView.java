package com.gathin.news;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DashboardView extends FrameLayout {

    public interface PosterLoader { byte[] load(String url); }

    private static final int SUPPORTING_SLOTS = 5;
    private static final int MATCH_SLOTS = 4;
    private static final int CHIP_SLOTS = 8;
    private static final int LAUNCHER_SLOTS = 6;

    private static final int TITLE = 0xFFF2F7FC;
    private static final int TITLE_STALE = 0xC8F2F7FC;
    private static final int META = 0xFF4FC3F7;
    private static final int META_STALE = 0x994FC3F7;
    private static final int DIM = 0xFF6B7A8C;
    private static final int CARD = 0x14F2F7FC;
    private static final int LIVE = 0xFF3CBE78;
    private static final int BAR_VACANT = 0x33FFFFFF;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService posters = Executors.newSingleThreadExecutor();
    private PosterLoader posterLoader;

    private final TextView clock;
    private final TextView[] chips = new TextView[CHIP_SLOTS];
    private final TextView leadTitle;
    private final TextView leadMeta;
    private final ImageView leadPoster;
    private final TextView matchEmpty;
    private final LinearLayout launcherRow;
    private final StoryCell[] supporting = new StoryCell[SUPPORTING_SLOTS];
    private final MatchCell[] matches = new MatchCell[MATCH_SLOTS];

    private static final class StoryCell {
        final TextView title;
        final TextView meta;
        final ImageView poster;

        StoryCell(TextView title, TextView meta, ImageView poster) {
            this.title = title;
            this.meta = meta;
            this.poster = poster;
        }
    }

    private static final class MatchCell {
        final View bar;
        final TextView topLine;
        final TextView status;

        MatchCell(View bar, TextView topLine, TextView status) {
            this.bar = bar;
            this.topLine = topLine;
            this.status = status;
        }
    }

    public DashboardView(Context context) {
        super(context);
        setBackgroundResource(R.drawable.screen);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(96, 40, 96, 200);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        clock = new TextView(context);
        clock.setTextColor(TITLE);
        clock.setTextSize(44);
        clock.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        header.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        header.addView(new Space(context), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout chipRow = new LinearLayout(context);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < CHIP_SLOTS; i++) {
            TextView chip = new TextView(context);
            chip.setTextColor(TITLE);
            chip.setTextSize(20);
            chip.setMaxLines(1);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setVisibility(GONE);
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
            chipParams.leftMargin = 32;
            chipRow.addView(chip, chipParams);
            chips[i] = chip;
        }
        header.addView(chipRow, new LinearLayout.LayoutParams(-2, -2));
        content.addView(header, rowParams(88f, 24));

        LinearLayout lead = new LinearLayout(context);
        lead.setOrientation(LinearLayout.HORIZONTAL);
        lead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout leadText = new LinearLayout(context);
        leadText.setOrientation(LinearLayout.VERTICAL);
        leadTitle = titleView(context, 54, 3);
        leadMeta = metaView(context, 26);
        leadText.addView(leadTitle, new LinearLayout.LayoutParams(-1, -2));
        leadText.addView(leadMeta, new LinearLayout.LayoutParams(-1, -2));
        lead.addView(leadText, new LinearLayout.LayoutParams(0, -1, 5f));
        leadPoster = posterView(context, 12);
        LinearLayout.LayoutParams posterParams = new LinearLayout.LayoutParams(0, -1, 2f);
        posterParams.leftMargin = 48;
        lead.addView(leadPoster, posterParams);
        content.addView(lead, rowParams(380f, 24));

        LinearLayout supportingRow = new LinearLayout(context);
        supportingRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < SUPPORTING_SLOTS; i++) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(cardBackground(CARD));
            card.setPadding(24, 24, 24, 24);
            TextView title = titleView(context, 26, 2);
            TextView meta = metaView(context, 20);
            ImageView poster = posterView(context, 8);
            LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(-1, 0, 1f);
            thumbParams.topMargin = 16;
            card.addView(title, new LinearLayout.LayoutParams(-1, -2));
            card.addView(meta, new LinearLayout.LayoutParams(-1, -2));
            card.addView(poster, thumbParams);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, -1, 1f);
            cardParams.leftMargin = i == 0 ? 0 : 12;
            cardParams.rightMargin = i == SUPPORTING_SLOTS - 1 ? 0 : 12;
            supportingRow.addView(card, cardParams);
            supporting[i] = new StoryCell(title, meta, poster);
        }
        content.addView(supportingRow, rowParams(260f, 24));

        FrameLayout strip = new FrameLayout(context);
        LinearLayout matchRow = new LinearLayout(context);
        matchRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < MATCH_SLOTS; i++) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(cardBackground(CARD));
            card.setPadding(20, 16, 20, 16);
            View bar = new View(context);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(10, -1);
            card.addView(bar, barParams);
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView topLine = new TextView(context);
            topLine.setTextColor(TITLE);
            topLine.setTextSize(24);
            topLine.setMaxLines(2);
            topLine.setEllipsize(TextUtils.TruncateAt.END);
            TextView status = new TextView(context);
            status.setTextColor(META);
            status.setTextSize(22);
            status.setMaxLines(1);
            status.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(topLine, new LinearLayout.LayoutParams(-1, -2));
            texts.addView(status, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1f);
            textParams.leftMargin = 20;
            card.addView(texts, textParams);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, -1, 1f);
            cardParams.leftMargin = i == 0 ? 0 : 12;
            cardParams.rightMargin = i == MATCH_SLOTS - 1 ? 0 : 12;
            matchRow.addView(card, cardParams);
            matches[i] = new MatchCell(bar, topLine, status);
        }
        strip.addView(matchRow, new FrameLayout.LayoutParams(-1, -1));
        matchEmpty = new TextView(context);
        matchEmpty.setTextColor(DIM);
        matchEmpty.setTextSize(24);
        matchEmpty.setGravity(Gravity.CENTER);
        matchEmpty.setVisibility(GONE);
        strip.addView(matchEmpty, new FrameLayout.LayoutParams(-1, -1));
        content.addView(strip, rowParams(160f, 0));

        addView(content, new FrameLayout.LayoutParams(-1, -1));

        launcherRow = new LinearLayout(context);
        launcherRow.setOrientation(LinearLayout.HORIZONTAL);
        launcherRow.setGravity(Gravity.CENTER_VERTICAL);
        launcherRow.setPadding(96, 0, 96, 28);
        launcherRow.setVisibility(GONE);
        TextView label = new TextView(context);
        label.setTextColor(DIM);
        label.setTextSize(24);
        label.setText("Apps");
        launcherRow.addView(label, new LinearLayout.LayoutParams(-2, -2));
        for (int i = 0; i < LAUNCHER_SLOTS; i++) {
            TextView slot = new TextView(context);
            slot.setTextColor(TITLE);
            slot.setTextSize(26);
            slot.setGravity(Gravity.CENTER);
            slot.setBackground(cardBackground(CARD));
            LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(0, 88, 1f);
            slotParams.leftMargin = 24;
            launcherRow.addView(slot, slotParams);
        }
        addView(launcherRow, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    public void render(DashboardSnapshot snapshot) {
        long now = System.currentTimeMillis();
        renderChips(snapshot.sources, now);
        List<DashboardSnapshot.StorySlot> stories = snapshot.stories;
        boolean allOffline = !snapshot.sources.isEmpty();
        for (DashboardSnapshot.SourceStatus status : snapshot.sources.values())
            if (status.freshness != DashboardSnapshot.Freshness.UNAVAILABLE) allOffline = false;
        renderStory(leadTitle, leadMeta, leadPoster,
                stories.isEmpty() ? null : stories.get(0), now, true, allOffline);
        for (int i = 0; i < SUPPORTING_SLOTS; i++) {
            StoryCell cell = supporting[i];
            renderStory(cell.title, cell.meta, cell.poster,
                    stories.size() > i + 1 ? stories.get(i + 1) : null, now, false, false);
        }
        renderMatches(snapshot, now);
    }

    public void setLauncherVisible(boolean visible) {
        launcherRow.setVisibility(visible ? VISIBLE : GONE);
    }

    public void setClockText(String text) {
        clock.setText(text);
    }

    public void setPosterLoader(PosterLoader loader) {
        posterLoader = loader;
    }

    @Override protected void onDetachedFromWindow() {
        posters.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void renderChips(Map<String, DashboardSnapshot.SourceStatus> sources, long now) {
        int index = 0;
        for (Map.Entry<String, DashboardSnapshot.SourceStatus> entry : sources.entrySet()) {
            if (index >= CHIP_SLOTS) break;
            DashboardSnapshot.SourceStatus status = entry.getValue();
            TextView chip = chips[index++];
            chip.setText(DashboardFormat.chipLabel(entry.getKey(), status.freshness, now, status.updatedMs));
            chip.setTextColor(status.freshness == DashboardSnapshot.Freshness.FRESH ? TITLE
                    : status.freshness == DashboardSnapshot.Freshness.STALE ? META : DIM);
            chip.setVisibility(VISIBLE);
        }
        for (int i = index; i < CHIP_SLOTS; i++) chips[i].setVisibility(GONE);
    }

    private void renderStory(TextView title, TextView meta, ImageView poster,
                             DashboardSnapshot.StorySlot slot, long now, boolean lead, boolean allOffline) {
        if (slot == null || slot.isVacant()) {
            title.setTextColor(lead ? DIM : TITLE_STALE);
            title.setText(lead ? "No story loaded" : "");
            meta.setTextColor(DIM);
            meta.setText(lead && allOffline ? "sources unavailable" : "");
            poster.setImageDrawable(null);
            poster.setTag("");
            return;
        }
        boolean media = slot.contentKind == DashboardSnapshot.ContentKind.UPCOMING_MOVIE
                || slot.contentKind == DashboardSnapshot.ContentKind.UPCOMING_SERIES;
        title.setTextColor(slot.stale ? TITLE_STALE : TITLE);
        title.setText(DashboardFormat.truncate(slot.title, lead ? 140 : 90));
        meta.setTextColor(slot.stale ? META_STALE : META);
        meta.setText(media
                ? DashboardFormat.mediaMeta(slot.publishedMs, slot.stale, Locale.getDefault())
                : DashboardFormat.storyMeta(slot.source, slot.stale, now, slot.publishedMs));
        String posterUrl = media && slot.destinationUrl != null && !slot.destinationUrl.isBlank()
                ? slot.destinationUrl : null;
        if (posterUrl == null) {
            poster.setImageDrawable(null);
            poster.setTag("");
        } else {
            requestPoster(poster, posterUrl);
        }
    }

    private void renderMatches(DashboardSnapshot snapshot, long now) {
        List<DashboardSnapshot.MatchSlot> slots = snapshot.matches;
        DashboardSnapshot.SourceStatus gameDay = snapshot.sources.get("game_day");
        boolean empty = slots.isEmpty();
        if (empty && !snapshot.sources.isEmpty()) {
            matchEmpty.setText(DashboardFormat.matchStripLabel(
                    gameDay == null || gameDay.freshness == DashboardSnapshot.Freshness.UNAVAILABLE));
            matchEmpty.setVisibility(VISIBLE);
        } else {
            matchEmpty.setVisibility(GONE);
        }
        for (int i = 0; i < MATCH_SLOTS; i++) {
            MatchCell cell = matches[i];
            DashboardSnapshot.MatchSlot slot = empty || slots.size() <= i ? null : slots.get(i);
            cell.topLine.setVisibility(slot == null ? INVISIBLE : VISIBLE);
            cell.status.setVisibility(slot == null ? INVISIBLE : VISIBLE);
            cell.bar.setBackgroundColor(slot == null ? BAR_VACANT : slot.colorArgb);
            if (slot == null) continue;
            cell.topLine.setTextColor(slot.freshness == DashboardSnapshot.Freshness.STALE
                    ? TITLE_STALE : TITLE);
            cell.topLine.setText(DashboardFormat.truncate(slot.topLine, 40));
            cell.status.setText(DashboardFormat.fixtureStatus(slot, now));
            cell.status.setTextColor(slot.phase == DashboardSnapshot.MatchPhase.LIVE
                    ? LIVE : slot.freshness == DashboardSnapshot.Freshness.STALE ? META_STALE : META);
        }
    }

    private void requestPoster(ImageView target, String url) {
        if (url.equals(target.getTag())) return;
        target.setTag(url);
        target.setImageDrawable(null);
        if (posterLoader == null) return;
        posters.execute(() -> {
            byte[] bytes = posterLoader.load(url);
            Bitmap bitmap = bytes == null ? null : BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            handler.post(() -> {
                if (bitmap != null && isAttachedToWindow() && url.equals(target.getTag()))
                    target.setImageBitmap(bitmap);
            });
        });
    }

    private LinearLayout.LayoutParams rowParams(float weight, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 0, weight);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private static TextView titleView(Context context, int sizeSp, int maxLines) {
        TextView title = new TextView(context);
        title.setTextColor(TITLE);
        title.setTextSize(sizeSp);
        title.setMaxLines(maxLines);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 1.1f);
        return title;
    }

    private static TextView metaView(Context context, int sizeSp) {
        TextView meta = new TextView(context);
        meta.setTextColor(META);
        meta.setTextSize(sizeSp);
        meta.setMaxLines(1);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        return meta;
    }

    private static ImageView posterView(Context context, int radius) {
        ImageView poster = new ImageView(context);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        poster.setClipToOutline(true);
        poster.setTag("");
        return poster;
    }

    private static GradientDrawable cardBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(10);
        background.setColor(color);
        return background;
    }
}

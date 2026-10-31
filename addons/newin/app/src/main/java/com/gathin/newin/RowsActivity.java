package com.gathin.newin;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.BaseColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RowsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Rows.Shelf> shelves = new ArrayList<>();
    private RecyclerView list;
    private ShelfAdapter shelfAdapter;
    private TextView empty;
    private Cache cache;
    private Settings settings;
    private Map<String, String> jellyfinHeaders;

    @Override protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        cache = new Cache(this);
        settings = Settings.read(this);
        jellyfinHeaders = Map.of("Authorization", "MediaBrowser Token=\"" + settings.get("jellyfin_key") + "\"");
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(36), dp(20), dp(24), dp(12));
        TextView header = label("New In", 30, Color.WHITE);
        screen.addView(header);
        empty = label("Loading your shelves…", 17, 0xffaabac9);
        screen.addView(empty);
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        shelfAdapter = new ShelfAdapter();
        list.setAdapter(shelfAdapter);
        screen.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(screen);
        render();
        new Thread(this::loadLive, "newin-live").start();
        new Thread(this::loadOwnTv, "newin-own").start();
        new Thread(() -> loadRemote("latest", settings.get("jellyfin_url"), "/Users/" + settings.get("jellyfin_user") + "/Items/Latest?Limit=20", jellyfinHeaders), "newin-latest").start();
        new Thread(() -> loadRemote("resume", settings.get("jellyfin_url"), "/Users/" + settings.get("jellyfin_user") + "/Items/Resume", jellyfinHeaders), "newin-resume").start();
        new Thread(() -> loadRemote("seerr", settings.get("jellyseerr_url"), "/api/v1/request?take=20", Map.of("X-Api-Key", settings.get("jellyseerr_key"))), "newin-seerr").start();
    }

    private void loadLive() {
        List<Rows.Item> items = new ArrayList<>();
        try (Cursor cursor = getContentResolver().query(Uri.parse("content://io.github.justinnewman.gameday.games/turns"),
                new String[]{"top_line", "word", "start_ms", "end_ms", "colour", "calendar", "turn_seconds"}, null, null, null)) {
            if (cursor != null) {
                long now = System.currentTimeMillis();
                int title = cursor.getColumnIndexOrThrow("top_line"), start = cursor.getColumnIndexOrThrow("start_ms"), end = cursor.getColumnIndexOrThrow("end_ms");
                while (cursor.moveToNext()) if (cursor.getLong(start) <= now && cursor.getLong(end) > now)
                    items.add(new Rows.Item(cursor.getString(title), "Live now", "", "sports", -1));
            }
        } catch (Exception ignored) { }
        replace("live", items.isEmpty() ? List.of() : List.of(new Rows.Shelf("Live now", "live", items)));
    }

    private void loadOwnTv() {
        List<Rows.Item> items = new ArrayList<>();
        try (Cursor cursor = getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                new String[]{TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, TvContract.WatchNextPrograms.COLUMN_TITLE,
                        TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, TvContract.WatchNextPrograms.COLUMN_INTENT_URI},
                null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                String pkg = cursor.getString(0), intent = cursor.getString(3);
                if (Rows.ownTv(pkg, intent)) items.add(new Rows.Item(cursor.getString(1), "Continue on OwnTV",
                        cursor.getString(2), intent, -1));
            }
        } catch (Exception ignored) { }
        replace("own", items.isEmpty() ? List.of() : List.of(new Rows.Shelf("Continue on OwnTV", "own", items)));
    }

    private void loadRemote(String source, String base, String path, Map<String, String> headers) {
        base = base.replaceAll("/+$", "");
        byte[] saved = cache.read(source);
        if (saved != null) showJson(source, saved, base, false);
        if (base.isEmpty() || (source.equals("seerr") ? settings.get("jellyseerr_key").isEmpty()
                : settings.get("jellyfin_user").isEmpty() || settings.get("jellyfin_key").isEmpty())) {
            if (saved != null) showJson(source, saved, base, true);
            return;
        }
        byte[] fresh = Fetch.get(base + path, headers);
        List<Rows.Shelf> rows = fresh == null ? null : parseJson(source, fresh, base);
        if (rows != null) {
            cache.put(source, fresh);
            showRows(source, rows, false);
        } else if (saved != null) showJson(source, saved, base, true);
    }

    private List<Rows.Shelf> parseJson(String source, byte[] bytes, String base) {
        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            switch (source) {
                case "latest": return Rows.latest(json, base);
                case "resume": return Rows.resume(json, base);
                default: return Rows.seerr(json);
            }
        } catch (Exception ignored) { return null; }
    }

    private void showJson(String source, byte[] bytes, String base, boolean failed) {
        List<Rows.Shelf> rows = parseJson(source, bytes, base);
        if (rows != null) showRows(source, rows, failed);
    }

    private void showRows(String source, List<Rows.Shelf> rows, boolean failed) {
        String message = Ago.text(cache.ageSeconds(source));
        for (Rows.Shelf row : rows) row.message = message + (failed ? " · Source unavailable" : "");
        replace(source, rows);
    }

    private void replace(String source, List<Rows.Shelf> next) {
        main.post(() -> {
            int place = 0;
            String[] order = {"live", "own", "latest", "resume", "seerr"};
            for (String key : order) {
                if (key.equals(source)) break;
                for (Rows.Shelf shelf : shelves) if (shelf.source.equals(key)) place++;
            }
            int count = 0;
            while (place + count < shelves.size() && shelves.get(place + count).source.equals(source)) count++;
            for (int i = 0; i < next.size(); i++) {
                Rows.Shelf updated = next.get(i);
                int match = -1;
                for (int j = i; j < count; j++) {
                    if (shelves.get(place + j).title.equals(updated.title)) { match = j; break; }
                }
                if (match < 0) {
                    shelves.add(place + i, updated);
                    count++;
                    shelfAdapter.notifyItemInserted(place + i);
                } else {
                    if (match != i) {
                        Rows.Shelf moved = shelves.remove(place + match);
                        shelves.add(place + i, moved);
                        shelfAdapter.notifyItemMoved(place + match, place + i);
                    }
                    Rows.Shelf current = shelves.get(place + i);
                    current.items.clear();
                    current.items.addAll(updated.items);
                    current.message = updated.message;
                    shelfAdapter.notifyItemChanged(place + i, "source-refresh");
                }
            }
            if (count > next.size()) {
                shelves.subList(place + next.size(), place + count).clear();
                shelfAdapter.notifyItemRangeRemoved(place + next.size(), count - next.size());
            }
            render();
        });
    }

    private void render() {
        empty.setText(shelves.isEmpty() ? "No items right now. Check settings or try again later." : "");
    }

    private void launch(Rows.Item item) {
        if (item.intent.isEmpty()) return;
        if (item.intent.equals("sports") || item.intent.equals("jellyfin")) {
            String pkg = item.intent.equals("sports") ? settings.sportsApp() : "org.jellyfin.androidtv";
            try { Intent launch = getPackageManager().getLaunchIntentForPackage(pkg); if (launch != null) startActivity(launch); }
            catch (Exception ignored) { }
        } else {
            try { startActivity(Intent.parseUri(item.intent, Intent.URI_INTENT_SCHEME)); }
            catch (Exception e) {
                try { Intent fallback = getPackageManager().getLaunchIntentForPackage("tv.own.owntv"); if (fallback != null) startActivity(fallback); }
                catch (Exception ignored) { }
            }
        }
    }

    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setSingleLine(true); view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private final class ShelfAdapter extends RecyclerView.Adapter<ShelfAdapter.Holder> {
        final class Holder extends RecyclerView.ViewHolder {
            final TextView title, detail;
            final RecyclerView cards;
            Holder(LinearLayout view, TextView title, TextView detail, RecyclerView cards) {
                super(view); this.title = title; this.detail = detail; this.cards = cards;
            }
        }
        @Override public int getItemCount() { return shelves.size(); }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout view = new LinearLayout(RowsActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(0, dp(14), 0, dp(10));
            TextView title = label("", 21, Color.WHITE), detail = label("", 13, 0xff9aafbd);
            view.addView(title); view.addView(detail);
            RecyclerView cards = new RecyclerView(RowsActivity.this);
            cards.setLayoutManager(new LinearLayoutManager(RowsActivity.this, RecyclerView.HORIZONTAL, false));
            view.addView(cards, new LinearLayout.LayoutParams(-1, dp(210)));
            return new Holder(view, title, detail, cards);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            Rows.Shelf shelf = shelves.get(position);
            holder.title.setText(shelf.title);
            holder.detail.setText(shelf.message);
            holder.cards.setAdapter(new CardAdapter(shelf));
        }
        @Override public void onBindViewHolder(Holder holder, int position, List<Object> payloads) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position);
                return;
            }
            Rows.Shelf shelf = shelves.get(position);
            holder.detail.setText(shelf.message);
            RecyclerView.Adapter<?> cards = holder.cards.getAdapter();
            if (cards != null) cards.notifyDataSetChanged();
        }
    }

    private final class CardAdapter extends RecyclerView.Adapter<CardAdapter.Holder> {
        final Rows.Shelf shelf;
        CardAdapter(Rows.Shelf shelf) { this.shelf = shelf; }
        final class Holder extends RecyclerView.ViewHolder {
            final ImageView image;
            final TextView title, subtitle;
            final ProgressBar progress;
            Holder(LinearLayout view, ImageView image, TextView title, TextView subtitle, ProgressBar progress) {
                super(view); this.image = image; this.title = title; this.subtitle = subtitle; this.progress = progress;
            }
        }
        @Override public int getItemCount() { return shelf.items.size(); }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout card = new LinearLayout(RowsActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(5), dp(4), dp(5), dp(4));
            boolean wide = shelf.source.equals("live") || shelf.source.equals("resume") || shelf.source.equals("own");
            int imageWidth = wide ? 256 : 120;
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(imageWidth + 10), dp(206));
            params.setMargins(0, 0, dp(16), 0);
            card.setLayoutParams(params);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xff192632); background.setCornerRadius(dp(8));
            card.setBackground(background);
            ImageView image = new ImageView(RowsActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0xff24394a);
            card.addView(image, new LinearLayout.LayoutParams(dp(imageWidth), dp(wide ? 144 : 180)));
            ProgressBar progress = new ProgressBar(RowsActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            card.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
            TextView title = label("", 15, Color.WHITE), subtitle = label("", 12, 0xffa2c0d1);
            card.addView(title); card.addView(subtitle);
            card.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(140).start();
                v.setElevation(focused ? dp(10) : 0);
            });
            return new Holder(card, image, title, subtitle, progress);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            Rows.Item item = shelf.items.get(position);
            holder.title.setText(item.title);
            holder.subtitle.setText(item.subtitle);
            holder.progress.setVisibility(item.progress >= 0 ? View.VISIBLE : View.GONE);
            holder.progress.setProgress(Math.max(0, item.progress));
            holder.itemView.setOnClickListener(v -> launch(item));
            holder.image.setTag(null);
            holder.image.setImageDrawable(null);
            poster(holder.image, item.poster, shelf.source.equals("latest") || shelf.source.equals("resume") ? jellyfinHeaders : Map.of());
        }
    }

    private void poster(ImageView image, String url, Map<String, String> headers) {
        if (url == null || url.isEmpty()) return;
        String key;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((url + headers.toString()).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("poster-");
            for (int i = 0; i < 12; i++) builder.append(String.format("%02x", hash[i] & 255));
            key = builder.toString();
        } catch (Exception e) { return; }
        image.setTag(key);
        new Thread(() -> {
            byte[] data = cache.read(key);
            if (data == null) {
                if (url.startsWith("content://")) {
                    try (java.io.InputStream stream = getContentResolver().openInputStream(Uri.parse(url))) {
                        if (stream != null) data = stream.readNBytes(1024 * 1024);
                    } catch (Exception ignored) { }
                } else if (url.startsWith("http://") || url.startsWith("https://")) data = Fetch.get(url, headers);
                if (data != null) cache.put(key, data);
            }
            if (data == null) return;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap != null) main.post(() -> {
                if (key.equals(image.getTag())) image.setImageBitmap(bitmap);
            });
        }, "newin-poster").start();
    }
}

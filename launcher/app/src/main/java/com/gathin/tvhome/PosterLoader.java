package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Downloads bounded poster bytes off the UI thread; a missing image leaves the text card intact. */
final class PosterLoader {
    // One sixteenth of the heap is about 16–32 MB on this TV class: room for 45+ w342 posters.
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 16)) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    void load(ImageView art, View scrim, String url) {
        // The tag belongs to this binding, not the view. Even a failed fetch must not show an
        // older poster if a refresh or tab switch has reused the view for a different URL.
        art.setTag(url);
        if (url == null || url.isEmpty()) {
            detach(art, scrim, url);
            return;
        }
        Bitmap hit = cache.get(url);
        if (hit != null) {
            attach(art, scrim, url, hit);
            return;
        }
        detach(art, scrim, url);
        float density = art.getResources().getDisplayMetrics().density;
        int dstW = (int) (160 * density * 2);
        int dstH = (int) (240 * density * 2);
        try {
            io.execute(() -> {
                Bitmap bitmap = null;
                try {
                    // Fetch enforces both the ten-second timeouts and a 4 MB response ceiling.
                    byte[] bytes = new Fetch().get(url, Map.of());
                    if (bytes != null) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                        options.inJustDecodeBounds = false;
                        options.inSampleSize = inSampleSize(options.outWidth, options.outHeight, dstW, dstH);
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    }
                } catch (Exception ignored) {
                    // Malformed or unavailable art is not a failed card: keep the text fallback.
                }
                if (bitmap != null) cache.put(url, bitmap);
                Bitmap result = bitmap;
                main.post(() -> {
                    if (result == null) detach(art, scrim, url);
                    else attach(art, scrim, url, result);
                });
            });
        } catch (RejectedExecutionException ignored) { // Activity is shutting down.
        }
    }

    private void attach(ImageView art, View scrim, String url, Bitmap bitmap) {
        if (!url.equals(art.getTag())) return;
        art.setImageBitmap(bitmap);
        art.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
    }

    private void detach(ImageView art, View scrim, String url) {
        if (url != null && !url.equals(art.getTag())) return;
        art.setVisibility(View.GONE);
        scrim.setVisibility(View.GONE);
    }

    /** Largest power of two whose sampled image still covers both target dimensions. */
    static int inSampleSize(int srcW, int srcH, int dstW, int dstH) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return 1;
        int sample = 1;
        while (sample <= Integer.MAX_VALUE / 2
                && srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) {
            sample *= 2;
        }
        return sample;
    }

    void shutdown() { io.shutdownNow(); }
}

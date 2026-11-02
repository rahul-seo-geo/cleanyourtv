package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Downloads bounded poster bytes off the UI thread; a missing image leaves the text card intact. */
final class PosterLoader {
    interface Decoder<T> { T decode(byte[] bytes, int dstW, int dstH); }

    // One sixteenth of the heap is about 16–32 MB on this TV class: room for 45+ w342 posters.
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 16)) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PosterStore store;
    private final Function<String, Map<String, String>> headersFor;

    PosterLoader() {
        this(null);
    }

    PosterLoader(PosterStore store) {
        this(store, url -> Map.of());
    }

    PosterLoader(PosterStore store, Function<String, Map<String, String>> headersFor) {
        this.store = store;
        this.headersFor = headersFor;
    }

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
                Bitmap bitmap = fromSources(url, store, new Fetch(), PosterLoader::decode, dstW, dstH,
                        headersFor.apply(url));
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

    static <T> T fromSources(String url, PosterStore store, MediaLoader.Fetcher fetcher,
                             Decoder<T> decoder, int dstW, int dstH) {
        return fromSources(url, store, fetcher, decoder, dstW, dstH, Map.of());
    }

    static <T> T fromSources(String url, PosterStore store, MediaLoader.Fetcher fetcher,
                             Decoder<T> decoder, int dstW, int dstH, Map<String, String> headers) {
        byte[] bytes = null;
        if (store != null) {
            try {
                bytes = store.read(url);
            } catch (Exception ignored) { }
        }
        if (bytes != null) {
            try {
                T bitmap = decoder.decode(bytes, dstW, dstH);
                if (bitmap != null) return bitmap;
            } catch (Exception ignored) { }
        }
        try {
            bytes = fetcher.get(url, headers);
            if (bytes == null) return null;
            T bitmap = decoder.decode(bytes, dstW, dstH);
            if (bitmap != null && store != null) {
                try {
                    store.put(url, bytes);
                } catch (Exception ignored) { }
            }
            return bitmap;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Bitmap decode(byte[] bytes, int dstW, int dstH) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        options.inJustDecodeBounds = false;
        options.inSampleSize = inSampleSize(options.outWidth, options.outHeight, dstW, dstH);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
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

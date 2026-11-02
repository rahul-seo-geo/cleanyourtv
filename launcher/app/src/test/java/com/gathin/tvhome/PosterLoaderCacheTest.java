package com.gathin.tvhome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.*;

public class PosterLoaderCacheTest {
    @Test public void diskHitDoesNotFetch() throws Exception {
        Path dir = Files.createTempDirectory("poster-loader");
        try {
            PosterStore store = new PosterStore(dir.toFile());
            byte[] bytes = {1, 2};
            store.put("poster", bytes);
            AtomicInteger calls = new AtomicInteger();
            String result = PosterLoader.fromSources("poster", store, (url, headers) -> {
                calls.incrementAndGet();
                return null;
            }, (body, width, height) -> Arrays.equals(bytes, body) ? "bitmap" : null, 320, 480);
            assertEquals("bitmap", result);
            assertEquals(0, calls.get());
        } finally {
            delete(dir);
        }
    }

    @Test public void corruptDiskEntryRefetches() throws Exception {
        Path dir = Files.createTempDirectory("poster-loader");
        try {
            PosterStore store = new PosterStore(dir.toFile());
            store.put("poster", new byte[] {1});
            AtomicInteger calls = new AtomicInteger();
            String result = PosterLoader.fromSources("poster", store, (url, headers) -> {
                calls.incrementAndGet();
                return new byte[] {2};
            }, (body, width, height) -> body[0] == 2 ? "bitmap" : null, 320, 480);
            assertEquals("bitmap", result);
            assertEquals(1, calls.get());
            assertArrayEquals(new byte[] {2}, store.read("poster"));
        } finally {
            delete(dir);
        }
    }

    @Test public void diskMissFetchesAndStoresRawBytes() throws Exception {
        Path dir = Files.createTempDirectory("poster-loader");
        try {
            PosterStore store = new PosterStore(dir.toFile());
            byte[] raw = {4, 5, 6};
            AtomicInteger calls = new AtomicInteger();
            String result = PosterLoader.fromSources("poster", store, (url, headers) -> {
                calls.incrementAndGet();
                return raw;
            }, (body, width, height) -> "bitmap", 320, 480);
            assertEquals("bitmap", result);
            assertEquals(1, calls.get());
            assertArrayEquals(raw, store.read("poster"));
        } finally {
            delete(dir);
        }
    }

    @Test public void failedFetchOrDecodeLeavesTextFallbackAndDoesNotStore() throws Exception {
        Path dir = Files.createTempDirectory("poster-loader");
        try {
            PosterStore store = new PosterStore(dir.toFile());
            PosterLoader.Decoder<String> decoder = (body, width, height) -> null;
            assertNull(PosterLoader.fromSources("missing", store, (url, headers) -> null,
                    decoder, 320, 480));
            assertNull(PosterLoader.fromSources("undecodable", store, (url, headers) -> new byte[] {1},
                    decoder, 320, 480));
            assertNull(store.read("missing"));
            assertNull(store.read("undecodable"));
            try (Stream<Path> files = Files.list(dir)) {
                assertEquals(0, files.count());
            }
        } finally {
            delete(dir);
        }
    }

    @Test public void optionalHeadersReachOnlyTheNetworkFetch() {
        java.util.Map<String, String> auth = java.util.Map.of("Authorization", "MediaBrowser Token=\"k\"");
        String result = PosterLoader.fromSources("https://jf.example/Items/a/Images/Primary", null,
                (url, headers) -> {
                    assertEquals(auth, headers);
                    return new byte[] {1};
                }, (body, width, height) -> "bitmap", 320, 480, auth);
        assertEquals("bitmap", result);
    }

    private static void delete(Path dir) throws Exception {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
}

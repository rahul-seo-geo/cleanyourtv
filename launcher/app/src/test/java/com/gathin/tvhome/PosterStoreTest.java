package com.gathin.tvhome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.*;

public class PosterStoreTest {
    @Test public void putThenReadAndMissingKey() throws Exception {
        Path dir = Files.createTempDirectory("poster-store");
        try {
            PosterStore store = new PosterStore(dir.toFile());
            byte[] bytes = {1, 2, 3};
            store.put("https://example.com/one", bytes);
            assertArrayEquals(bytes, store.read("https://example.com/one"));
            assertNull(store.read("https://example.com/missing"));
        } finally {
            delete(dir);
        }
    }

    @Test public void distinctKeysCreateDistinctFiles() throws Exception {
        Path dir = Files.createTempDirectory("poster-store");
        try {
            PosterStore store = new PosterStore(dir.toFile());
            store.put("first", new byte[] {1});
            store.put("second", new byte[] {2});
            assertArrayEquals(new byte[] {1}, store.read("first"));
            assertArrayEquals(new byte[] {2}, store.read("second"));
            try (Stream<Path> files = Files.list(dir)) {
                assertEquals(2, files.count());
            }
        } finally {
            delete(dir);
        }
    }

    @Test public void trimEvictsOldestAfterFortyNineEntries() throws Exception {
        Path dir = Files.createTempDirectory("poster-store");
        try {
            PosterStore store = new PosterStore(dir.toFile());
            store.put("oldest", new byte[] {1});
            try (Stream<Path> files = Files.list(dir)) {
                Files.setLastModifiedTime(files.findFirst().get(), FileTime.fromMillis(1000));
            }
            for (int i = 0; i < 48; i++) store.put("key-" + i, new byte[] {(byte) i});
            assertNull(store.read("oldest"));
            for (int i = 0; i < 48; i++) assertArrayEquals(new byte[] {(byte) i}, store.read("key-" + i));
            try (Stream<Path> files = Files.list(dir)) {
                assertEquals(48, files.count());
            }
        } finally {
            delete(dir);
        }
    }

    @Test public void trimKeepsJustWrittenEntryWhenTimestampsTie() throws Exception {
        Path dir = Files.createTempDirectory("poster-store");
        try {
            PosterStore store = new PosterStore(dir.toFile());
            for (int i = 0; i < 48; i++) store.put("key-" + i, new byte[] {1});
            try (Stream<Path> files = Files.list(dir)) {
                files.forEach(file -> {
                    try {
                        Files.setLastModifiedTime(file, FileTime.fromMillis(4_102_444_800_000L));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
            store.put("!new", new byte[] {2});
            assertArrayEquals(new byte[] {2}, store.read("!new"));
            try (Stream<Path> files = Files.list(dir)) {
                assertEquals(48, files.count());
            }
        } finally {
            delete(dir);
        }
    }

    @Test public void oversizedBodyIsNotStored() throws Exception {
        Path dir = Files.createTempDirectory("poster-store");
        try {
            PosterStore store = new PosterStore(dir.toFile());
            store.put("large", new byte[4 * 1024 * 1024 + 1]);
            assertNull(store.read("large"));
            byte[] oversized = new byte[4 * 1024 * 1024 + 1];
            Files.write(dir.resolve("6c61726765.bin"), oversized);
            assertNull(store.read("large"));
            try (Stream<Path> files = Files.list(dir)) {
                assertEquals(1, files.count());
            }
        } finally {
            delete(dir);
        }
    }

    @Test public void missingAndCorruptDirectoryFailSilently() throws Exception {
        Path root = Files.createTempDirectory("poster-store");
        try {
            Path missing = root.resolve("missing");
            PosterStore store = new PosterStore(missing.toFile());
            assertNull(store.read("key"));
            store.put("key", new byte[] {1});
            assertArrayEquals(new byte[] {1}, store.read("key"));
            Path corrupt = root.resolve("corrupt");
            Files.write(corrupt, new byte[] {1});
            PosterStore broken = new PosterStore(corrupt.toFile());
            assertNull(broken.read("key"));
            broken.put("key", new byte[] {1});
            assertNull(broken.read("key"));
        } finally {
            delete(root);
        }
    }

    private static void delete(Path dir) throws Exception {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
}

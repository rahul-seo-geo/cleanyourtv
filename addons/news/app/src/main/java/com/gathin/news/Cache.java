package com.gathin.news;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class Cache {
    static final long LIMIT = 20L * 1024 * 1024;
    private final File dir;

    Cache(Context context) {
        dir = new File(context.getFilesDir(), "cache");
        dir.mkdirs();
    }

    byte[] read(String key) {
        try {
            File file = file(key);
            if (!file.isFile()) return null;
            byte[] data = Files.readAllBytes(file.toPath());
            if (key.startsWith("poster-")) file.setLastModified(System.currentTimeMillis());
            return data;
        } catch (Exception e) { return null; }
    }

    void put(String key, byte[] bytes) {
        if (bytes == null || bytes.length > 4 * 1024 * 1024) return;
        try {
            Files.write(file(key).toPath(), bytes);
            File[] files = dir.listFiles();
            if (files != null) trim(new ArrayList<>(List.of(files)), LIMIT);
        } catch (Exception ignored) { }
    }

    long ageSeconds(String key) {
        long modified = file(key).lastModified();
        return modified == 0 ? -1 : Math.max(0, (System.currentTimeMillis() - modified) / 1000);
    }

    private File file(String key) { return new File(dir, key.replaceAll("[^a-zA-Z0-9_-]", "_") + ".bin"); }

    static void trim(List<File> files, long cap) {
        files.sort(Comparator.comparingLong(File::lastModified));
        long size = 0;
        for (File file : files) size += file.length();
        for (File file : files) {
            if (size <= cap) break;
            long length = file.length();
            if (file.delete()) size -= length;
        }
    }
}

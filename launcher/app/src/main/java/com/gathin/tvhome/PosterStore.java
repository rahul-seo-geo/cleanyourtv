package com.gathin.tvhome;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

final class PosterStore {
    private static final int MAX_ENTRIES = 48;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private final File dir;

    PosterStore(File dir) {
        this.dir = dir;
    }

    byte[] read(String key) {
        try {
            File file = new File(dir, name(key));
            return file.isFile() && file.length() <= MAX_BODY_BYTES
                    ? Files.readAllBytes(file.toPath()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    void put(String key, byte[] value) {
        if (value == null || value.length > MAX_BODY_BYTES) return;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File written = new File(dir, name(key));
            Files.write(written.toPath(), value);
            trim(written.getName());
        } catch (Exception ignored) { }
    }

    private void trim(String written) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_ENTRIES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        int excess = files.length - MAX_ENTRIES;
        for (File file : files) {
            if (excess == 0) break;
            if (!file.getName().equals(written) && file.delete()) excess--;
        }
    }

    private static String name(String key) {
        byte[] bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        char[] chars = new char[bytes.length * 2];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            chars[2 * i] = hex[(bytes[i] & 0xff) >>> 4];
            chars[2 * i + 1] = hex[bytes[i] & 0x0f];
        }
        return new String(chars) + ".bin";
    }
}

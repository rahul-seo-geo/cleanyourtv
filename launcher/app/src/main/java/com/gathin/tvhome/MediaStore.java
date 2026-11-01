package com.gathin.tvhome;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

final class MediaStore implements MediaLoader.Store {
    private static final int MAX_ENTRIES = 16;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private final File dir;

    MediaStore(Context context) {
        dir = new File(context.getFilesDir(), "media-cache");
    }

    @Override public byte[] read(String key) {
        try {
            File file = new File(dir, name(key));
            return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override public void put(String key, byte[] value) {
        if (value == null || value.length > MAX_BODY_BYTES) return;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            Files.write(new File(dir, name(key)).toPath(), value);
            trim();
        } catch (Exception ignored) { }
    }

    @Override public long ageSeconds(String key) {
        long modified = new File(dir, name(key)).lastModified();
        return modified <= 0 ? -1 : Math.max(0, (System.currentTimeMillis() - modified) / 1000);
    }

    private void trim() {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_ENTRIES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_ENTRIES; i++) files[i].delete();
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

package com.gathin.tvhome;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** dashboard.txt in the launcher's private storage (pushed by setup.sh, never in git). */
final class MediaSettings {
    private static final int DEFAULT_REFRESH_MINUTES = 15;
    private final Map<String, String> values;

    private MediaSettings(Map<String, String> values) { this.values = values; }

    static MediaSettings parse(String text) {
        Map<String, String> values = new HashMap<>();
        if (text == null) return new MediaSettings(values);
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            String line = text.substring(start, end).trim();
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1).trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    String key = line.substring(0, equals).trim();
                    String val = line.substring(equals + 1).trim();
                    if (key.endsWith("_url") && val.endsWith("//")) {
                        while (val.endsWith("/")) {
                            val = val.substring(0, val.length() - 1);
                        }
                    }
                    values.put(key, val);
                }
            }
            if (end >= text.length()) break;
            start = end + 1;
        }
        return new MediaSettings(values);
    }

    static MediaSettings read(Context context) {
        try {
            return parse(new String(Files.readAllBytes(
                    new File(context.getFilesDir(), "dashboard.txt").toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return parse("");
        }
    }

    String get(String key) { return values.getOrDefault(key, ""); }

    /** Any configured source keeps the band on screen; a blank file keeps Home exactly as today. */
    boolean anySource() {
        return !get("seerr_url").isEmpty() || !get("m3u_url").isEmpty() || !get("game_day_pkg").isEmpty();
    }

    int refreshMinutes() {
        try {
            int parsed = Integer.parseInt(get("refresh_minutes").trim());
            return Math.max(1, parsed);
        } catch (NumberFormatException e) {
            return DEFAULT_REFRESH_MINUTES;
        }
    }
}

package com.gathin.newin;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

final class Settings {
    private final Map<String, String> values;

    private Settings(Map<String, String> values) { this.values = values; }

    static Settings parse(String text) {
        Map<String, String> values = new HashMap<>();
        for (String line : text.split("\\r?\\n|\\r")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals > 0) values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
        }
        return new Settings(values);
    }

    static Settings read(Context context) {
        try {
            return parse(new String(Files.readAllBytes(new File(context.getFilesDir(), "settings.txt").toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return parse("");
        }
    }

    String get(String key) { return values.getOrDefault(key, ""); }
    String sportsApp() { return values.getOrDefault("live_sports_app", "com.cricfytv.sports"); }
}

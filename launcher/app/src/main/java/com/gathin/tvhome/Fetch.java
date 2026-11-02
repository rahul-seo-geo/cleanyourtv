package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

final class Fetch implements MediaLoader.Fetcher {
    @Override public byte[] get(String url, Map<String, String> headers) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            // Browser-like default: Cloudflare-fronted APIs (TheSportsDB) answer 403 without one.
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko)"
                            + " Chrome/126.0.0.0 Mobile Safari/537.36");
            connection.setRequestProperty("Accept", "application/json,*/*");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            if (connection.getResponseCode() != 200) {
                android.util.Log.w("tvhome", "fetch non-200 " + connection.getResponseCode() + " " + url);
                return null;
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > 4 * 1024 * 1024) return null;
                }
                return output.toByteArray();
            }
        } catch (Exception e) {
            android.util.Log.w("tvhome", "fetch failed " + url + ": " + e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}

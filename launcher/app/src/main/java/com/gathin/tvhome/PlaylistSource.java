package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;

/** m3u playlist parsing for the Live tab: plain char loops, every input bounded, no regex. */
final class PlaylistSource {
    private static final int MAX_LINE = 2048;
    private static final int MAX_TITLE = 64;
    private static final int MAX_GROUP = 32;
    private static final int MAX_URL = 1024;

    static List<MediaCard> parse(String text, int cap) {
        List<MediaCard> cards = new ArrayList<>();
        if (text == null || cap <= 0) return cards;
        int i = 0;
        String pendingName = null, pendingGroup = "";
        while (i < text.length() && cards.size() < cap) {
            int end = text.indexOf('\n', i);
            if (end < 0) end = text.length();
            String line = text.substring(i, end);
            int stop = line.length();
            if (stop > 0 && line.charAt(stop - 1) == '\r') stop--;
            if (stop > MAX_LINE) { pendingName = null; i = end + 1; continue; }
            line = line.substring(0, stop).trim();
            if (line.isEmpty()) { i = end + 1; continue; }
            if (line.startsWith("#EXTINF:")) {
                int comma = line.lastIndexOf(',');
                pendingName = comma >= 0 && comma + 1 < line.length()
                        ? bound(line.substring(comma + 1).trim(), MAX_TITLE) : "";
                pendingGroup = bound(attribute(line, "group-title"), MAX_GROUP);
            } else if (line.startsWith("#")) {
                // Any other tag sits between EXTINF and its URL: keep the pending pair alive.
            } else if (pendingName != null) {
                String url = line.length() <= MAX_URL && line.contains("://") ? line : null;
                if (url != null) {
                    String name = pendingName.isEmpty()
                            ? bound(url.substring(url.lastIndexOf('/') + 1), MAX_TITLE) : pendingName;
                    cards.add(new MediaCard(url, MediaCard.Kind.LIVE, name, pendingGroup,
                            MediaCard.State.AVAILABLE, 0, 0, "", url));
                }
                pendingName = null;
                pendingGroup = "";
            }
            i = end + 1;
        }
        return cards;
    }

    /** value of key="value" inside an EXTINF tag, located by scanning; "" when absent. */
    private static String attribute(String line, String key) {
        int at = line.indexOf(key + "=\"");
        if (at < 0) return "";
        int from = at + key.length() + 2;
        int to = line.indexOf('"', from);
        return to < 0 ? "" : line.substring(from, to);
    }

    private static String bound(String value, int max) {
        if (value.length() <= max) return value;
        return value.substring(0, max - 1) + "…";
    }
}

package com.gathin.news;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class DashboardFormat {

    private DashboardFormat() { }

    static String ageLabel(long nowMs, long publishedMs) {
        if (publishedMs <= 0 || nowMs < publishedMs) return "";
        long minutes = (nowMs - publishedMs) / 60000L;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " h ago";
        return hours / 24 + " d ago";
    }

    static String releaseLabel(long releaseMs, Locale locale) {
        if (releaseMs <= 0) return "";
        return "releases " + new SimpleDateFormat("d MMM yyyy", locale).format(new Date(releaseMs));
    }

    static String mediaMeta(long releaseMs, boolean cached, Locale locale) {
        String label = releaseLabel(releaseMs, locale);
        if (label.isEmpty()) return cached ? "cached" : label;
        return cached ? "cached · " + label : label;
    }

    static String storyMeta(String source, boolean cached, long nowMs, long publishedMs) {
        String label = source == null ? "" : source.trim();
        String age = ageLabel(nowMs, publishedMs);
        String joined = age.isEmpty() ? label : label.isEmpty() ? age : label + " · " + age;
        if (joined.isEmpty()) return cached ? "cached" : "";
        return cached ? "cached · " + joined : joined;
    }

    static String chipLabel(String sourceKey, DashboardSnapshot.Freshness freshness, long nowMs, long updatedMs) {
        String label = sourceLabel(sourceKey);
        String state = chipState(freshness, nowMs, updatedMs);
        return label.isEmpty() ? state : state.isEmpty() ? label : label + " · " + state;
    }

    static String chipState(DashboardSnapshot.Freshness freshness, long nowMs, long updatedMs) {
        if (freshness == DashboardSnapshot.Freshness.FRESH) return "fresh";
        if (freshness == DashboardSnapshot.Freshness.UNAVAILABLE) return "offline";
        String age = ageShort(nowMs, updatedMs);
        return age.isEmpty() ? "cached" : "cached " + age;
    }

    static String ageShort(long nowMs, long updatedMs) {
        if (updatedMs <= 0 || nowMs < updatedMs) return "";
        long minutes = (nowMs - updatedMs) / 60000L;
        if (minutes < 1) return "now";
        if (minutes < 60) return minutes + "m";
        if (minutes < 1440) return minutes / 60 + "h";
        return minutes / 1440 + "d";
    }

    static String sourceLabel(String key) {
        if (key == null) return "";
        if (key.startsWith("feed")) {
            String digits = key.substring(4).trim();
            return digits.isEmpty() ? "Feed" : "Feed " + digits;
        }
        switch (key) {
            case "upcoming-movies": return "Film";
            case "upcoming-tv": return "Series";
            case "game_day": return "Matches";
            default: return prettify(key);
        }
    }

    static String fixtureStatus(DashboardSnapshot.MatchSlot match, long nowMs) {
        String status;
        if (match.phase == DashboardSnapshot.MatchPhase.LIVE)
            status = match.scoreOrCountdown == null ? "in play" : match.scoreOrCountdown;
        else if (match.phase == DashboardSnapshot.MatchPhase.UPCOMING)
            status = match.scoreOrCountdown == null ? countdownLabel(match.startMs, nowMs)
                    : match.scoreOrCountdown;
        else if (match.phase == DashboardSnapshot.MatchPhase.FINAL)
            status = match.scoreOrCountdown == null ? "full time" : "FT " + match.scoreOrCountdown;
        else status = "score unknown";
        if (match.freshness == DashboardSnapshot.Freshness.STALE) return status + " · cached";
        return status;
    }

    static String countdownLabel(long startMs, long nowMs) {
        if (startMs <= 0 || nowMs >= startMs) return "soon";
        long minutes = (startMs - nowMs) / 60000L;
        if (minutes < 60) return "in " + minutes + "m";
        if (minutes < 1440) return "in " + minutes / 60 + "h " + minutes % 60 + "m";
        return "in " + minutes / 1440 + "d";
    }

    static String matchStripLabel(boolean unavailable) {
        return unavailable ? "Match data unavailable" : "No matches scheduled today";
    }

    static String truncate(String text, int maxChars) {
        if (text == null) return "";
        String trimmed = text.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= maxChars) return trimmed;
        String cut = trimmed.substring(0, maxChars);
        int space = cut.lastIndexOf(' ');
        return (space > maxChars / 2 ? cut.substring(0, space) : cut) + "…";
    }

    private static String prettify(String key) {
        String[] words = key.replace('-', ' ').replace('_', ' ').trim().split("\\s+");
        StringBuilder text = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (text.length() > 0) text.append(' ');
            text.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return text.toString();
    }
}

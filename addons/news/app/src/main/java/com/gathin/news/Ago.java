package com.gathin.news;

final class Ago {
    static String text(long seconds) {
        if (seconds < 0) return "never updated";
        if (seconds < 60) return "updated just now";
        if (seconds < 3600) return "updated " + seconds / 60 + "m ago";
        if (seconds < 86400) return "updated " + seconds / 3600 + "h ago";
        return "updated " + seconds / 86400 + "d ago";
    }
}

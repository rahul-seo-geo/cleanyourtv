package com.gathin.news;

final class RefreshScheduler {

    interface Commands {
        void runLoad(long requestId);
        void scheduleRefresh(long delayMs);
        void cancelScheduledRefresh();
    }

    static final long MIN_INTERVAL_MS = 60_000L;
    static final long DEFAULT_INTERVAL_MS = 15 * 60_000L;

    private final Commands commands;
    private final long intervalMs;
    private boolean attached;
    private boolean loadInFlight;
    private long latestRequestId;

    RefreshScheduler(Commands commands, long intervalMs) {
        this.commands = commands;
        this.intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs);
    }

    static long intervalMs(String configuredMinutes) {
        long minutes;
        try {
            minutes = Long.parseLong(configuredMinutes.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_INTERVAL_MS;
        }
        if (minutes <= 0) return DEFAULT_INTERVAL_MS;
        return Math.max(MIN_INTERVAL_MS, minutes * 60_000L);
    }

    synchronized void attach() {
        if (attached) return;
        attached = true;
        latestRequestId++;
        loadInFlight = true;
        commands.runLoad(latestRequestId);
    }

    synchronized void detach() {
        if (!attached) return;
        attached = false;
        loadInFlight = false;
        latestRequestId++;
        commands.cancelScheduledRefresh();
    }

    synchronized void refreshDue() {
        if (!attached || loadInFlight) return;
        latestRequestId++;
        loadInFlight = true;
        commands.runLoad(latestRequestId);
    }

    synchronized void loadFinished(long requestId) {
        if (!attached || !loadInFlight || requestId != latestRequestId) return;
        loadInFlight = false;
        commands.scheduleRefresh(intervalMs);
    }

    synchronized boolean shouldPublish(long requestId) {
        return attached && requestId == latestRequestId;
    }
}

package com.gathin.news;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RefreshSchedulerTest {

    private static final class Recording implements RefreshScheduler.Commands {
        final List<String> calls = new ArrayList<>();
        long lastDelayMs;

        @Override public void runLoad(long requestId) { calls.add("load:" + requestId); }
        @Override public void scheduleRefresh(long delayMs) {
            calls.add("schedule");
            lastDelayMs = delayMs;
        }
        @Override public void cancelScheduledRefresh() { calls.add("cancel"); }
    }

    @Test public void attachStartsExactlyOneLoad() {
        Recording commands = new Recording();
        RefreshScheduler scheduler = new RefreshScheduler(commands, 300_000);
        scheduler.attach();
        scheduler.attach();
        assertEquals(List.of("load:1"), commands.calls);
    }

    @Test public void nextRefreshScheduledOnlyAfterLoadFinishesWhileAttached() {
        Recording commands = new Recording();
        RefreshScheduler scheduler = new RefreshScheduler(commands, 300_000);
        scheduler.attach();
        assertFalse(commands.calls.contains("schedule"));
        scheduler.loadFinished(1);
        assertEquals(List.of("load:1", "schedule"), commands.calls);
        assertEquals(300_000, commands.lastDelayMs);
    }

    @Test public void refreshDueStartsNextLoadOnlyAfterPreviousFinished() {
        Recording commands = new Recording();
        RefreshScheduler scheduler = new RefreshScheduler(commands, 300_000);
        scheduler.attach();
        scheduler.refreshDue();
        assertEquals(List.of("load:1"), commands.calls);
        scheduler.loadFinished(1);
        scheduler.refreshDue();
        assertEquals(List.of("load:1", "schedule", "load:2"), commands.calls);
    }

    @Test public void detachCancelsScheduledRefreshAndFutureLoads() {
        Recording commands = new Recording();
        RefreshScheduler scheduler = new RefreshScheduler(commands, 300_000);
        scheduler.attach();
        scheduler.loadFinished(1);
        scheduler.detach();
        scheduler.refreshDue();
        scheduler.loadFinished(1);
        assertFalse(scheduler.shouldPublish(1));
        assertEquals(List.of("load:1", "schedule", "cancel"), commands.calls);
    }

    @Test public void staleResultNeitherPublishesNorReschedules() {
        Recording commands = new Recording();
        RefreshScheduler scheduler = new RefreshScheduler(commands, 300_000);
        scheduler.attach();
        scheduler.detach();
        scheduler.attach();
        assertFalse(scheduler.shouldPublish(1));
        assertFalse(scheduler.shouldPublish(2));
        assertTrue(scheduler.shouldPublish(3));
        scheduler.loadFinished(1);
        assertFalse(commands.calls.contains("schedule"));
        scheduler.loadFinished(3);
        assertEquals(List.of("load:1", "cancel", "load:3", "schedule"), commands.calls);
    }

    @Test public void clampsIntervalToSafeLowerBound() {
        Recording commands = new Recording();
        RefreshScheduler scheduler = new RefreshScheduler(commands, 5_000);
        scheduler.attach();
        scheduler.loadFinished(1);
        assertEquals(RefreshScheduler.MIN_INTERVAL_MS, commands.lastDelayMs);
    }

    @Test public void parsesConfiguredMinutesWithBounds() {
        assertEquals(300_000, RefreshScheduler.intervalMs("5"));
        assertEquals(300_000, RefreshScheduler.intervalMs(" 5 "));
        assertEquals(RefreshScheduler.MIN_INTERVAL_MS, RefreshScheduler.intervalMs("1"));
        assertEquals(RefreshScheduler.DEFAULT_INTERVAL_MS, RefreshScheduler.intervalMs(""));
        assertEquals(RefreshScheduler.DEFAULT_INTERVAL_MS, RefreshScheduler.intervalMs("0"));
        assertEquals(RefreshScheduler.DEFAULT_INTERVAL_MS, RefreshScheduler.intervalMs("junk"));
    }
}

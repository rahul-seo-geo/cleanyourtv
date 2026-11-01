package com.gathin.news;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LauncherTargetsTest {

    @Test public void parsesRowKeysInNumericOrderWithOptionalLabels() {
        Settings settings = Settings.parse(
                "row2=net.example.two|Two\nrow1=net.example.one\nrow10=net.example.ten");
        List<LauncherTargets.Target> targets = LauncherTargets.parse(settings, 6);
        assertEquals(3, targets.size());
        assertEquals("net.example.one", targets.get(0).pkg);
        assertNull(targets.get(0).label);
        assertEquals("net.example.two", targets.get(1).pkg);
        assertEquals("Two", targets.get(1).label);
        assertEquals("net.example.ten", targets.get(2).pkg);
    }

    @Test public void skipsBlankValuesAndCapsAtMaxSlots() {
        Settings settings = Settings.parse(
                "row1=\nrow2=net.example.two\nrow3=net.example.three\nrow4=net.example.four");
        List<LauncherTargets.Target> targets = LauncherTargets.parse(settings, 2);
        assertEquals(2, targets.size());
        assertEquals("net.example.two", targets.get(0).pkg);
        assertEquals("net.example.three", targets.get(1).pkg);
    }

    @Test public void zeroConfiguredRowsYieldEmptyList() {
        Settings settings = Settings.parse("feed1=https://example.com/feed\njellyseerr_url=https://x");
        assertTrue(LauncherTargets.parse(settings, 6).isEmpty());
    }

    @Test public void filterKeepsOnlyLaunchableTargetsInOrder() {
        List<LauncherTargets.Target> configured = LauncherTargets.parse(
                Settings.parse("row1=net.one\nrow2=net.two\nrow3=net.three"), 6);
        List<LauncherTargets.Target> valid = LauncherTargets.filter(configured,
                pkg -> !pkg.equals("net.two"));
        assertEquals(2, valid.size());
        assertEquals("net.one", valid.get(0).pkg);
        assertEquals("net.three", valid.get(1).pkg);
    }

    @Test public void filterDropsEverythingWhenNothingIsLaunchable() {
        List<LauncherTargets.Target> configured = LauncherTargets.parse(
                Settings.parse("row1=net.one\nrow2=net.two"), 6);
        assertTrue(LauncherTargets.filter(configured, pkg -> false).isEmpty());
    }
}

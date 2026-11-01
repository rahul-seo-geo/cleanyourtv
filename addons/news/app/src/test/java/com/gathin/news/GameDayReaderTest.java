package com.gathin.news;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameDayReaderTest {
    private static final long NOW = 1_000_000L;

    private static Object[] row(String topLine, Object start, Object end, Object seconds) {
        return new Object[] {topLine, "Puck drop", start, end, 0xFF123456, "calendar", seconds};
    }

    @Test public void futureStartIsUpcomingWithoutFabricatedScore() {
        GameDayReader.GameDayResult result = GameDayReader.mapRows(
                Collections.singletonList(row("Wild at Stars", NOW + 1000, NOW + 2000, 30)), NOW);
        assertEquals(GameDayReader.State.AVAILABLE, result.state);
        assertEquals(1, result.matches.size());
        DashboardSnapshot.MatchSlot match = result.matches.get(0);
        assertEquals(DashboardSnapshot.MatchPhase.UPCOMING, match.phase);
        assertEquals(DashboardSnapshot.Freshness.FRESH, match.freshness);
        assertNull(match.scoreOrCountdown);
        assertEquals("Wild at Stars", match.topLine);
        assertEquals(30, result.turnSeconds);
    }

    @Test public void currentIntervalIsFreshAndLive() {
        GameDayReader.GameDayResult result = GameDayReader.mapRows(
                Collections.singletonList(row("Wild at Stars", NOW - 1000, NOW + 1000, 30)), NOW);
        assertEquals(DashboardSnapshot.MatchPhase.LIVE, result.matches.get(0).phase);
        assertEquals(DashboardSnapshot.Freshness.FRESH, result.matches.get(0).freshness);
        assertNull(result.matches.get(0).scoreOrCountdown);
    }

    @Test public void endedRowWithoutExplicitFinalIsOmitted() {
        GameDayReader.GameDayResult result = GameDayReader.mapRows(
                Collections.singletonList(row("Wild at Stars", NOW - 2000, NOW, 30)), NOW);
        assertEquals(GameDayReader.State.EMPTY, result.state);
        assertTrue(result.matches.isEmpty());
    }

    @Test public void missingTopLineIsSkipped() {
        GameDayReader.GameDayResult result = GameDayReader.mapRows(
                Collections.singletonList(row(null, NOW + 1000, NOW + 2000, 30)), NOW);
        assertEquals(GameDayReader.State.EMPTY, result.state);
    }

    @Test public void invalidTurnSecondsUsesLauncherDefault() {
        GameDayReader.GameDayResult result = GameDayReader.mapRows(
                Arrays.asList(row("A", NOW + 1000, NOW + 2000, 9),
                        row("B", NOW + 2000, NOW + 3000, 120)), NOW);
        assertEquals(30, result.turnSeconds);
        assertEquals(2, result.matches.size());
    }

    @Test public void emptyRowsAreEmptyNotUnavailable() {
        assertEquals(GameDayReader.State.EMPTY, GameDayReader.mapRows(Collections.emptyList(), NOW).state);
    }

    @Test public void malformedTimeIsUnavailable() {
        assertEquals(GameDayReader.State.UNAVAILABLE, GameDayReader.mapRows(
                Collections.singletonList(row("A", "bad", NOW + 1000, 30)), NOW).state);
    }

    @Test public void providerFailureIsUnavailableAndNeverReturnsOldRows() {
        GameDayReader.ProviderSource source = new GameDayReader.ProviderSource() {
            @Override public String authorityFor(String pkg) { return "com.example.games"; }
            @Override public String ownerFor(String authority) { return "com.example"; }
            @Override public java.util.List<Object[]> rows(String authority) { throw new SecurityException("denied"); }
        };
        assertEquals(GameDayReader.State.UNAVAILABLE,
                GameDayReader.read("com.example", source, NOW).state);
    }

    @Test public void runtimeQueryFailureIsUnavailable() {
        GameDayReader.ProviderSource source = new GameDayReader.ProviderSource() {
            @Override public String authorityFor(String pkg) { return "com.example.games"; }
            @Override public String ownerFor(String authority) { return "com.example"; }
            @Override public java.util.List<Object[]> rows(String authority) { throw new IllegalArgumentException("gone"); }
        };
        assertEquals(GameDayReader.State.UNAVAILABLE,
                GameDayReader.read("com.example", source, NOW).state);
    }

    @Test public void missingMetadataIsUnavailableWithoutQuery() {
        GameDayReader.ProviderSource source = new GameDayReader.ProviderSource() {
            @Override public String authorityFor(String pkg) { return null; }
            @Override public String ownerFor(String authority) { fail("must not resolve"); return null; }
            @Override public java.util.List<Object[]> rows(String authority) { fail("must not query"); return null; }
        };
        assertEquals(GameDayReader.State.UNAVAILABLE,
                GameDayReader.read("com.example", source, NOW).state);
    }

    @Test public void nullCursorIsUnavailable() {
        GameDayReader.ProviderSource source = new GameDayReader.ProviderSource() {
            @Override public String authorityFor(String pkg) { return "com.example.games"; }
            @Override public String ownerFor(String authority) { return "com.example"; }
            @Override public java.util.List<Object[]> rows(String authority) { return null; }
        };
        assertEquals(GameDayReader.State.UNAVAILABLE,
                GameDayReader.read("com.example", source, NOW).state);
    }

    @Test public void validProviderRowsAreMapped() {
        GameDayReader.ProviderSource source = new GameDayReader.ProviderSource() {
            @Override public String authorityFor(String pkg) { return "com.example.games"; }
            @Override public String ownerFor(String authority) { return "com.example"; }
            @Override public java.util.List<Object[]> rows(String authority) {
                assertEquals("com.example.games", authority);
                return Collections.singletonList(row("Wild at Stars", NOW + 1000, NOW + 2000, 30));
            }
        };
        GameDayReader.GameDayResult result = GameDayReader.read("com.example", source, NOW);
        assertEquals(GameDayReader.State.AVAILABLE, result.state);
        assertEquals(DashboardSnapshot.MatchPhase.UPCOMING, result.matches.get(0).phase);
    }

    @Test public void providerOwnerMustMatchConfiguredPackage() {
        GameDayReader.ProviderSource source = new GameDayReader.ProviderSource() {
            @Override public String authorityFor(String pkg) { return "com.example.games"; }
            @Override public String ownerFor(String authority) { return "com.other"; }
            @Override public java.util.List<Object[]> rows(String authority) { fail("must not query"); return null; }
        };
        assertEquals(GameDayReader.State.UNAVAILABLE,
                GameDayReader.read("com.example", source, NOW).state);
    }
}

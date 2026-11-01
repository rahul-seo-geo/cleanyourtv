package com.gathin.news;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class GameDayReader {
    private static final String META = "com.gathin.tvhome.games";
    private static final String[] COLUMNS = {"top_line", "word", "start_ms", "end_ms", "colour", "calendar", "turn_seconds"};
    private static final int TURN_DEFAULT = 30;

    enum State { AVAILABLE, EMPTY, UNAVAILABLE }

    static final class GameDayResult {
        final State state;
        final List<DashboardSnapshot.MatchSlot> matches;
        final int turnSeconds;

        GameDayResult(State state, List<DashboardSnapshot.MatchSlot> matches, int turnSeconds) {
            this.state = state;
            this.matches = Collections.unmodifiableList(new ArrayList<>(matches));
            this.turnSeconds = turnSeconds;
        }
    }

    interface ProviderSource {
        String authorityFor(String pkg) throws PackageManager.NameNotFoundException;
        String ownerFor(String authority);
        List<Object[]> rows(String authority);
    }

    static GameDayResult read(Context context, long nowMs) {
        try {
            return read(Settings.read(context).get("game_day"), new AndroidSource(context), nowMs);
        } catch (RuntimeException failure) {
            return unavailable();
        }
    }

    static GameDayResult read(String pkg, ProviderSource source, long nowMs) {
        if (pkg == null || pkg.trim().isEmpty()) return unavailable();
        try {
            String packageName = pkg.trim();
            String authority = source.authorityFor(packageName);
            if (authority == null || authority.trim().isEmpty()) return unavailable();
            authority = authority.trim();
            if (!packageName.equals(source.ownerFor(authority))) return unavailable();
            List<Object[]> rows = source.rows(authority);
            return rows == null ? unavailable() : mapRows(rows, nowMs);
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
            return unavailable();
        }
    }

    static GameDayResult mapRows(List<Object[]> rows, long nowMs) {
        if (rows == null) return unavailable();
        int turnSeconds = TURN_DEFAULT;
        if (!rows.isEmpty()) {
            Object[] first = rows.get(0);
            if (first == null || first.length < COLUMNS.length) return unavailable();
            if (first[6] instanceof Number) {
                int seconds = ((Number) first[6]).intValue();
                if (seconds >= 10 && seconds <= 120) turnSeconds = seconds;
            }
        }
        List<DashboardSnapshot.MatchSlot> matches = new ArrayList<>();
        for (Object[] row : rows) {
            if (row == null || row.length < COLUMNS.length) return unavailable();
            if (!(row[0] instanceof String) || ((String) row[0]).trim().isEmpty()) continue;
            if (!(row[2] instanceof Number) || !(row[3] instanceof Number)) return unavailable();
            long start = ((Number) row[2]).longValue();
            long end = ((Number) row[3]).longValue();
            if (start < 0 || end <= start) return unavailable();
            if (nowMs >= end) continue;
            int color = row[4] instanceof Number ? ((Number) row[4]).intValue() : 0xFFFFFFFF;
            DashboardSnapshot.MatchPhase phase = nowMs < start
                    ? DashboardSnapshot.MatchPhase.UPCOMING : DashboardSnapshot.MatchPhase.LIVE;
            matches.add(new DashboardSnapshot.MatchSlot(
                    ((String) row[0]).trim() + "|" + start + "|" + end,
                    ((String) row[0]).trim(), phase, null, start, end, color,
                    DashboardSnapshot.Freshness.FRESH));
        }
        return new GameDayResult(matches.isEmpty() ? State.EMPTY : State.AVAILABLE, matches, turnSeconds);
    }

    private static GameDayResult unavailable() {
        return new GameDayResult(State.UNAVAILABLE, Collections.emptyList(), TURN_DEFAULT);
    }

    static Object[] rowFromCursor(Cursor cursor) {
        return new Object[] {cursor.getString(0), cursor.getString(1),
                cursor.getType(2) == Cursor.FIELD_TYPE_INTEGER ? cursor.getLong(2) : null,
                cursor.getType(3) == Cursor.FIELD_TYPE_INTEGER ? cursor.getLong(3) : null,
                cursor.getInt(4), cursor.getString(5), cursor.getInt(6)};
    }

    private static final class AndroidSource implements ProviderSource {
        private final Context context;
        private final PackageManager pm;

        AndroidSource(Context context) {
            this.context = context;
            this.pm = context.getPackageManager();
        }

        @Override public String authorityFor(String pkg) throws PackageManager.NameNotFoundException {
            ApplicationInfo info = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            return info.metaData == null ? null : info.metaData.getString(META);
        }

        @Override public String ownerFor(String authority) {
            ProviderInfo info = pm.resolveContentProvider(authority, 0);
            return info == null ? null : info.packageName;
        }

        @Override public List<Object[]> rows(String authority) {
            Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                    .authority(authority).appendPath("turns").build();
            try (Cursor cursor = context.getContentResolver().query(uri, COLUMNS, null, null, null)) {
                if (cursor == null) return null;
                List<Object[]> rows = new ArrayList<>();
                while (cursor.moveToNext()) {
                    rows.add(rowFromCursor(cursor));
                }
                return rows;
            }
        }
    }
}

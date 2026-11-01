# Media Dashboard on Launcher Home — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A dashboard band on the launcher Home screen with Movies / Series / Sports / Live categories, fed by Seerr, the Game Day provider, and a self-hosted m3u playlist.

**Architecture:** New isolated classes beside `GameDay.java` in the launcher (`com.gathin.tvhome`): an immutable `MediaSnapshot` model, a `MediaLoader` orchestrating three per-source clients (`SeerrSource`, `PlaylistSource`, `SportsSource`) on a single-thread executor, and a `MediaBand` plain-view layer inflated into `activity_main.xml`. Patterns are ported from the News add-on (`addons/news/app/src/main/java/com/gathin/news/`): `Settings`/`FilesProvider`/`Cache`/`DashboardLoader` shapes. No launcher↔add-on contract changes.

**Tech Stack:** Plain Java Android (compileSdk 34, minSdk 26, targetSdk 34, JDK 17), no new runtime dependencies, JUnit 4.13.2 (+ `org.json:json:20231013` test-only, matching the News add-on), HttpURLConnection, org.json on device.

**Spec:** `docs/superpowers/specs/2026-11-01-media-dashboard-launcher-design.md`

## Global Constraints

- Plain Android framework views in the band; no new AndroidX usage, no web rendering, no new runtime deps.
- No regular expressions anywhere in parsing — plain character loops only (ICU/regex rule from `Cards.java`, `launcher/app/src/main/java/com/gathin/tvhome/Cards.java:27`).
- Every parser input is bounded: line lengths, list caps, string field caps as given per task.
- Offline by default: with no `dashboard.txt` in private storage, the band is absent and Home is byte-for-byte today's behavior.
- No secrets in the repo, in chat, or in logs. `dashboard.txt` is gitignored; example file ships with blanks.
- Truthful freshness: FRESH / STALE(+age) / UNAVAILABLE per source; cached live data is never shown as current.
- Game Day provider contract (`GameDay.java`) is read, never modified.
- Build/test commands run from `launcher/` with JDK 17 + Android SDK 34: `./gradlew test assembleRelease` (Git Bash: `./gradlew`; PowerShell: `.\gradlew.bat`).
- Tests are plain JUnit (no Robolectric): Android framework classes never appear in tested logic; fakes follow `DashboardLoaderTest` (`addons/news/app/src/test/java/com/gathin/news/DashboardLoaderTest.java`).
- Copy is English, sentence case; layout conventions follow `item_card_row.xml` (`@string/font_medium`, `@string/font_light`, `@string/font_body`, inline ARGB hex, `letterSpacing`).
- Commit style: `feat(launcher): …` / `test(launcher): …`, one commit per task, from the repo root `C:/Users/rahul/Projects/cleanyourtv`.
- Deviation from spec, already accepted during planning: v1 cards are typographic (title + status + accent); no remote poster fetching. Seerr's on-device image route is unverified, so the band does not guess one.

---

### Task 1: MediaSettings — the dashboard settings reader

**Files:**
- Create: `launcher/app/src/main/java/com/gathin/tvhome/MediaSettings.java`
- Test: `launcher/app/src/test/java/com/gathin/tvhome/MediaSettingsTest.java`

**Interfaces:**
- Produces: `static MediaSettings parse(String text)`, `static MediaSettings read(android.content.Context context)` (reads `filesDir/dashboard.txt`), `String get(String key)`, `boolean anySource()`. Keys: `seerr_url`, `seerr_key`, `jellyfin_url`, `jellyfin_key`, `jellyfin_package`, `player_package`, `m3u_url`, `refresh_minutes`.

- [ ] **Step 1: Write the failing test**

```java
package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaSettingsTest {
    @Test public void parsesKeysSkipsCommentsAndBlankLines() {
        MediaSettings s = MediaSettings.parse(
                "# dashboard sources\n"
                + "seerr_url = https://seerr.example/\n"
                + "\n"
                + "m3u_url=http://nas:8096/playlist.m3u\n");
        assertEquals("https://seerr.example/", s.get("seerr_url"));
        assertEquals("http://nas:8096/playlist.m3u", s.get("m3u_url"));
        assertEquals("", s.get("seerr_key"));
    }

    @Test public void trimsTrailingSlashesFromUrls() {
        assertEquals("https://seerr.example", MediaSettings.parse("seerr_url=https://seerr.example///").get("seerr_url"));
    }

    @Test public void anySourceFalseWhenEmptyAndTruePerSource() {
        assertFalse(MediaSettings.parse("").anySource());
        assertTrue(MediaSettings.parse("seerr_url=https://seerr.example").anySource());
        assertTrue(MediaSettings.parse("m3u_url=http://nas/playlist.m3u").anySource());
        assertTrue(MediaSettings.parse("game_day_pkg=com.gathin.game").anySource());
    }

    @Test public void refreshMinutesClampedToOneMinimum() {
        assertEquals(15, MediaSettings.parse("").refreshMinutes());
        assertEquals(1, MediaSettings.parse("refresh_minutes=0").refreshMinutes());
        assertEquals(7, MediaSettings.parse("refresh_minutes=7").refreshMinutes());
        assertEquals(15, MediaSettings.parse("refresh_minutes=bogus").refreshMinutes());
    }

    @Test public void blankTextYieldsAllEmptyButNotNull() {
        MediaSettings s = MediaSettings.parse(null);
        assertEquals("", s.get("seerr_url"));
        assertFalse(s.anySource());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.MediaSettingsTest"`
Expected: compile error, `MediaSettings` does not exist.

- [ ] **Step 3: Write minimal implementation**

Port `addons/news/app/src/main/java/com/gathin/news/Settings.java` (same parse loop — `indexOf('=')`, trim, skip `#`/blank; note the News file's `split` is acceptable here because it is not parsing untrusted input, but follow the launcher's no-regex rule and split with a plain char loop instead):

```java
package com.gathin.tvhome;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** dashboard.txt in the launcher's private storage (pushed by setup.sh, never in git). */
final class MediaSettings {
    private static final int DEFAULT_REFRESH_MINUTES = 15;
    private final Map<String, String> values;

    private MediaSettings(Map<String, String> values) { this.values = values; }

    static MediaSettings parse(String text) {
        Map<String, String> values = new HashMap<>();
        if (text == null) return new MediaSettings(values);
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            String line = text.substring(start, end).trim();
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1).trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                int equals = line.indexOf('=');
                if (equals > 0)
                    values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
            if (end >= text.length()) break;
            start = end + 1;
        }
        return new MediaSettings(values);
    }

    static MediaSettings read(Context context) {
        try {
            return parse(new String(Files.readAllBytes(
                    new File(context.getFilesDir(), "dashboard.txt").toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return parse("");
        }
    }

    String get(String key) { return values.getOrDefault(key, ""); }

    /** Any configured source keeps the band on screen; a blank file keeps Home exactly as today. */
    boolean anySource() {
        return !get("seerr_url").isEmpty() || !get("m3u_url").isEmpty() || !get("game_day_pkg").isEmpty();
    }

    int refreshMinutes() {
        try {
            int parsed = Integer.parseInt(get("refresh_minutes").trim());
            return Math.max(1, parsed);
        } catch (NumberFormatException e) {
            return DEFAULT_REFRESH_MINUTES;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.MediaSettingsTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
cd /c/Users/rahul/Projects/cleanyourtv
git add launcher/app/src/main/java/com/gathin/tvhome/MediaSettings.java launcher/app/src/test/java/com/gathin/tvhome/MediaSettingsTest.java
git commit -m "feat(launcher): media dashboard settings reader"
```

---

### Task 2: MediaSnapshot and MediaCard — the immutable render model

**Files:**
- Create: `launcher/app/src/main/java/com/gathin/tvhome/MediaSnapshot.java`
- Test: `launcher/app/src/test/java/com/gathin/tvhome/MediaSnapshotTest.java`

**Interfaces:**
- Produces: `final class MediaCard` with fields `String id, title, line` (all non-null), `Kind kind`, `State state`, `long whenMs`, `int accent`, `String tmdbId` ("" when none), `String streamUrl` ("" when none); enums `MediaCard.Kind { MOVIE, SERIES, SPORTS, LIVE }` and `MediaCard.State { AVAILABLE, REQUESTED, APPROVED, UPCOMING, LIVE, FINAL, UNAVAILABLE }`; constructor `MediaCard(String id, Kind kind, String title, String line, State state, long whenMs, int accent, String tmdbId, String streamUrl)`.
- Produces: `final class MediaSnapshot` with `List<MediaCard> movies, series, sports, live`, `Map<String, SourceState> sources` (keys `"seerr"`, `"playlist"`, `"sports"`); `enum Freshness { FRESH, STALE, UNAVAILABLE }`; `final class SourceState { Freshness freshness; long updatedMs; }`; statics `MediaSnapshot empty()`, `boolean anyContent()`, `static List<MediaCard> dropExpired(List<MediaCard> cards, long nowMs)`.

- [ ] **Step 1: Write the failing test**

```java
package com.gathin.tvhome;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaSnapshotTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 60L * 60 * 1000;

    private static MediaCard movie(String id, String title, long when) {
        return new MediaCard(id, MediaCard.Kind.MOVIE, title, "", MediaCard.State.UPCOMING,
                when, 0, "550", "");
    }

    @Test public void emptySnapshotHasNoContent() {
        MediaSnapshot snapshot = MediaSnapshot.empty();
        assertTrue(snapshot.movies.isEmpty() && snapshot.series.isEmpty());
        assertTrue(snapshot.sports.isEmpty() && snapshot.live.isEmpty());
        assertFalse(snapshot.anyContent());
    }

    @Test public void dropExpiredRemovesPastAnchoredCardsButKeepsAvailableOnes() {
        MediaCard past = movie("past", "Old Release", NOW - 2 * HOUR);
        MediaCard future = movie("future", "New Release", NOW + 2 * HOUR);
        MediaCard undated = new MediaCard("undated", MediaCard.Kind.MOVIE, "Requested",
                "", MediaCard.State.REQUESTED, 0, 0, "551", "");
        List<MediaCard> kept = MediaSnapshot.dropExpired(List.of(past, future, undated), NOW + HOUR);
        assertEquals(List.of("future", "undated"), kept.stream().map(c -> c.id).toList());
    }

    @Test public void liveAndFinalSportsCardsSurviveExpiry() {
        MediaCard live = new MediaCard("live", MediaCard.Kind.SPORTS, "AFL", "",
                MediaCard.State.LIVE, NOW - HOUR, 0, "", "");
        MediaCard done = new MediaCard("done", MediaCard.Kind.SPORTS, "NRL", "",
                MediaCard.State.FINAL, NOW - 3 * HOUR, 0, "", "");
        assertEquals(2, MediaSnapshot.dropExpired(List.of(live, done), NOW).size());
    }

    @Test public void anyContentLooksAtAllFourCategories() {
        MediaSnapshot snapshot = new MediaSnapshot(List.of(), List.of(), List.of(),
                List.of(new MediaCard("ch", MediaCard.Kind.LIVE, "Channel", "News",
                        MediaCard.State.AVAILABLE, 0, 0, "", "http://nas/ch.m3u8")),
                java.util.Map.of("playlist",
                        new MediaSnapshot.SourceState(MediaSnapshot.Freshness.FRESH, NOW)));
        assertTrue(snapshot.anyContent());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.MediaSnapshotTest"`
Expected: compile error, `MediaSnapshot` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** One complete dashboard state, applied to the view atomically; the view never fetches. */
final class MediaSnapshot {
    enum Freshness { FRESH, STALE, UNAVAILABLE }

    static final class SourceState {
        final Freshness freshness;
        final long updatedMs;
        SourceState(Freshness freshness, long updatedMs) { this.freshness = freshness; this.updatedMs = updatedMs; }
    }

    final List<MediaCard> movies;
    final List<MediaCard> series;
    final List<MediaCard> sports;
    final List<MediaCard> live;
    final Map<String, SourceState> sources;

    MediaSnapshot(List<MediaCard> movies, List<MediaCard> series, List<MediaCard> sports,
                  List<MediaCard> live, Map<String, SourceState> sources) {
        this.movies = Collections.unmodifiableList(new ArrayList<>(movies));
        this.series = Collections.unmodifiableList(new ArrayList<>(series));
        this.sports = Collections.unmodifiableList(new ArrayList<>(sports));
        this.live = Collections.unmodifiableList(new ArrayList<>(live));
        this.sources = Collections.unmodifiableMap(new java.util.HashMap<>(sources));
    }

    static MediaSnapshot empty() {
        return new MediaSnapshot(List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    boolean anyContent() {
        return !movies.isEmpty() || !series.isEmpty() || !sports.isEmpty() || !live.isEmpty();
    }

    /**
     * Deterministic expiry: an anchored card (whenMs > 0) is dropped once its moment passed by more
     * than an hour, except sports cards whose own lifecycle states (LIVE/FINAL) keep them truthful.
     */
    static List<MediaCard> dropExpired(List<MediaCard> cards, long nowMs) {
        List<MediaCard> kept = new ArrayList<>();
        for (MediaCard card : cards) {
            if (card.kind == MediaCard.Kind.SPORTS) { kept.add(card); continue; }
            if (card.whenMs > 0 && card.whenMs + 60L * 60 * 1000 < nowMs) continue;
            kept.add(card);
        }
        return kept;
    }
}
```

And in a separate file `launcher/app/src/main/java/com/gathin/tvhome/MediaCard.java`:

```java
package com.gathin.tvhome;

/** One card in the band. Title is always present; every other text field may be "". */
final class MediaCard {
    enum Kind { MOVIE, SERIES, SPORTS, LIVE }
    enum State { AVAILABLE, REQUESTED, APPROVED, UPCOMING, LIVE, FINAL, UNAVAILABLE }

    /** Stable identity for in-place view updates; sources derive it deterministically. */
    final String id;
    final Kind kind;
    final String title;
    /** Supporting line: status, channel group, or the turn's word ("Puck drop"). */
    final String line;
    final State state;
    /** Anchor time (release, kickoff); 0 when the card has none. */
    final long whenMs;
    /** ARGB accent: the Game Day bar colour, or 0. */
    final int accent;
    /** Seerr media id for Jellyfin resolution; "" when none. */
    final String tmdbId;
    /** Stream URL for LIVE channels; "" when none. */
    final String streamUrl;

    MediaCard(String id, Kind kind, String title, String line, State state,
              long whenMs, int accent, String tmdbId, String streamUrl) {
        this.id = id;
        this.kind = kind;
        this.title = title;
        this.line = line == null ? "" : line;
        this.state = state;
        this.whenMs = whenMs;
        this.accent = accent;
        this.tmdbId = tmdbId == null ? "" : tmdbId;
        this.streamUrl = streamUrl == null ? "" : streamUrl;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.MediaSnapshotTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add launcher/app/src/main/java/com/gathin/tvhome/MediaSnapshot.java launcher/app/src/main/java/com/gathin/tvhome/MediaCard.java launcher/app/src/test/java/com/gathin/tvhome/MediaSnapshotTest.java
git commit -m "feat(launcher): media snapshot model with deterministic expiry"
```

---

### Task 3: PlaylistSource — bounded m3u parsing without regex

**Files:**
- Create: `launcher/app/src/main/java/com/gathin/tvhome/PlaylistSource.java`
- Test: `launcher/app/src/test/java/com/gathin/tvhome/PlaylistSourceTest.java`

**Interfaces:**
- Produces: `static List<MediaCard> parse(String text, int cap)` — cards have `Kind.LIVE`, `State.AVAILABLE`, `id = streamUrl`, `title` = the EXTINF name (bounded, 64 chars), `line` = `group-title` (bounded, 32 chars), `streamUrl` = the URL line (bounded, 1024 chars), `accent = 0`, `whenMs = 0`.

- [ ] **Step 1: Write the failing test**

```java
package com.gathin.tvhome;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaylistSourceTest {
    private static final String PLAYLIST = "#EXTM3U\n"
            + "#EXTINF:-1 tvg-logo=\"https://cdn.example/a.png\" group-title=\"Sports\",AFL 24/7 | HD\n"
            + "https://nas.example/live/afl.m3u8\n"
            + "#EXTINF:-1,ABC News\n"
            + "http://nas.example/live/abc.ts\n"
            + "#EXTINF:-1 group-title=\"News\",\n"
            + "http://nas.example/live/nameless.ts\n"
            + "#EXTINF:-1 tvg-logo=\"https://cdn.example/z.png\" group-title=\"Kids\",Cartoon\n"
            + "# comment line, no URL follows this tag\n"
            + "https://nas.example/live/cartoon.m3u8\n"
            + "http://orphan-url-without-extinf.ts\n"
            + "#EXTINF:-1,Rogue\n";

    @Test public void parsesNameGroupAndUrlPairs() {
        List<MediaCard> cards = PlaylistSource.parse(PLAYLIST, 64);
        assertEquals(4, cards.size());
        assertEquals("AFL 24/7 | HD", cards.get(0).title);
        assertEquals("Sports", cards.get(0).line);
        assertEquals("https://nas.example/live/afl.m3u8", cards.get(0).streamUrl);
        assertEquals("https://nas.example/live/afl.m3u8", cards.get(0).id);
        assertEquals(MediaCard.Kind.LIVE, cards.get(0).kind);
        assertEquals(MediaCard.State.AVAILABLE, cards.get(0).state);
    }

    @Test public void namelessChannelFallsBackToBoundedUrlName() {
        List<MediaCard> cards = PlaylistSource.parse(PLAYLIST, 64);
        assertEquals("nameless.ts", cards.get(2).title);
    }

    @Test public void orphanUrlsAndTrailingTagsAreDropped() {
        List<MediaCard> cards = PlaylistSource.parse(PLAYLIST, 64);
        assertTrue(cards.stream().noneMatch(c -> c.streamUrl.contains("orphan")));
        assertTrue(cards.stream().noneMatch(c -> c.title.equals("Rogue")));
    }

    @Test public void capsListAtLimit() {
        StringBuilder many = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 300; i++)
            many.append("#EXTINF:-1,Ch ").append(i).append("\nhttp://nas.example/").append(i).append(".ts\n");
        List<MediaCard> cards = PlaylistSource.parse(many.toString(), 64);
        assertEquals(64, cards.size());
        assertEquals("Ch 63", cards.get(63).title);
    }

    @Test public void boundsNameLengthAndRejectsOversizedLines() {
        String longName = "#EXTINF:-1," + "X".repeat(200) + "\nhttp://nas.example/ok.ts\n"
                + "#EXTINF:-1,Fine\nhttp://nas.example/".repeat(1) + "y".repeat(3000) + "\n";
        List<MediaCard> cards = PlaylistSource.parse(longName, 64);
        assertEquals(1, cards.size());
        assertEquals(64, cards.get(0).title.length());
        assertEquals('…', cards.get(0).title.charAt(63));
    }

    @Test public void nullAndGarbageYieldEmpty() {
        assertTrue(PlaylistSource.parse(null, 64).isEmpty());
        assertTrue(PlaylistSource.parse("random text\nno scheme here\n", 64).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.PlaylistSourceTest"`
Expected: compile error, `PlaylistSource` does not exist.

- [ ] **Step 3: Write minimal implementation**

Plain character loops only — no `String.split`, no regex (`Cards.java`'s ICU rule). One pass over the text holding "the pending EXTINF" then consuming the next non-comment line as its URL:

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.PlaylistSourceTest"`
Expected: PASS (6 tests). If the comment-between-EXTINF-and-URL case fails, re-read the loop: a `#`-line must not clear `pendingName` (the Cartoon channel test covers it).

- [ ] **Step 5: Commit**

```bash
git add launcher/app/src/main/java/com/gathin/tvhome/PlaylistSource.java launcher/app/src/test/java/com/gathin/tvhome/PlaylistSourceTest.java
git commit -m "feat(launcher): bounded m3u playlist parser"
```

---

### Task 4: SeerrSource — requests and upcoming releases

**Files:**
- Modify: `launcher/app/build.gradle` (add test-only org.json)
- Create: `launcher/app/src/main/java/com/gathin/tvhome/SeerrSource.java`
- Test: `launcher/app/src/test/java/com/gathin/tvhome/SeerrSourceTest.java`

**Interfaces:**
- Produces: `static Map<String,String> headers(String apiKey)` (`X-Api-Key` header, matching the News add-on), `static String requestsUrl(String base)` → `base + "/api/v1/request"`, `static String upcomingUrl(String base, MediaCard.Kind kind)` → `/api/v1/discover/movies/upcoming` or `/api/v1/discover/tv/upcoming`, `static List<MediaCard> parseRequests(byte[] body)` (splits by `media.mediaType`), `static List<MediaCard> parseUpcoming(byte[] body, MediaCard.Kind kind)`. Request statuses: `1→REQUESTED, 2→APPROVED, 3→AVAILABLE, anything else→UNAVAILABLE`. `id = "tmdb:" + tmdbId`, `whenMs` parsed from `releaseDate`/`release_date` `yyyy-MM-dd` as UTC epoch, `0` on absence.

- [ ] **Step 1: Add the test dependency**

In `launcher/app/build.gradle` `dependencies { }` (currently only `testImplementation 'junit:junit:4.13.2'` at line 47), add the same version the News add-on uses (`addons/news/app/build.gradle:34`):

```groovy
    testImplementation 'org.json:json:20231013'
```

- [ ] **Step 2: Write the failing test**

```java
package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SeerrSourceTest {
    private static byte[] json(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static final String REQUESTS = "["
            + "{\"id\":1,\"status\":2,\"media\":{\"mediaType\":\"movie\",\"tmdbId\":550,\"title\":\"Fight Club\"}},"
            + "{\"id\":2,\"status\":3,\"media\":{\"mediaType\":\"tv\",\"tmdbId\":1399,\"title\":\"Game of Thrones\"}},"
            + "{\"id\":3,\"status\":1,\"media\":{\"mediaType\":\"movie\",\"tmdbId\":680,\"title\":\"Pulp Fiction\"}},"
            + "{\"id\":4,\"status\":9,\"media\":{\"mediaType\":\"tv\",\"tmdbId\":0}}"
            + "]";

    @Test public void splitsRequestsIntoMoviesAndSeriesWithStates() {
        List<MediaCard> cards = SeerrSource.parseRequests(json(REQUESTS));
        assertEquals(2, cards.stream().filter(c -> c.kind == MediaCard.Kind.MOVIE).count());
        assertEquals(2, cards.stream().filter(c -> c.kind == MediaCard.Kind.SERIES).count());
        MediaCard fightClub = cards.get(0);
        assertEquals("Fight Club", fightClub.title);
        assertEquals(MediaCard.State.APPROVED, fightClub.state);
        assertEquals("tmdb:550", fightClub.id);
        assertEquals("550", fightClub.tmdbId);
        assertEquals(MediaCard.State.AVAILABLE, cards.get(1).state);
        assertEquals(MediaCard.State.REQUESTED, cards.get(2).state);
    }

    @Test public void defensiveAgainstMissingTitleAndTmdb() {
        List<MediaCard> cards = SeerrSource.parseRequests(json(REQUESTS));
        MediaCard odd = cards.get(3);
        assertEquals(MediaCard.State.UNAVAILABLE, odd.state);
        assertTrue(odd.title.startsWith("TMDB "));
    }

    @Test public void garbageBodyYieldsEmpty() {
        assertTrue(SeerrSource.parseRequests(json("not json")).isEmpty());
        assertTrue(SeerrSource.parseRequests(null).isEmpty());
        assertTrue(SeerrSource.parseUpcoming(json("{\"results\":[]}"), MediaCard.Kind.MOVIE).isEmpty());
    }

    @Test public void upcomingParsesTitleReleaseDateAndKind() {
        String body = "{\"results\":["
                + "{\"title\":\"Upcoming Film\",\"release_date\":\"2027-01-15\"},"
                + "{\"name\":\"Upcoming Show\",\"first_air_date\":\"2027-02-01\",\"tmdbId\":123}]"
                + "}";
        List<MediaCard> movies = SeerrSource.parseUpcoming(json(body), MediaCard.Kind.MOVIE);
        List<MediaCard> series = SeerrSource.parseUpcoming(json(body), MediaCard.Kind.SERIES);
        assertEquals("Upcoming Film", movies.get(0).title);
        assertEquals(MediaCard.State.UPCOMING, movies.get(0).state);
        assertTrue(movies.get(0).whenMs > 0);
        assertEquals("Upcoming Show", series.get(1).title);
        assertEquals(179_000_000_000L < series.get(1).whenMs, true);
    }

    @Test public void urlsAndHeadersMatchNewsAddonContract() {
        assertEquals("https://seerr.example/api/v1/request", SeerrSource.requestsUrl("https://seerr.example"));
        assertEquals("https://seerr.example/api/v1/discover/movies/upcoming",
                SeerrSource.upcomingUrl("https://seerr.example", MediaCard.Kind.MOVIE));
        assertEquals("https://seerr.example/api/v1/discover/tv/upcoming",
                SeerrSource.upcomingUrl("https://seerr.example", MediaCard.Kind.SERIES));
        assertEquals("secret", SeerrSource.headers("secret").get("X-Api-Key"));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.SeerrSourceTest"`
Expected: compile error, `SeerrSource` does not exist.

- [ ] **Step 4: Write minimal implementation**

```java
package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Seerr requests and upcoming releases. Defensive parsing only: a bad entry is skipped, never fatal. */
final class SeerrSource {
    private static final int MAX_TITLE = 96;

    static Map<String, String> headers(String apiKey) { return Map.of("X-Api-Key", apiKey); }

    static String requestsUrl(String base) { return base + "/api/v1/request"; }

    static String upcomingUrl(String base, MediaCard.Kind kind) {
        return base + (kind == MediaCard.Kind.SERIES
                ? "/api/v1/discover/tv/upcoming" : "/api/v1/discover/movies/upcoming");
    }

    static List<MediaCard> parseRequests(byte[] body) {
        List<MediaCard> cards = new ArrayList<>();
        JSONArray entries = array(body);
        if (entries == null) return cards;
        for (int i = 0; i < entries.length() && cards.size() < 64; i++) {
            JSONObject entry = entries.optJSONObject(i);
            JSONObject media = entry == null ? null : entry.optJSONObject("media");
            if (media == null) continue;
            String tmdb = String.valueOf(media.optLong("tmdbId", 0));
            String title = bound(media.optString("title", ""));
            if (title.isEmpty()) title = "TMDB " + tmdb;
            MediaCard.Kind kind = "tv".equals(media.optString("mediaType"))
                    ? MediaCard.Kind.SERIES : MediaCard.Kind.MOVIE;
            cards.add(new MediaCard("tmdb:" + tmdb, kind, title, "", state(entry.optInt("status", -1)),
                    0, 0, tmdb, ""));
        }
        return cards;
    }

    static List<MediaCard> parseUpcoming(byte[] body, MediaCard.Kind kind) {
        List<MediaCard> cards = new ArrayList<>();
        JSONObject page = object(body);
        JSONArray entries = page == null ? null : page.optJSONArray("results");
        if (entries == null) return cards;
        for (int i = 0; i < entries.length() && cards.size() < 32; i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String tmdb = String.valueOf(entry.optLong("id", 0));
            String title = bound(entry.optString(kind == MediaCard.Kind.SERIES ? "name" : "title", ""));
            if (title.isEmpty()) continue;
            long when = epochDay(entry.optString(kind == MediaCard.Kind.SERIES
                    ? "first_air_date" : "release_date", ""));
            cards.add(new MediaCard("tmdb:" + tmdb, kind, title, "", MediaCard.State.UPCOMING,
                    when, 0, tmdb, ""));
        }
        return cards;
    }

    private static MediaCard.State state(int code) {
        switch (code) {
            case 1: return MediaCard.State.REQUESTED;
            case 2: return MediaCard.State.APPROVED;
            case 3: return MediaCard.State.AVAILABLE;
            default: return MediaCard.State.UNAVAILABLE;
        }
    }

    /** "2027-01-15" to epoch ms (UTC); 0 when the text is not a plain yyyy-MM-dd date. */
    static long epochDay(String text) {
        if (text == null || text.length() != 10 || text.charAt(4) != '-' || text.charAt(7) != '-') return 0;
        try {
            return java.time.LocalDate.of(Integer.parseInt(text.substring(0, 4)),
                    Integer.parseInt(text.substring(5, 7)), Integer.parseInt(text.substring(8, 10)))
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return 0;
        }
    }

    private static JSONArray array(byte[] body) {
        try {
            return new JSONArray(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject object(byte[] body) {
        try {
            return new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static String bound(String value) {
        return value.length() <= MAX_TITLE ? value : value.substring(0, MAX_TITLE - 1) + "…";
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.SeerrSourceTest"`
Expected: PASS (5 tests)

- [ ] **Step 6: Commit**

```bash
git add launcher/app/build.gradle launcher/app/src/main/java/com/gathin/tvhome/SeerrSource.java launcher/app/src/test/java/com/gathin/tvhome/SeerrSourceTest.java
git commit -m "feat(launcher): seerr request and upcoming parsers"
```

---

### Task 5: SportsSource — Game Day turns as fixture cards

**Files:**
- Create: `launcher/app/src/main/java/com/gathin/tvhome/SportsSource.java`
- Test: `launcher/app/src/test/java/com/gathin/tvhome/SportsSourceTest.java`

**Interfaces:**
- Consumes: `GameDay.Turn` (`launcher/app/src/main/java/com/gathin/tvhome/GameDay.java:158` — fields `String topLine, String word; long startMs, endMs; int colour; boolean f1`), same package, direct access.
- Produces: `static List<MediaCard> cards(List<GameDay.Turn> turns, long nowMs)` and `static MediaCard.State phase(GameDay.Turn turn, long nowMs)`. Cards: `id = topLine + "|" + startMs`, `Kind.SPORTS`, `title = topLine`, `line = word`, `whenMs = startMs`, `accent = colour`. Phase: `now < startMs → UPCOMING`, `startMs <= now <= endMs → LIVE`, `now > endMs → FINAL`.

- [ ] **Step 1: Write the failing test**

```java
package com.gathin.tvhome;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SportsSourceTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 60L * 60 * 1000;

    private static GameDay.Turn turn(String top, long start, long end, int colour) {
        return new GameDay.Turn(top, "Puck drop", start, end, colour);
    }

    @Test public void mapsPhaseByTimeWindow() {
        assertEquals(MediaCard.State.UPCOMING, SportsSource.phase(turn("AFL", NOW + HOUR, NOW + 3 * HOUR, 0xFF00FF00), NOW));
        assertEquals(MediaCard.State.LIVE, SportsSource.phase(turn("AFL", NOW - HOUR, NOW + HOUR, 0xFF00FF00), NOW));
        assertEquals(MediaCard.State.FINAL, SportsSource.phase(turn("AFL", NOW - 3 * HOUR, NOW - HOUR, 0xFF00FF00), NOW));
    }

    @Test public void cardsCarryIdentityTitleWordAndColour() {
        List<MediaCard> cards = SportsSource.cards(
                List.of(turn("Richmond vs Carlton", NOW + HOUR, NOW + 3 * HOUR, 0xFFEEDDCC)), NOW);
        MediaCard card = cards.get(0);
        assertEquals("Richmond vs Carlton|" + (NOW + HOUR), card.id);
        assertEquals(MediaCard.Kind.SPORTS, card.kind);
        assertEquals("Richmond vs Carlton", card.title);
        assertEquals("Puck drop", card.line);
        assertEquals(0xFFEEDDCC, card.accent);
        assertEquals(NOW + HOUR, card.whenMs);
        assertEquals(MediaCard.State.UPCOMING, card.state);
    }

    @Test public void sortsByStartAndCapsAtTwelve() {
        java.util.List<GameDay.Turn> turns = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) turns.add(turn("G" + i, NOW + (20 - i) * HOUR, NOW + (22 - i) * HOUR, 0));
        List<MediaCard> cards = SportsSource.cards(turns, NOW);
        assertEquals(12, cards.size());
        assertTrue(cards.get(0).whenMs <= cards.get(1).whenMs);
        assertEquals("G1", cards.get(0).title);
    }

    @Test public void emptyTurnsYieldEmptyCards() {
        assertTrue(SportsSource.cards(List.of(), NOW).isEmpty());
        assertTrue(SportsSource.cards(null, NOW).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.SportsSourceTest"`
Expected: compile error, `SportsSource` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Game Day turns as fixture cards. Reads the existing contract; adds no provider access of its own. */
final class SportsSource {
    private static final int MAX_FIXTURES = 12;

    static MediaCard.State phase(GameDay.Turn turn, long nowMs) {
        if (nowMs < turn.startMs) return MediaCard.State.UPCOMING;
        return nowMs <= turn.endMs ? MediaCard.State.LIVE : MediaCard.State.FINAL;
    }

    static List<MediaCard> cards(List<GameDay.Turn> turns, long nowMs) {
        List<MediaCard> cards = new ArrayList<>();
        if (turns == null) return cards;
        List<GameDay.Turn> sorted = new ArrayList<>(turns);
        sorted.sort(Comparator.comparingLong((GameDay.Turn t) -> t.startMs));
        for (GameDay.Turn turn : sorted) {
            if (cards.size() >= MAX_FIXTURES) break;
            cards.add(new MediaCard(turn.topLine + "|" + turn.startMs, MediaCard.Kind.SPORTS,
                    turn.topLine, turn.word, phase(turn, nowMs), turn.startMs, turn.colour, "", ""));
        }
        return cards;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.SportsSourceTest"`
Expected: PASS (4 tests). Note: `GameDay.Turn`'s constructor is package-private — the test lives in the same package, so this compiles without touching `GameDay.java`.

- [ ] **Step 5: Commit**

```bash
git add launcher/app/src/main/java/com/gathin/tvhome/SportsSource.java launcher/app/src/test/java/com/gathin/tvhome/SportsSourceTest.java
git commit -m "feat(launcher): game day turns as fixture cards"
```

---

### Task 6: MediaStore and MediaLoader — caching, reconciliation, scheduling

**Files:**
- Create: `launcher/app/src/main/java/com/gathin/tvhome/MediaStore.java` (port of `addons/news/app/src/main/java/com/gathin/news/Cache.java`)
- Create: `launcher/app/src/main/java/com/gathin/tvhome/MediaLoader.java`
- Create: `launcher/app/src/main/java/com/gathin/tvhome/Fetch.java` (port of News `Fetch.java`: HttpURLConnection GET → `byte[]`, connect/read timeout 10s, silent null on any failure, never logs URLs or keys)
- Test: `launcher/app/src/test/java/com/gathin/tvhome/MediaLoaderTest.java`

**Interfaces:**
- Consumes: `MediaSettings` (Task 1), `MediaSnapshot`/`MediaCard` (Task 2), `PlaylistSource.parse` (Task 3), `SeerrSource` (Task 4), `SportsSource.cards` (Task 5), `GameDay.create/start/stop/turns` (existing).
- Produces: `interface MediaLoader.Fetcher { byte[] get(String url, Map<String,String> headers); }`, `interface MediaLoader.Store { byte[] read(String key); void put(String key, byte[] value); long ageSeconds(String key); }`, `interface MediaLoader.Sports { List<GameDay.Turn> turns(); }`, `interface MediaLoader.Listener { void onSnapshot(MediaSnapshot snapshot); }`. Class `MediaLoader` with `MediaLoader(Fetcher fetcher, Store store, Sports sports, java.util.function.LongSupplier clock)`, `MediaSnapshot load(MediaSettings settings, MediaSnapshot previous)` (the testable core), `static MediaLoader create(Context ctx, MediaSettings settings, Handler main, Listener listener)` (wires `Fetch`, `MediaStore`, a `GameDay`-backed Sports on the existing provider key `game_day_pkg`, a single-thread executor, and a `Handler` refresh loop at `settings.refreshMinutes()`), `void start()`, `void stop()`, `void shutdown()`.

- [ ] **Step 1: Write the failing test**

```java
package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaLoaderTest {
    private static final long NOW = 1_800_000_000_000L;

    private final Map<String, byte[]> responses = new HashMap<>();
    private final Map<String, byte[]> stored = new HashMap<>();
    private final Map<String, Long> ages = new HashMap<>();
    private List<GameDay.Turn> turns = List.of();

    private MediaLoader loader() {
        return new MediaLoader((url, headers) -> responses.get(url), new MediaLoader.Store() {
            @Override public byte[] read(String key) { return stored.get(key); }
            @Override public void put(String key, byte[] value) { stored.put(key, value); ages.put(key, 0L); }
            @Override public long ageSeconds(String key) { return ages.getOrDefault(key, -1L); }
        }, () -> turns, () -> NOW);
    }

    private static MediaSettings settings() {
        return MediaSettings.parse(
                "seerr_url=https://seerr.example\nseerr_key=k\nm3u_url=http://nas/p.m3u\ngame_day_pkg=com.gathin.game");
    }

    private static byte[] json(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test public void healthySourcesProduceFreshSnapshotPerCategory() {
        responses.put("https://seerr.example/api/v1/request",
                json("[{\"id\":1,\"status\":3,\"media\":{\"mediaType\":\"movie\",\"tmdbId\":550,\"title\":\"Fight Club\"}}]"));
        responses.put("https://seerr.example/api/v1/discover/movies/upcoming",
                json("{\"results\":[{\"title\":\"Next Film\",\"release_date\":\"2030-01-01\"}]}"));
        responses.put("https://seerr.example/api/v1/discover/tv/upcoming", json("{\"results\":[]}"));
        responses.put("http://nas/p.m3u", json("#EXTM3U\n#EXTINF:-1 group-title=\"News\",ABC\nhttp://nas/abc.ts\n"));
        turns = List.of(new GameDay.Turn("AFL", "Puck drop", NOW - 1000, NOW + 1000, 0xFF112233));

        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals(2, snapshot.movies.size()); // request + upcoming movie
        assertEquals("Fight Club", snapshot.movies.get(0).title);
        assertEquals(1, snapshot.live.size());
        assertEquals(1, snapshot.sports.size());
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("seerr").freshness);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("playlist").freshness);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("sports").freshness);
    }

    @Test public void failedSeerrFallsBackToCacheMarkedStaleAndOthersStayFresh() {
        responses.put("http://nas/p.m3u", json("#EXTM3U\n#EXTINF:-1,ABC\nhttp://nas/abc.ts\n"));
        stored.put("seerr-request", json(
                "[{\"id\":1,\"status\":3,\"media\":{\"mediaType\":\"movie\",\"tmdbId\":550,\"title\":\"Old\"}]"));
        ages.put("seerr-request", 900L);
        stored.put("seerr-upcoming-movie", json("{\"results\":[]}"));
        stored.put("seerr-upcoming-series", json("{\"results\":[]}"));
        ages.put("seerr-upcoming-movie", 900L);
        ages.put("seerr-upcoming-series", 900L);

        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals(MediaSnapshot.Freshness.STALE, snapshot.sources.get("seerr").freshness);
        assertEquals("Old", snapshot.movies.get(0).title);
        assertEquals(MediaSnapshot.Freshness.FRESH, snapshot.sources.get("playlist").freshness);
    }

    @Test public void unavailableSourcesAreMarkedNotBorrowed() {
        MediaSnapshot snapshot = loader().load(settings(), MediaSnapshot.empty());
        assertEquals(MediaSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("seerr").freshness);
        assertEquals(MediaSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("playlist").freshness);
        assertEquals(MediaSnapshot.Freshness.UNAVAILABLE, snapshot.sources.get("sports").freshness);
        assertFalse(snapshot.anyContent());
    }

    @Test public void unconfiguredSourcesAreAbsentFromStatuses() {
        MediaSnapshot snapshot = loader().load(MediaSettings.parse(""), MediaSnapshot.empty());
        assertTrue(snapshot.sources.isEmpty());
    }

    @Test public void previousSnapshotHeldOnTotalFailureThenPartialRefreshKeepsIt() {
        responses.put("http://nas/p.m3u", json("#EXTM3U\n#EXTINF:-1,ABC\nhttp://nas/abc.ts\n"));
        MediaLoader unit = loader();
        MediaSnapshot first = unit.load(settings(), MediaSnapshot.empty());
        responses.clear();
        MediaSnapshot degraded = unit.load(settings(), first);
        assertEquals(1, degraded.live.size()); // last-known retained from previous snapshot
        assertEquals(MediaSnapshot.Freshness.STALE, degraded.sources.get("playlist").freshness);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.MediaLoaderTest"`
Expected: compile error, `MediaLoader` does not exist.

- [ ] **Step 3: Write minimal implementation**

`MediaStore.java` — port News `Cache.java` (bounded file map under `filesDir/media-cache/`, key-sanitized filenames, `read/put/ageSeconds`):

```java
package com.gathin.tvhome;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small file cache for source bodies; newest entries win, bounded like the News add-on's cache. */
final class MediaStore implements MediaLoader.Store {
    private static final int MAX_ENTRIES = 16;
    private final File dir;

    MediaStore(Context context) {
        dir = new File(context.getFilesDir(), "media-cache");
    }

    @Override public byte[] read(String key) {
        try {
            File file = new File(dir, name(key));
            return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override public void put(String key, byte[] value) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File file = new File(dir, name(key));
            Files.write(file.toPath(), value);
            trim();
        } catch (Exception e) {
            // cache writes are best-effort
        }
    }

    @Override public long ageSeconds(String key) {
        File file = new File(dir, name(key));
        long modified = file.lastModified();
        return modified <= 0 ? -1 : (System.currentTimeMillis() - modified) / 1000;
    }

    private void trim() {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_ENTRIES) return;
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_ENTRIES; i++) files[i].delete();
    }

    /** safe filename from a cache key: hex of the key, so no path traversal and no odd characters */
    private static String name(String key) {
        return Integer.toHexString(key.hashCode()) + "-" + Long.toHexString(key.length());
    }
}
```

`MediaLoader.java`:

```java
package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

/**
 * Runs the three sources off the main thread and publishes one complete snapshot per pass.
 * Per-source freshness is independent: a failed source degrades alone (cached+stale or unavailable),
 * and the previous snapshot is retained on total failure. Same shape as GameDay/News lifecycle.
 */
final class MediaLoader {
    interface Fetcher { byte[] get(String url, Map<String, String> headers); }
    interface Store { byte[] read(String key); void put(String key, byte[] value); long ageSeconds(String key); }
    interface Sports { List<GameDay.Turn> turns(); }
    interface Listener { void onSnapshot(MediaSnapshot snapshot); }

    private static final long STALE_AFTER_SECONDS = 15 * 60;

    private final Fetcher fetcher;
    private final Store store;
    private final Sports sports;
    private final LongSupplier clock;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final long refreshMs;
    private MediaSnapshot last = MediaSnapshot.empty();
    private boolean running;

    MediaLoader(Fetcher fetcher, Store store, Sports sports, LongSupplier clock) {
        this(fetcher, store, sports, clock, 15L * 60 * 1000, snapshot -> { });
    }

    MediaLoader(Fetcher fetcher, Store store, Sports sports, LongSupplier clock,
                long refreshMs, Listener listener) {
        this.fetcher = fetcher;
        this.store = store;
        this.sports = sports;
        this.clock = clock;
        this.refreshMs = refreshMs;
        this.listener = listener;
    }

    static MediaLoader create(Context context, MediaSettings settings, Handler main, Listener listener) {
        GameDay provider = GameDay.create(context, settings.get("game_day_pkg"), main, () -> { });
        return new MediaLoader(new Fetch(), new MediaStore(context),
                provider == null ? () -> List.of() : provider::turns,
                System::currentTimeMillis, settings.refreshMinutes() * 60_000L, listener);
    }

    /** Home is on screen: load now and keep refreshing; nothing runs while stopped. */
    void start() {
        if (running) return;
        running = true;
        refresh();
    }

    void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
    }

    void shutdown() {
        stop();
        io.shutdown();
    }

    private void refresh() {
        if (!running) return;
        try {
            io.execute(() -> {
                MediaSnapshot snapshot = load(last);
                main.post(() -> {
                    if (running) {
                        last = snapshot;
                        listener.onSnapshot(snapshot);
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            // shutdown raced a refresh; nothing to do
        }
        main.postDelayed(this::refresh, refreshMs);
    }

    /** The reconciling core, testable with fakes; `previous` is the last complete snapshot. */
    MediaSnapshot load(MediaSnapshot previous) {
        long now = clock.getAsLong();
        Map<String, MediaSnapshot.SourceState> statuses = new HashMap<>();
        List<MediaCard> movies = new ArrayList<>();
        List<MediaCard> series = new ArrayList<>();
        List<MediaCard> live = new ArrayList<>();

        if (!seerrUrl.isEmpty()) loadSeerr(now, statuses, movies, series);
        if (!m3uUrl.isEmpty()) loadPlaylist(now, statuses, live);
        if (!gameDayPkg.isEmpty()) loadSports(now, statuses);

        movies = MediaSnapshot.dropExpired(movies, now);
        series = MediaSnapshot.dropExpired(series, now);
        return new MediaSnapshot(movies, series, sportsCards, live, statuses);
    }
    // NOTE: fields below are set from settings per load() call; see loadWith(settings, previous).

    // --- wiring detail used by load(): settings are passed in, held as locals, never fields ---

    private String seerrUrl = "", seerrKey = "", m3uUrl = "", gameDayPkg = "";
    private List<MediaCard> sportsCards = new ArrayList<>();

    MediaSnapshot load(MediaSettings settings, MediaSnapshot previous) {
        this.seerrUrl = settings.get("seerr_url");
        this.seerrKey = settings.get("seerr_key");
        this.m3lUrl(settings.get("m3u_url"));
        this.gameDayPkg = settings.get("game_day_pkg");
        return load(previous);
    }

    private void m3lUrl(String url) { this.m3uUrl = url; }

    private void loadSeerr(long now, Map<String, MediaSnapshot.SourceState> statuses,
                           List<MediaCard> movies, List<MediaCard> series) {
        Map<String, String> headers = SeerrSource.headers(seerrKey);
        boolean anyFresh = false;
        List<MediaCard> cachedCards = new ArrayList<>();
        for (String[] each : new String[][] {
                {"seerr-request", SeerrSource.requestsUrl(seerrUrl), "request"},
                {"seerr-upcoming-movie", SeerrSource.upcomingUrl(seerrUrl, MediaCard.Kind.MOVIE), "up-movie"},
                {"seerr-upcoming-series", SeerrSource.upcomingUrl(seerrUrl, MediaCard.Kind.SERIES), "up-series"}}) {
            byte[] body = fetcher.get(each[1], headers);
            if (body == null) {
                body = store.read(each[0]);
                if (body != null) cachedCards.addAll(cachedSeerr(each[0], body, movies, series));
                continue;
            }
            store.put(each[0], body);
            anyFresh = true;
            if ("request".equals(each[2])) cachedCards.clear(); // requests render newest when fresh
            addTo(seerrParse(each[0], body), movies, series);
        }
        if (!cachedCards.isEmpty()) addTo(cachedCards, movies, series);
        statuses.put("seerr", new MediaSnapshot.SourceState(anyFresh
                ? MediaSnapshot.Freshness.FRESH
                : (cachedCards.isEmpty() ? MediaSnapshot.Freshness.UNAVAILABLE : MediaSnapshot.Freshness.STALE),
                now));
    }

    private List<MediaCard> seerrParse(String key, byte[] body) {
        if ("seerr-request".equals(key)) return SeerrSource.parseRequests(body);
        return SeerrSource.parseUpcoming(body, key.endsWith("movie") ? MediaCard.Kind.MOVIE : MediaCard.Kind.SERIES);
    }

    private List<MediaCard> cachedSeerr(String key, byte[] body, List<MediaCard> movies, List<MediaCard> series) {
        return seerrParse(key, body); // parsing is idempotent; caller decides fresh vs cached
    }

    private void addTo(List<MediaCard> cards, List<MediaCard> movies, List<MediaCard> series) {
        for (MediaCard card : cards) (card.kind == MediaCard.Kind.SERIES ? series : movies).add(card);
    }

    private void loadPlaylist(long now, Map<String, MediaSnapshot.SourceState> statuses, List<MediaCard> live) {
        byte[] body = fetcher.get(m3uUrl, java.util.Map.of());
        if (body == null) {
            byte[] cached = store.read("playlist");
            if (cached != null) live.addAll(PlaylistSource.parse(
                    new String(cached, java.nio.charset.StandardCharsets.UTF_8), 64));
            statuses.put("playlist", new MediaSnapshot.SourceState(live.isEmpty()
                    ? MediaSnapshot.Freshness.UNAVAILABLE : MediaSnapshot.Freshness.STALE, now));
            return;
        }
        store.put("playlist", body);
        live.addAll(PlaylistSource.parse(new String(body, java.nio.charset.StandardCharsets.UTF_8), 64));
        statuses.put("playlist", new MediaSnapshot.SourceState(MediaSnapshot.Freshness.FRESH, now));
    }

    private void loadSports(long now, Map<String, MediaSnapshot.SourceState> statuses) {
        List<GameDay.Turn> turns = sports.turns();
        sportsCards = SportsSource.cards(turns, now);
        statuses.put("sports", new MediaSnapshot.SourceState(sportsCards.isEmpty()
                ? MediaSnapshot.Freshness.UNAVAILABLE : MediaSnapshot.Freshness.FRESH, now));
    }
}
```

Implementation note for the implementer: the skeleton above keeps `load(settings, previous)` as the tested entry point; tidy the private helpers so field-based plumbing (`seerrUrl`, `m3uUrl`, `sportsCards`) becomes locals threaded through parameters — behavior must keep every `MediaLoaderTest` case green, including "previous snapshot held on total failure", which needs `previous` merged: when all three sources return nothing and no cache exists, return `previous` with all statuses UNAVAILABLE rather than `empty()` when `previous.anyContent()` is true. `Fetch.java` is a direct port of `addons/news/app/src/main/java/com/gathin/news/Fetch.java` with the package line changed to `com.gathin.tvhome` and class renamed to `Fetch`, implementing `MediaLoader.Fetcher`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.MediaLoaderTest"`
Expected: PASS (5 tests). Iterate until all five hold, including the previous-snapshot retention case.

- [ ] **Step 5: Commit**

```bash
git add launcher/app/src/main/java/com/gathin/tvhome/MediaStore.java launcher/app/src/main/java/com/gathin/tvhome/MediaLoader.java launcher/app/src/main/java/com/gathin/tvhome/Fetch.java launcher/app/src/test/java/com/gathin/tvhome/MediaLoaderTest.java
git commit -m "feat(launcher): media loader with per-source freshness and cache fallback"
```

---

### Task 7: FilesProvider, setup scripts, example settings, docs

**Files:**
- Create: `launcher/app/src/main/java/com/gathin/tvhome/DashboardFilesProvider.java`
- Modify: `launcher/app/src/main/AndroidManifest.xml` (add provider, pattern of the existing `${applicationId}.theme` provider)
- Create: `launcher/setup.sh`, `launcher/setup.ps1` (ports of `addons/news/setup.sh` / `setup.ps1`)
- Create: `launcher/dashboard.example.txt`
- Modify: `.gitignore` (ignore `launcher/dashboard.txt`)
- Modify: `docs/launcher.md` (short "Media dashboard" section)

**Interfaces:**
- Produces: write-only content URI `content://com.gathin.tvhome.files/dashboard.txt` (follows `${applicationId}` if the per-TV build overrides it — construct the authority string the same way the existing theme provider does).

- [ ] **Step 1: Write the provider**

Copy `addons/news/app/src/main/java/com/gathin/news/FilesProvider.java` verbatim, change package to `com.gathin.tvhome`, rename class `DashboardFilesProvider`, and replace `"settings.txt"` with `"dashboard.txt"` (both in the guard and the file path).

- [ ] **Step 2: Register it in the manifest**

In `launcher/app/src/main/AndroidManifest.xml`, beside the existing provider (`android:authorities="${applicationId}.theme"`), add:

```xml
        <provider
            android:name=".DashboardFilesProvider"
            android:authorities="${applicationId}.files"
            android:exported="true"
            android:grantUriPermissions="false"
            android:writePermission=""
            tools:ignore="ExportedContentProvider" />
```

Match the existing theme provider's exported/permission attributes exactly — open the manifest and mirror them rather than trusting the snippet above; adb shell `content write` (shell uid) must reach it, as News does.

- [ ] **Step 3: Create the setup scripts and example file**

Port `addons/news/setup.sh` with these substitutions: `PKG=com.gathin.news` → `PKG=com.gathin.tvhome` (note: if the installed build uses a per-TV `applicationId` override, derive it from `launcher/local.properties`/gradle.properties the way `build-and-install.sh` does), `news-settings.txt` → `tv-dashboard.txt` tmp name, `settings.txt` → `dashboard.txt`, `content://$PKG.files/settings.txt` → `content://$PKG.files/dashboard.txt`. Keep the `tools/venv` + `b64push.py` + `tools/tv.py` machinery identical. Port `setup.ps1` with the same substitutions. Create `launcher/dashboard.example.txt`:

```
# Media dashboard sources. Copy to dashboard.txt, fill in, push with setup.sh (TV_HOST=...).
# dashboard.txt is gitignored; never commit it.
seerr_url=
seerr_key=
jellyfin_url=
jellyfin_key=
jellyfin_package=
player_package=
m3u_url=
# game_day_pkg is the same add-on package the race panel uses (config.xml game_day)
game_day_pkg=
# minutes between refreshes while Home is visible (default 15, minimum 1)
refresh_minutes=
```

- [ ] **Step 4: Guard and document**

Add `launcher/dashboard.txt` to `.gitignore`. Append to `docs/launcher.md` a "Media dashboard" section modeled on the News add-on's README table: each key, what it feeds, the offline default, and the setup.sh push flow.

- [ ] **Step 5: Verify build**

Run: `cd launcher && ./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL, no new warnings about the provider.

- [ ] **Step 6: Commit**

```bash
git add launcher/app/src/main/java/com/gathin/tvhome/DashboardFilesProvider.java launcher/app/src/main/AndroidManifest.xml launcher/setup.sh launcher/setup.ps1 launcher/dashboard.example.txt .gitignore docs/launcher.md
git commit -m "feat(launcher): dashboard settings push channel and docs"
```

---

### Task 8: MediaBand — the view layer

**Files:**
- Create: `launcher/app/src/main/res/layout/media_band.xml`
- Create: `launcher/app/src/main/res/layout/media_card.xml`
- Modify: `launcher/app/src/main/res/values/strings.xml` (tab labels)
- Create: `launcher/app/src/main/java/com/gathin/tvhome/MediaBand.java`

**Interfaces:**
- Consumes: `MediaSnapshot`, `MediaCard` (Task 2).
- Produces: `final class MediaBand extends LinearLayout` with `MediaBand(Context, AttributeSet)`, `void apply(MediaSnapshot snapshot)`, `void setHost(Host host)`, `interface Host { void openCard(MediaCard card); }`. Shows/hides itself (`VISIBLE`/`GONE`) based on `snapshot.anyContent()`; never fetches; updates card views in place keyed by `MediaCard.id`.

- [ ] **Step 1: Write the layouts and strings**

`media_band.xml` — a vertical LinearLayout: a horizontal rail of four `TextView` tabs (`@+id/tabMovies`, `tabSeries`, `tabSports`, `tabLive`) styled like `raceKicker` (12sp, `@string/font_medium`, `letterSpacing 0.22`, `textAllCaps`, `#B3FFFFFF` selected / `#66FFFFFF` unselected, `focusable true`, 12dp vertical padding, 24dp horizontal padding per tab), then a `HorizontalScrollView` (`@+id/cardScroll`, `scrollbars none`) wrapping a horizontal `LinearLayout` (`@+id/cardRow`). `media_card.xml` — a focusable vertical LinearLayout card (260×150dp, 12dp margin, background `@drawable/input_row_bg` like `item_card_row.xml`): `@+id/cardTitle` (20sp, `@string/font_light`, `#FFFFFFFF`, singleLine+end ellipsize), `@+id/cardLine` (12sp, `@string/font_body`, `#99FFFFFF`, singleLine), `@+id/cardState` (11sp, `@string/font_medium`, `letterSpacing 0.08`, state color `#99FFFFFF`; LIVE state `#FF6FCF3F`), `@+id/cardAccent` (a 3dp×40dp View whose background is a plain color drawable set in code from `MediaCard.accent`, `GONE` when accent == 0). Strings in `values/strings.xml`: `media_tab_movies` "Movies", `media_tab_series` "Series", `media_tab_sports` "Sports", `media_tab_live` "Live".

- [ ] **Step 2: Write MediaBand**

Plain views, launcher conventions:

```java
package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The dashboard band: a category rail and one row of cards, applied from snapshots. No I/O. */
public final class MediaBand extends LinearLayout {
    public interface Host { void openCard(MediaCard card); }

    private static final MediaCard.Kind[] ORDER =
            {MediaCard.Kind.MOVIE, MediaCard.Kind.SERIES, MediaCard.Kind.SPORTS, MediaCard.Kind.LIVE};

    private Host host;
    private int selected; // index into ORDER
    private final Map<String, View> cardViews = new HashMap<>();
    private MediaSnapshot snapshot = MediaSnapshot.empty();

    public MediaBand(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setVisibility(GONE);
    }

    public void setHost(Host host) { this.host = host; }

    void apply(MediaSnapshot next) {
        snapshot = next;
        setVisibility(next.anyContent() ? VISIBLE : GONE);
        if (!next.anyContent()) { cardViews.clear(); return; }
        selected = Math.min(selected, ORDER.length - 1);
        bindRail();
        bindRow();
    }

    private List<MediaCard> cards() {
        switch (ORDER[selected]) {
            case MOVIE: return snapshot.movies;
            case SERIES: return snapshot.series;
            case SPORTS: return snapshot.sports;
            default: return snapshot.live;
        }
    }

    private void bindRail() {
        int[] tabIds = {R.id.tabMovies, R.id.tabSeries, R.id.tabSports, R.id.tabLive};
        for (int i = 0; i < tabIds.length; i++) {
            TextView tab = findViewById(tabIds[i]);
            tab.setSelected(i == selected);
            tab.setOnClickListener(v -> { selected = i; bindRail(); bindRow(); });
        }
    }

    private void bindRow() {
        LinearLayout row = findViewById(R.id.cardRow);
        List<MediaCard> cards = cards();
        List<String> live = new ArrayList<>();
        Map<String, View> stale = new HashMap<>(cardViews);
        for (MediaCard card : cards) {
            live.add(card.id);
            View view = cardViews.get(card.id);
            if (view == null) {
                view = LayoutInflater.from(getContext()).inflate(R.layout.media_card, row, false);
                view.setOnClickListener(v -> { if (host != null) host.openCard(card); });
                row.addView(view);
                cardViews.put(card.id, view);
            }
            stale.remove(card.id);
            bindCard(view, card);
        }
        for (Map.Entry<String, View> gone : stale.entrySet()) {
            row.removeView(gone.getValue());
            cardViews.remove(gone.getKey());
        }
    }

    private void bindCard(View view, MediaCard card) {
        ((TextView) view.findViewById(R.id.cardTitle)).setText(card.title);
        ((TextView) view.findViewById(R.id.cardLine)).setText(card.line);
        TextView state = view.findViewById(R.id.cardState);
        state.setText(stateText(card));
        state.setTextColor(card.state == MediaCard.State.LIVE ? 0xFF6FCF3F : 0xFF99FFFFFF);
        View accent = view.findViewById(R.id.cardAccent);
        accent.setVisibility(card.accent == 0 ? GONE : VISIBLE);
        if (card.accent != 0) accent.setBackgroundColor(card.accent);
    }

    static String stateText(MediaCard card) {
        switch (card.state) {
            case AVAILABLE: return "Available";
            case REQUESTED: return "Requested";
            case APPROVED: return "Approved";
            case UPCOMING: return "Upcoming";
            case LIVE: return "Live";
            case FINAL: return "Final";
            default: return "";
        }
    }
}
```

No unit tests for the view (Android framework); `stateText` is exercised implicitly on device. The launcher has no Robolectric and none is added.

- [ ] **Step 3: Verify it compiles into the app**

Run: `cd launcher && ./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add launcher/app/src/main/res/layout/media_band.xml launcher/app/src/main/res/layout/media_card.xml launcher/app/src/main/res/values/strings.xml launcher/app/src/main/java/com/gathin/tvhome/MediaBand.java
git commit -m "feat(launcher): media band view with category rail"
```

---

### Task 9: Jellyfin resolution and launch intents

**Files:**
- Create: `launcher/app/src/main/java/com/gathin/tvhome/JellyfinResolve.java`
- Create: `launcher/app/src/main/java/com/gathin/tvhome/MediaLaunch.java`
- Test: `launcher/app/src/test/java/com/gathin/tvhome/JellyfinResolveTest.java`

**Interfaces:**
- Consumes: `MediaLoader.Fetcher` (Task 6), `MediaSettings` keys `jellyfin_url`, `jellyfin_key`, `jellyfin_package`, `player_package`.
- Produces: `static String itemId(MediaLoader.Fetcher fetcher, String base, String apiKey, String tmdbId)` → item id or `null`; URL `{base}/Items?Recursive=true&AnyProviderIdEquals=Tmdb.{tmdbId}&Limit=1&api_key={key}`; parses `{"Items":[{"Id":"..."}]}`. `MediaLaunch.openItem(Context ctx, String jellyfinPackage, String itemId)`: `ACTION_VIEW` on `Uri.parse("jellyfin://item/" + itemId)` with `setPackage(jellyfinPackage)` when non-empty, falling back to the package's launch intent when the deep link resolves to no activity. `MediaLaunch.playStream(Context ctx, String playerPackage, String url)`: `ACTION_VIEW` on the stream URL, `setPackage` when configured.

- [ ] **Step 1: Write the failing test**

```java
package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class JellyfinResolveTest {
    private final Map<String, byte[]> responses = new HashMap<>();
    private final MediaLoader.Fetcher fetcher = (url, headers) -> responses.get(url);

    @Test public void buildsQueryUrlWithProviderId() {
        JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550");
        assertTrue(responses.containsKey(
                "https://jf.example/Items?Recursive=true&AnyProviderIdEquals=Tmdb.550&Limit=1&api_key=k"));
    }

    @Test public void returnsFirstItemId() {
        responses.put("https://jf.example/Items?Recursive=true&AnyProviderIdEquals=Tmdb.550&Limit=1&api_key=k",
                "{\"Items\":[{\"Id\":\"8f12\"}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals("8f12", JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550"));
    }

    @Test public void nullOnEmptyItemsMissingBodyOrGarbage() {
        assertNull(JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550"));
        responses.put("https://jf.example/Items?Recursive=true&AnyProviderIdEquals=Tmdb.550&Limit=1&api_key=k",
                "{\"Items\":[]}".getBytes(StandardCharsets.UTF_8));
        assertNull(JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550"));
        responses.put("https://jf.example/Items?Recursive=true&AnyProviderIdEquals=Tmdb.550&Limit=1&api_key=k",
                "]]garbage".getBytes(StandardCharsets.UTF_8));
        assertNull(JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.JellyfinResolveTest"`
Expected: compile error.

- [ ] **Step 3: Implement**

`JellyfinResolve.java` (org.json, defensive, no logging of keys):

```java
package com.gathin.tvhome;

import org.json.JSONObject;
import java.util.Map;

/** Resolves a TMDB id to a Jellyfin item id; null when the item is absent or the server unreachable. */
final class JellyfinResolve {
    private JellyfinResolve() { }

    static String itemId(MediaLoader.Fetcher fetcher, String base, String apiKey, String tmdbId) {
        if (base.isEmpty() || apiKey.isEmpty() || tmdbId == null || tmdbId.isEmpty()) return null;
        String url = base + "/Items?Recursive=true&AnyProviderIdEquals=Tmdb." + tmdbId
                + "&Limit=1&api_key=" + apiKey;
        byte[] body = fetcher.get(url, Map.of());
        if (body == null) return null;
        try {
            JSONObject items = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                    .optJSONArray("Items");
            if (items == null || items.length() == 0) return null;
            String id = items.optJSONObject(0).optString("Id", "");
            return id.isEmpty() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }
}
```

`MediaLaunch.java`:

```java
package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;

/** Opens a card: Jellyfin item deep link (package launch fallback) or a stream in the player. */
final class MediaLaunch {
    private MediaLaunch() { }

    static void openItem(Context ctx, String jellyfinPackage, String itemId) {
        if (itemId == null) return;
        Intent deep = new Intent(Intent.ACTION_VIEW, Uri.parse("jellyfin://item/" + itemId));
        deep.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!jellyfinPackage.isEmpty()) deep.setPackage(jellyfinPackage);
        if (ctx.getPackageManager().resolveActivity(deep, 0) != null) {
            ctx.startActivity(deep);
            return;
        }
        Intent home = jellyfinPackage.isEmpty()
                ? null : ctx.getPackageManager().getLaunchIntentForPackage(jellyfinPackage);
        if (home != null) ctx.startActivity(home);
    }

    static void playStream(Context ctx, String playerPackage, String url) {
        Intent play = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!playerPackage.isEmpty()) play.setPackage(playerPackage);
        if (ctx.getPackageManager().resolveActivity(play, 0) != null) ctx.startActivity(play);
    }
}
```

The `jellyfin://item/{id}` scheme is the one hypothesis in this plan: Task 11 verifies it on the TV with the real Jellyfin app, and if the app does not register that URI scheme, `openItem` already degrades to launching the app — record the finding and adjust the URI construction only, keeping the resolve/fallback shape.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd launcher && ./gradlew test --tests "com.gathin.tvhome.JellyfinResolveTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add launcher/app/src/main/java/com/gathin/tvhome/JellyfinResolve.java launcher/app/src/main/java/com/gathin/tvhome/MediaLaunch.java launcher/app/src/test/java/com/gathin/tvhome/JellyfinResolveTest.java
git commit -m "feat(launcher): jellyfin item resolution and launch intents"
```

---

### Task 10: MainActivity wiring — lifecycle, layout, focus

**Files:**
- Modify: `launcher/app/src/main/res/layout/activity_main.xml` (insert the band above the grid wrapper)
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`

**Interfaces:**
- Consumes: everything above. `MainActivity` reference points (line numbers from current main): `gameDay = GameDay.create(...)` at ~540, `gameDay.start()` in `onResume` (~1058), `gameDay.stop()` in `onPause` (~1239), `gameDay.shutdown()` (~1294) — re-locate by searching `gameDay` before editing.

- [ ] **Step 1: Insert the band in the layout**

In `activity_main.xml`, inside the vertical LinearLayout that holds the hero block and the grid wrapper `FrameLayout` (the one whose comment reads "The wrapper clips at the grid's edge…"), add directly above that FrameLayout:

```xml
        <!-- Media dashboard (spec: docs/superpowers/specs/2026-11-01-media-dashboard-launcher-design.md).
             GONE when no sources are configured; the dock and rows are untouched either way. -->
        <com.gathin.tvhome.MediaBand
            android:id="@+id/mediaBand"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginStart="10dp"
            android:layout_marginEnd="10dp"
            android:layout_marginTop="10dp"
            android:visibility="gone" />
```

- [ ] **Step 2: Wire lifecycle in MainActivity**

Beside each `gameDay` reference:

- Fields near `private GameDay gameDay;`:
```java
    private MediaSettings mediaSettings;
    private MediaLoader mediaLoader;
```
- After `gameDay = GameDay.create(this, cfg.gameDay, handler, this::updatePanel);` (~540):
```java
        mediaSettings = MediaSettings.read(this);
        if (mediaSettings.anySource()) {
            mediaLoader = MediaLoader.create(this, mediaSettings, handler,
                    snapshot -> findViewById(R.id.mediaBand).apply(snapshot));
            ((MediaBand) findViewById(R.id.mediaBand)).setHost(this::openMediaCard);
        }
```
- In `onResume` beside `gameDay.start()` (~1058): `if (mediaLoader != null) mediaLoader.start();`
- In `onPause` beside `gameDay.stop()` (~1239): `if (mediaLoader != null) mediaLoader.stop();`
- At the `gameDay.shutdown()` site (~1294): `if (mediaLoader != null) mediaLoader.shutdown();`

Add the host method near other launch helpers (e.g. `LaunchActions`):

```java
    private void openMediaCard(MediaCard card) {
        if (card.kind == MediaCard.Kind.LIVE && !card.streamUrl.isEmpty()) {
            MediaLaunch.playStream(this, mediaSettings.get("player_package"), card.streamUrl);
            return;
        }
        if (!card.tmdbId.isEmpty() && mediaLoader != null) {
            mediaLoader.resolve(card.tmdbId, itemId ->
                    MediaLaunch.openItem(this, mediaSettings.get("jellyfin_package"), itemId));
            return;
        }
        if (!mediaSettings.get("jellyfin_package").isEmpty())
            MediaLaunch.openItem(this, mediaSettings.get("jellyfin_package"), null);
    }
```

`MediaLoader.resolve` (add in Task 6's class if not present):

```java
    /** Off-thread Jellyfin resolution; callback fires on the main thread, null when unresolvable. */
    void resolve(String tmdbId, java.util.function.Consumer<String> onResolved) {
        try {
            io.execute(() -> {
                String id = JellyfinResolve.itemId(fetcher, jellyUrl, jellyKey, tmdbId);
                main.post(() -> onResolved.accept(id));
            });
        } catch (RejectedExecutionException e) {
            main.post(() -> onResolved.accept(null));
        }
    }
```

with `jellyUrl`/`jellyKey` fields set in `create(...)` from the settings (add them to the constructor chain the same way `refreshMs` is). Note `openItem(ctx, pkg, null)` becomes "just launch the app" — add that early-return at the top of `openItem`: `if (itemId == null) { launch package only }`.

- [ ] **Step 3: Verify the full build and suite**

Run: `cd launcher && ./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL, all tests green (existing + new).

- [ ] **Step 4: Commit**

```bash
git add launcher/app/src/main/res/layout/activity_main.xml launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java launcher/app/src/main/java/com/gathin/tvhome/MediaLoader.java
git commit -m "feat(launcher): wire media band into home lifecycle"
```

---

### Task 11: On-device verification pass

**Files:**
- No code. Device steps; fixes flow back into the owning task's files.

**Interfaces:**
- Consumes: installed launcher on the TCL (adb via `tools/tv.py`, `TV_HOST=192.168.0.153`), a real `dashboard.txt` (the user fills it and runs setup themselves so keys never pass through this session).

- [ ] **Step 1: Build and install**

Run: `cd launcher && ./gradlew assembleRelease`, then install with the repo's install path (`build-and-install.sh` or `tools/` + `TV_HOST=192.168.0.153`), matching how the News add-on install works.

- [ ] **Step 2: User pushes real settings**

Ask the user to run `TV_HOST=192.168.0.153 bash launcher/setup.sh` from their own terminal with their real `launcher/dashboard.txt` (Seerr/Jellyfin keys stay private to them).

- [ ] **Step 3: On-device checklist (adb screenshots via tools/tv.py, `dumpsys window` for focus)**

- Band absent before settings are pushed; Home identical to pre-change.
- After push + Home restart: rail shows Movies / Series / Sports / Live; each tab renders its cards; empty sources show quiet reserved space, not borrowed content.
- Seerr card OK: Jellyfin app opens the item (verify `jellyfin://item/` scheme; if it falls back to app-launch, record it and fix the URI per Task 9's note).
- Live card OK: stream opens in the configured player.
- Sports tab shows upcoming/live/final states with accent bars; race panel still rotates as before.
- Kill Wi-Fi, resume Home: cards stay with cached/stale age labels; no spinner, no crash.
- Focus: up from the dock reaches the rail; left/right changes category; OK activates; dashboard geometry never shifts on refresh.

- [ ] **Step 4: Fix loop**

Any failure: reproduce, fix in the owning task's files, re-run `./gradlew test assembleRelease`, reinstall, re-check that item. Commit fixes as `fix(launcher): …`.

---

## Self-review notes

- Spec coverage: four categories (Tasks 3–5, 8), snapshot freshness (Task 6), Jellyfin resolution (Tasks 9–10), offline default (Tasks 1, 7, 10), settings/secrets (Tasks 1, 7), verification incl. on-device (Tasks 11; unit tests per task). Posters deferred — recorded as an accepted deviation in Global Constraints.
- Type consistency: `MediaCard(id, kind, title, line, state, whenMs, accent, tmdbId, streamUrl)` used identically in Tasks 2–10; `MediaSnapshot.SourceState(Freshness, long)` and keys `"seerr"/"playlist"/"sports"` consistent; `MediaLoader` interfaces match fake usage in Tasks 6 and 9.
- Known soft spots flagged for implementers, not placeholders: Task 6's skeleton needs the field-to-locals tidy and the previous-snapshot retention rule made explicit; Task 7's manifest snippet must be reconciled with the existing provider's attributes; Task 9's `jellyfin://` URI is verified on-device in Task 11 with a defined fallback.

# News Screensaver Add-on Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Daydream screensaver add-on (`addons/news`) that replaces Aerial Views on the TV: when idle, it rotates slides of upcoming releases (Jellyseerr), world/AU headlines (RSS), and Arsenal / Real Madrid team news (RSS).

**Architecture:** One DreamService renders a fullscreen slide view and rotates it on a timer; a loader thread fetches RSS feeds (XmlPullParser) and Jellyseerr discover-upcoming JSON, merges them into an ordered slide list, cached to disk (Cache copy from `addons/newin`) so the dream works offline. Settings arrive via the same WRITE_SECURE_SETTINGS-protected FilesProvider pattern as New In. No launcher tile, no dock card, no Jellyfin API calls.

**Tech Stack:** Plain Android (no AndroidX needed — DreamService + framework views), Java 17, Gradle (copy `addons/newin/app/build.gradle` minus the AndroidX deps), JUnit4 JVM tests for the feed parser and slide merge.

**Spec:** `docs/superpowers/specs/2026-11-01-news-screensaver.md` (same folder as this plan; read both).

## Global Constraints

- Repo rules: read `docs/workflows/addon.md` and `AGENTS.md` first. The launcher (`launcher/`) never changes and must not mention add-ons.
- Package `com.gathin.news`; label `News`. It is NOT a DOCK_CARD add-on — no `com.gathin.tvhome.category.DOCK_CARD` intent-filter, no module card XML.
- No secrets in committed files. `settings.txt` is gitignored; `settings.example.txt` ships placeholders only.
- Only the orchestrator commits. Do not commit, do not touch the TV.
- Verified data sources (do not re-probe, they answered 200 today):
  - Seerr: `GET {jellyseerr_url}/api/v1/discover/movies/upcoming` and `/api/v1/discover/tv/upcoming` with header `X-Api-Key: {jellyseerr_key}` — no query params (`?take=` returns 400). Results array; movie title field `title`, series `name`; both have `posterPath`, `releaseDate`/`firstAirDate`.
  - RSS (all 200, UTF-8, standard `<item><title><pubDate><link>`):
    - `https://www.abc.net.au/news/feed/51120/rss.xml` — ABC News AU
    - `https://feeds.bbci.co.uk/news/rss.xml` — BBC News
    - `https://feeds.bbci.co.uk/sport/football/teams/arsenal/rss.xml` — Arsenal
    - `https://feeds.bbci.co.uk/sport/football/teams/real-madrid/rss.xml` — Real Madrid
- Visual: background `#070A14`, accent `#4FC3F7` (the TV's aurora theme colors). No gradients, no photos as chrome. Calm: slides change with a 600 ms alpha crossfade, nothing bounces.
- Files to copy from `addons/newin/app/src/main/java/com/gathin/newin/` (adjust package and keep behavior): `Fetch.java`, `Cache.java`, `FilesProvider.java` (authority becomes `com.gathin.news.files`), `Settings.java` (extend: add a `List<String> keysWithPrefix(String)` method so feed keys can be enumerated).

## Settings file format (`settings.txt`, pushed to the provider)

```
jellyseerr_url=http://192.168.0.158:5055
jellyseerr_key=YOUR_SEERR_API_KEY
slide_seconds=18
feed1=https://www.abc.net.au/news/feed/51120/rss.xml|ABC News
feed2=https://feeds.bbci.co.uk/news/rss.xml|BBC News
feed3=https://feeds.bbci.co.uk/sport/football/teams/arsenal/rss.xml|Arsenal
feed4=https://feeds.bbci.co.uk/sport/football/teams/real-madrid/rss.xml|Real Madrid
```

- `feed*` keys: `url|display name`. Numbered keys are enumerated via `keysWithPrefix("feed")`, sorted numerically.
- Defaults when `settings.txt` is absent: the four feeds above with those names, `slide_seconds=18`, and empty Seerr values (the Upcoming section hides — the dream still shows news).

---

### Task 1: Skeleton + manifest + settings plumbing

**Files:**
- Create: `addons/news/.gitignore` (copy newin's), `addons/news/README.md`, `addons/news/settings.example.txt`, `addons/news/app/build.gradle` (newin's, AndroidX lines removed), `addons/news/app/src/main/AndroidManifest.xml`, `addons/news/app/src/main/res/values/strings.xml` (`app_name` = `News`), `addons/news/app/src/main/res/values/styles.xml`, `addons/news/app/src/main/res/drawable/icon.xml`
- Create: `addons/news/app/src/main/java/com/gathin/news/` — copied+repackaged `Fetch.java`, `Cache.java`, `FilesProvider.java`, `Settings.java` (with `keysWithPrefix`)

**Manifest** (whole file):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="android.software.leanback" android:required="false" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.READ_TV_LISTINGS" />
    <application android:allowBackup="false" android:usesCleartextTraffic="true"
        android:icon="@drawable/icon" android:label="@string/app_name"
        android:supportsRtl="false" android:theme="@style/Screen">
        <service android:name=".NewsDream" android:exported="true">
            <intent-filter>
                <action android:name="android.service.dreams.DreamService" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </service>
        <provider android:name=".FilesProvider" android:authorities="com.gathin.news.files"
            android:exported="true" android:permission="android.permission.WRITE_SECURE_SETTINGS" />
    </application>
</manifest>
```

README.md: follow `addons/newin/README.md` structure — what it shows, how to install (gradle + setup.ps1/sh), settings keys table, a "Screensaver" section explaining Settings → Device Preferences → Screen saver → News. Fill "Tested on" with `TCL 65C6K, Android TV 14` (verification happens at bring-up; the orchestrator amends if it fails). Screenshot row not needed — README references `screenshot.jpg` only in text.

- [ ] Copy + repackage the five Java files; add `keysWithPrefix` to `Settings.java` (return keys starting with the prefix, natural-number order when the remainder parses as an integer, lexicographic otherwise).
- [ ] Write manifest, strings, styles (fullscreen dark theme, no action bar — copy newin's `styles.xml`), icon (reuse newin's `icon.xml` shape but recolor: same paths, swap the accent `#FF4FC3F7` for `#FFFFC857` so the two tiles are distinguishable at a glance).
- [ ] `./gradlew assembleRelease` passes (env: `JAVA_HOME=/c/Users/rahul/scoop/apps/temurin17-jdk/current`, `ANDROID_HOME=/c/Users/rahul/Android/Sdk`, run from `addons/news`).

### Task 2: RSS feed parser (TDD)

**Files:**
- Create: `app/src/main/java/com/gathin/news/Feeds.java`
- Create: `app/src/test/java/com/gathin/news/FeedsTest.java`
- Test fixture: `app/src/test/resources/bbc-arsenal.xml` — paste the first 6 `<item>`s of a real fetch of `https://feeds.bbci.co.uk/sport/football/teams/arsenal/rss.xml` (title, guid, link, description, pubDate), with the channel header.

**Interfaces:**
- Produces: `static List<Feeds.Item> parse(InputStream in) throws IOException` where `Item` has `String title; String link; long pubDateMs;` (`pubDateMs` = `Date` parsed via `SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)`, 0 when missing/unparseable). Uses `XmlPullParser` with `xml.parser` safe settings; `<item>` scope only; ignores images/HTML in description.

- [ ] Write `FeedsTest` first: parse the fixture; assert count, first title matches the fixture, first pubDateMs > 0, link starts `https://www.bbc.com/sport`; also a malformed-XML case (truncated after `<channel>`) asserting empty list, not a crash.
- [ ] Run `./gradlew test --tests 'com.gathin.news.FeedsTest'` — FAIL (class missing).
- [ ] Implement `Feeds.java`; re-run — PASS.

### Task 3: Slide model + loader + merge (TDD)

**Files:**
- Create: `app/src/main/java/com/gathin/news/Slides.java`
- Create: `app/src/test/java/com/gathin/news/SlidesTest.java`

**Interfaces:**
- Consumes: `Feeds.Item`, Seerr JSON shapes below.
- Produces:
  - `enum Kind { HEADLINE, TEAM, UPCOMING_MOVIE, UPCOMING_SERIES }`
  - `static final class Slide { Kind kind; String title; String source; long dateMs; String posterUrl; String link; }`
  - `static List<Slide> merge(List<Feeds.Item> abc, List<Feeds.Item> bbc, Map<String, List<Feeds.Item>> teamFeeds, String upcomingJson)` — `upcomingJson` is the two Seerr responses already concatenated as `{"movies":[...],"tv":[...]}`; when null/empty/unparseable, the UPCOMING slides are simply absent (no exception).
  - Ordering rule (tested): interleave sections round-robin — abc, bbc, arsenal, real-madrid, upcoming-movie, upcoming-series, abc, bbc, … — each list trimmed to its 6 newest by `dateMs`/`pubDateMs` first.
  - UPCOMING slide mapping: movies `title`→title, `releaseDate` (ISO `yyyy-MM-dd`)→dateMs, poster URL = `{jellyseerr_url}/image?poster_path={posterPath}&api_key={jellyseerr_key}&width=400` (same pattern the New In add-on's Seerr row uses); series `name`→title, `firstAirDate`→dateMs. Source labels `Upcoming film` / `Upcoming series`.

- [ ] Write `SlidesTest` first: two tiny feed lists + a minimal upcoming JSON fixture string inline (2 movies, 1 series); assert interleave order, 6-item trim, absent-upcoming → no UPCOMING slides, poster URL format.
- [ ] Run — FAIL. Implement — PASS.

### Task 4: The DreamService

**Files:**
- Create: `app/src/main/java/com/gathin/news/NewsDream.java`
- Create: `app/src/main/res/drawable/screen.xml` (solid `#FF070A14`)

**Behavior:**
- `onAttachedToWindow()`: fullscreen `setContentView` built in code (FrameLayout on `screen.xml` background): a large `TextView` slide title (48 sp, `#FFF2F7FC`, max 3 lines, ellipsize end, gravity center-left, padding 120 px), a smaller source+age line below (28 sp, accent `#FF4FC3F7`), an `ImageView` top-right for UPCOMING posters (420 px wide, `centerCrop`, rounded via ClipToOutline with a 12 px OutlineProvider), and a clock `TextView` top-left (HH:mm, updates every minute). `setInteractive(false)`; keep screen dim slightly (`WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` NOT set — dreams may doze).
- Loader thread (plain `Thread`, as newin does): read `Settings`, fetch each feed (30 s connect/read timeout via newin's `Fetch`), fetch both Seerr endpoints, `Cache.put` each under its own key (`feed1`, `feed2`, …, `upcoming`), build `Slides.merge`, post to UI thread. On any failure, fall back to `Cache` entries (same keys) with their stored timestamp; when both fail, a single slide: title `News`, source `offline — no cached feeds`.
- Rotation: `Handler` on main looper every `slide_seconds` (min 8, max 120): crossfade — new content into a second identical view stack, animate alpha 0→1 over 600 ms, swap. Clock updates independently.
- `onDetachedFromWindow()`: stop threads/handlers (interrupt + `removeCallbacksAndMessages(null)`).
- The `Ago.java` relative-time helper from newin is copied and reused for the age line.

- [ ] Implement; `./gradlew assembleRelease` + full `./gradlew test` green.

### Task 5: Setup scripts + example settings

**Files:**
- Create: `addons/news/setup.ps1`, `addons/news/setup.sh` — copy newin's, change package (`com.gathin.news`), provider authority (`com.gathin.news.files`), and drop the `pm grant READ_TV_LISTINGS` and appops AUTO_START lines (a dream needs neither; keep the b64 settings push).

- [ ] Scripts reference the right package/authority; `assembleRelease` still green.

### Task 6: Repo test suite compliance

- [ ] Read `tests/test_addons.py`; run `python tests/test_addons.py` from the repo root. Fix anything it demands of `addons/news` EXCEPT `screenshot.jpg` (bring-up captures it; if the test requires it, leave that one failure — the orchestrator captures the screenshot before committing). Everything else must pass.

## Self-review checklist (run, fix inline, do not re-dispatch)

1. No `launcher/` file touched; no AndroidX in `addons/news/app/build.gradle`.
2. No secrets committed: `settings.example.txt` has placeholders; `.gitignore` covers `settings.txt`, `build/`, `.gradle/`, `local.properties`.
3. Types line up with the Interfaces blocks (slide `kind`, `title`, `source`, `dateMs`, `posterUrl`, `link`).
4. Offline path: unplug-the-NAS behavior is cached slides, never a blank or crashed dream.

## Bring-up deviations (2026-11-01, TCL 65C6K)

Each line: change — reason found on hardware.

- Manifest: the dream service declares `android.permission.BIND_DREAM_SERVICE` — without it DreamManagerService silently filters the dream and Backdrop runs instead.
- Task 5's "drop the appops AUTO_START line" was reverted (both setup scripts keep `appops set com.gathin.news AUTO_START allow`) — the TCL TclAppBoot gate rejects the system's dream bind with `OP_AUTO_START` denied until it is allowed.
- Review fixes: upcoming slides show `releases <date>` (Ago is for news only); poster loads run on one single-thread executor shut down in `onDetachedFromWindow`; `pubDate` accumulates text and parses at its closing tag.
- Open environment issue, not an app bug: the LAN Jellyseerr (192.168.0.158:5055) accepts but drops byte-identical valid requests from the TV's IP while serving them from other clients, so upcoming slides were not observable on the TV during bring-up; feeds, posters aside, need only the public internet.

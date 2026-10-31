# Implementation plan — `newin` add-on (New In: Jellyfin & Seerr + live-sport link)

Date: 2026-11-01. Source of truth: `docs/superpowers/specs/2026-10-31-newin-addon-design.md`.
Implementer: `developer` subagent. All paths relative to repo root `C:/Users/rahul/Projects/cleanyourtv`.

## Ground rules (from repo AGENTS.md / docs/workflows/addon.md)

- Nothing under `launcher/` changes, and `launcher/` never mentions `newin`.
- Never commit secrets: `settings.txt`, `calendars.txt`, API keys, or the local changes to
  `launcher/app/src/main/res/values/config.xml` and `custom/` (untracked, stay untracked).
- Own Gradle project + own `gradlew`/`gradlew.bat` wrappers copied from `addons/game-day`,
  plus game-day's `.gitignore` (keeps `local.properties` and build output out of git —
  `tests/test_addon_local_properties_ignored.py` checks this).
- Build env on this machine: `JAVA_HOME=/c/Users/rahul/scoop/apps/temurin17-jdk/current`,
  `ANDROID_HOME=/c/Users/rahul/Android/Sdk`.

## Facts already verified on the TV (do not re-derive)

- TV: TCL "Smart TV Pro" (65C6K), Android 14 / SDK 34, adb at `192.168.0.153:43641`.
- OwnTV package: `tv.own.owntv`. `owntv://play` and `owntv://open` resolve (VIEW/BROWSABLE,
  authority `play`/`open`; paths accepted). Watch Next `intent_uri`s are fired verbatim.
- Cricfy: `com.cricfytv.sports`, no useful VIEW filters → plain launch only.
- Jellyfin app `org.jellyfin.androidtv` (installed build) has **no data VIEW filter** → the
  spec's fallback is permanent: every Jellyfin item OK plain-launches `org.jellyfin.androidtv`.
- Game Day is installed (`io.github.justinnewman.gameday`) and exports
  `GamesProvider` at authority `io.github.justinnewman.gameday.games` (manifest), plus
  `FilesProvider` at `io.github.justinnewman.gameday.files`.
- NAS: Jellyfin `http://192.168.0.158:8096`, Seerr `http://192.168.0.158:5055`
  (`/api/v1/request` exists; auth via `X-Api-Key` header).

## Task 1 — Scaffold the project

Create `addons/newin/` mirroring `addons/game-day`'s layout:

- Copy verbatim from `addons/game-day`: `gradlew`, `gradlew.bat`, `gradle/` (wrapper jar+
  properties), `.gitignore`, `gradle.properties`, root `build.gradle`, `settings.gradle`
  (retarget `include ':app'` and root name).
- `app/build.gradle`: `com.android.application`; namespace + applicationId
  `com.gathin.newin`; minSdk 26, target/compileSdk 34; release buildType with
  `minifyEnabled true`, `shrinkResources true`, debug `signingConfig` (sideload pattern,
  same comment as game-day's); Java 17; UI deps `androidx.recyclerview:recyclerview:1.3.2`
  and `androidx.dynamicanimation:dynamicanimation:1.0.0` (launcher's own versions — the only
  allowed dependencies, see Task 5); test deps `junit:junit:4.13.2` +
  `org.json:json:20231013` (JVM tests, same as game-day).
- Package dir `app/src/main/java/com/gathin/newin/`.

Verify: `./gradlew tasks --console=plain` runs (with the env vars above).

## Task 2 — Manifest, card, art

`app/src/main/AndroidManifest.xml` modeled on game-day's:

- Permissions: `INTERNET`, `READ_TV_LISTINGS` (runtime, granted by setup script).
- `RowsActivity` (main): `exported="true"`, `screenOrientation="landscape"`,
  LEANBACK_LAUNCHER filter **and** the module-card filter
  `<category android:name="com.gathin.tvhome.category.DOCK_CARD" />`,
  meta-data `com.gathin.tvhome.card` → `@xml/module_card`.
- `app/src/main/res/xml/module_card.xml`:
  `<card spec="1" label="New In" caption="Jellyfin & Seerr" accent="#4FC3F7" />`.
- `FilesProvider` declared exactly like game-day's: authority `com.gathin.newin.files`,
  `exported="true"`, `android:permission="android.permission.WRITE_SECURE_SETTINGS"`.
- Own simple vector `banner.xml` (320x180 leanback banner) and `icon.xml`; theme copied
  from game-day's `res/values/styles.xml` ("Screen" style) so screens look native.

## Task 3 — Plumbing classes (plain Java, no external libs)

In `com.gathin.newin`:

- `Settings.java` — reads `settings.txt` from `getFilesDir()`; one `key=value` per line;
  `#` comments; keys: `jellyfin_url`, `jellyfin_key`, `jellyfin_user`, `jellyseerr_url`,
  `jellyseerr_key`, `live_sports_app`. Static `parse(String)` for tests.
- `Fetch.java` — `HttpURLConnection` + `org.json`; GET with optional headers; 5s connect
  and read timeouts; returns `byte[]`/`null`; never throws out (log only).
- `Cache.java` — `getFilesDir()/cache/`; stores last-good JSON per source key and poster
  files; `ageSeconds(key)` for the "updated Xh ago" line; trims to ~20 MB
  (oldest-last-used first); static-friendly core (`trim(List<File>, long)`) for tests.
- `Ago.java` — "updated 2h ago"-style formatting (adapt game-day's `Ago.java` wording).

## Task 4 — Rows (each independent; hidden when no data and no cache)

1. `LiveNowRow` — query `content://io.github.justinnewman.gameday.games` **after reading
   `addons/game-day/.../GamesProvider.java`** for the real URI shape and cursor columns
   (the spec sketch said `/turns`; the manifest authority is `.games` — trust the source).
   Keep only turns currently live (now between start and end). Provider missing
   (SecurityException/empty) → row hidden. OK → launch `live_sports_app` (default
   `com.cricfytv.sports`) via `package` intent, plain launch.
2. `OwnTvRow` — `TvContract.WatchNextPrograms` query filtered to
   `tv.own.owntv`; needs `READ_TV_LISTINGS`; when missing/empty → hidden. Poster from the
   row's poster art URI. OK → `startActivity(Intent.parseUri(intent_uri))` verbatim;
   any parse failure → plain-launch OwnTV.
3. `JellyfinLatestRow` — `GET {jellyfin_url}/Users/{jellyfin_user}/Items/Latest?Limit=20`
   with `Authorization: MediaBrowser Token="{jellyfin_key}"` (modern Jellyfin builds
   reject the legacy `X-Emby-Token` header); split Movies / Series into two labeled strips.
   Poster `{jellyfin_url}/Items/{id}/Images/Primary?maxWidth=400` (same Authorization header).
   OK → plain-launch `org.jellyfin.androidtv`.
4. `JellyfinResumeRow` — `GET .../Users/{jellyfin_user}/Items/Resume` same auth; progress
   bar from `UserData.PlayedPercentage`. OK → plain-launch Jellyfin.
5. `SeerrRow` — `GET {jellyseerr_url}/api/v1/request?take=20` with `X-Api-Key`;
   badge per `status`: 1 Pending, 2 Approved, 3 Processing, 4 Available (verify against
   Seerr's `/api/v1/request` JSON while implementing — statuses are ints in Overseerr's
   API; keep mapping in one static method for tests). Poster: TMDB URL from the item.
   OK → no-op in v1 (per spec).

Rows load on `RowsActivity` open; cached JSON renders instantly with the age line, network
refresh happens behind it. A source failing with cache → row + one-line error. No cache and
failing → hidden. Never blank, never crash.

## Task 5 — UI

- `RowsActivity` with `androidx.recyclerview:recyclerview:1.3.2` (+ `androidx.dynamicanimation:dynamicanimation:1.0.0` if used for focus zoom) — the same dependencies and versions the launcher itself uses for its rows (`launcher/app/build.gradle`); copy the launcher's packaging `excludes` for kotlin metadata into `app/build.gradle`. This is the one allowed dependency pair.
- Focus zoom on cards; posters 2:3, Live/Continue cards 16:9.
- A small header: "New In" + per-source "updated Xh ago" caption line.

## Task 6 — setup scripts

- `setup.ps1` / `setup.sh` adapted from game-day's: read `settings.txt` beside the script,
  check the app is installed, then through `tools/` venv + `tv.py`:
  `appops set com.gathin.newin AUTO_START allow` (TCL quirk, same as game-day),
  b64push → `content write --uri content://com.gathin.newin.files/settings.txt`,
  then `pm grant com.gathin.newin android.permission.READ_TV_LISTINGS`.
  Never print file contents. `settings.txt` is git-ignored (add to the copied `.gitignore`).

## Task 7 — Docs + repo integration

- `addons/newin/README.md`: what it shows, settings.txt keys (placeholders only),
  setup instructions, **Permissions section** listing `INTERNET` (fetch Jellyfin/Seerr/
  posters) and `READ_TV_LISTINGS` (read OwnTV's Watch Next row) in the words
  `addons/README.md`'s table already uses ("network access", "read TV listings").
- One row in `addons/README.md` table: `newin/`, tested on TCL 65C6K, permissions column,
  screenshot column `newin/screenshot.jpg`.
- `screenshot.jpg`: 1280x720 JPEG <300 KB of the rows on the TV — captured in Task 9, then
  committed (blur anything personal).

## Task 8 — JVM tests

`app/src/test/java/com/gathin/newin/` (JUnit4 + org.json test dep, launcher's
`launcher/app/src/test` style, no Android classes):

- `SettingsTest` — parse happy path, comments, missing keys.
- `RowsTest` (or per-row tests) — Jellyfin Latest JSON → Movies/Series split + poster URL;
  Resume JSON → progress percentage; Seerr request JSON → status badge mapping; Watch Next
  row build (plain object, not the contract class) → fire/no-fire decision.
- `CacheTest` — trim order and size cap; `Ago` formatting.

Verify: `./gradlew test assembleRelease --console=plain` green; APK at
`app/build/outputs/apk/release/app-release.apk`.

## Task 9 — On-TV bring-up (run by the orchestrator, not the developer subagent)

1. `adb -s 192.168.0.153:43641 install -r` the APK.
2. Write real `addons/newin/settings.txt` locally (keys from the user; Jellyfin API key +
   user id from the Jellyfin dashboard, Seerr key from its settings; never echoed).
3. Run `setup.ps1`; accept the module card on the TV ("Show card").
4. Open the tile: verify rows render, each OK lands in the right app (Cricfy live, OwnTV
   via its own link, Jellyfin plain launch), cached mode with NAS unreachable.
5. Capture `screenshot.jpg` from the TV screen.

## Task 10 — Repo checks + commit

- `python tests/test_addons.py` green.
- `git status` shows only intended files: `addons/newin/**` (minus ignored), the
  `addons/README.md` row, this plan. No launcher/, custom/, or secret files.
- Commit message in repo voice (see `git log` style): one commit, e.g.
  `addons/newin: New In card — Jellyfin & Seerr rows with live-sport link`.

## Out of scope (spec)

Live scores on Home, filing Seerr requests from the TV, launcher changes, France feed
(deferred by the user), Watch Next publishing (read-only).

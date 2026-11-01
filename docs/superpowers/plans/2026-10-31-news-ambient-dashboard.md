# News Ambient Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the News add-on’s rotating slide screensaver with a stable, glanceable 1080p dashboard containing configured news/media stories, a Game Day match strip, and a remote-revealed launcher row.

**Architecture:** Keep rendering and refresh lifecycle in `NewsDream`, but extract snapshot types, Game Day provider reading, and stable-slot reconciliation into focused plain-Java classes. Load RSS/Seerr and optional Game Day data off the UI thread, publish complete snapshots on the main thread, retain cached content with truthful per-source freshness, and keep fixed geometry and focus behavior through refreshes.

**Tech Stack:** Android DreamService, Java 17, Android framework views/content resolver, Gradle, JUnit 4.13.2, existing KXML parser; no AndroidX in News.

**Spec:** `docs/superpowers/specs/2026-10-31-news-ambient-dashboard-design.md`

## Global Constraints

- Android TV target: 1920×1080; minimum SDK 26; compile/target SDK remain 34.
- Plain Java Android framework views; add no AndroidX dependency to the News add-on.
- Dashboard remains a screensaver and refreshes only while `NewsDream` is attached.
- Keep existing RSS/Seerr configuration and Game Day provider contract; do not add sports API credentials.
- Match data must not be presented as live when cached or stale.
- Slots remain geometrically stable through refresh and failure; publish complete snapshots on the UI thread.
- Launcher row is hidden at rest and revealed by remote input without shifting dashboard regions.
- Do not edit unrelated pre-existing launcher config or custom theme changes.

---

## Code Map

- `addons/news/app/src/main/java/com/gathin/news/NewsDream.java` — current DreamService, view construction, loader lifecycle, clock, poster loading, and slide rotation; replace rotation with dashboard rendering and refresh lifecycle.
- `addons/news/app/src/main/java/com/gathin/news/Feeds.java` — RSS parser and item type; preserve parser behavior and add tests only if stable identity/freshness requires parsing changes.
- `addons/news/app/src/main/java/com/gathin/news/Slides.java` — existing RSS/Seerr selection and media models; reuse source behavior where possible, adapting selection output to dashboard slots.
- `addons/news/app/src/main/java/com/gathin/news/Cache.java` — existing bounded cache; retain the existing 20 MB limit and cached-response semantics.
- `addons/news/app/src/main/java/com/gathin/news/Settings.java` — configured feed and Seerr settings; extend only if the dashboard needs a configuration field not already represented.
- `addons/news/app/src/main/AndroidManifest.xml` — Internet permission and dream declaration; inspect package visibility/provider requirements before any manifest change.
- `addons/news/app/src/test/java/com/gathin/news/FeedsTest.java`, `SlidesTest.java` — existing JUnit tests and idiom; add pure-Java model/reconciliation/provider-row tests here or in focused sibling test classes.
- `docs/modules.md` — authoritative Game Day provider contract. Consumer reads `content://<authority>/turns`; rows include `top_line`, `word`, `start_ms`, `end_ms`, `colour`, `calendar`, and `turn_seconds`; provider ownership is validated by launcher contract.
- `launcher/app/src/main/java/com/gathin/tvhome/GameDay.java` — reference implementation of provider metadata resolution/query/lifecycle. Do not change provider contract as part of this work.
- `addons/news/app/build.gradle` — JUnit 4.13.2 and existing parser dependency; keep dependencies unchanged unless a specific required API is unavailable.
- `addons/news/README.md` — update user-facing description and configuration docs when new visible behavior is implemented.

## Task 1: Dashboard snapshot and stable slot reconciliation

**Files:**
- Create: `addons/news/app/src/main/java/com/gathin/news/DashboardSnapshot.java`
- Create: `addons/news/app/src/main/java/com/gathin/news/DashboardSlots.java`
- Create: `addons/news/app/src/test/java/com/gathin/news/DashboardSlotsTest.java`
- Modify only if necessary: `Feeds.java`, `Slides.java`

**Interfaces:**
- `DashboardSnapshot` contains immutable `List<StorySlot> stories`, `List<MatchSlot> matches`, and per-source freshness/status records. `StorySlot` includes stable slot ID, content identity, title, source, destination URL, timestamp, content kind, and cache/stale state. `MatchSlot` includes stable fixture key, top line, phase (`UPCOMING`, `LIVE`, `FINAL`, `UNAVAILABLE`), score/countdown text where available, start/end times, ARGB color, and freshness.
- `DashboardSlots.reconcile(previous, candidates, now, policy)` returns a new ordered list with fixed slot IDs, updating retained identities in place and filling vacated slots deterministically. It never shifts remaining slots left simply because one source item disappeared.
- Pure model classes must not depend on Android `View` classes, enabling local JUnit tests.

- [ ] **Step 1: Add failing tests for slot stability**
  - Test matching content IDs update in the same slot.
  - Test a removed/expired item leaves its slot available for deterministic replacement without shifting other retained IDs.
  - Test duplicate candidate IDs are de-duplicated and the configured slot count is respected.
  - Test cached/stale flags and source timestamps survive reconciliation when a failed source contributes last-known items.
- [ ] **Step 2: Run focused tests and confirm failure**
  - Run: `cd addons/news && ./gradlew testDebugUnitTest --tests 'com.gathin.news.DashboardSlotsTest'`
  - Expected: compilation/test failure because the model and reconciliation classes do not exist.
- [ ] **Step 3: Implement immutable snapshot and minimal reconciliation**
  - Use package-private nested types or separate focused types consistent with current package style.
  - Define finite slot count and deterministic input ordering; do not add generic plugin abstractions.
  - Use item link/URL as identity where available; otherwise use a normalized source + title + publication timestamp key.
- [ ] **Step 4: Run focused tests and existing unit tests**
  - Run: `cd addons/news && ./gradlew testDebugUnitTest`
  - Expected: all existing and new unit tests pass.

## Task 2: Game Day provider reader and match state mapping

**Files:**
- Create: `addons/news/app/src/main/java/com/gathin/news/GameDayReader.java`
- Create: `addons/news/app/src/test/java/com/gathin/news/GameDayReaderTest.java`
- Modify if required: `addons/news/app/src/main/AndroidManifest.xml`
- Reference only: `launcher/app/src/main/java/com/gathin/tvhome/GameDay.java`, `docs/modules.md`

**Interfaces:**
- `GameDayReader.read(Context, long nowMs)` returns a `GameDayResult` with explicit `AVAILABLE`, `EMPTY`, or `UNAVAILABLE` state and zero or more `DashboardSnapshot.MatchSlot` values.
- Reader resolves the named Game Day package/authority using the documented `com.gathin.tvhome.games` metadata convention, confirms provider ownership where platform APIs allow, and queries only `/turns`.
- It maps `start_ms`/`end_ms` to upcoming/live/final; ended rows may be retained briefly only if implementation can do so without falsely showing them live. Any cached result carries stale status and age.
- Missing package, metadata, provider, permission, malformed rows, or query exception yields an explicit unavailable result, not an exception escaping into the DreamService.

- [ ] **Step 1: Add tests for cursor-row mapping and provider failure outcomes**
  - Cover upcoming row, live interval, ended row, missing `top_line`, invalid `turn_seconds`, empty cursor, and query exception.
  - Keep row-mapping tests pure by separating cursor/provider lookup from row parsing if Android local unit test stubs prevent constructing a real `Cursor`.
- [ ] **Step 2: Run focused test and confirm expected failure**
  - Run: `cd addons/news && ./gradlew testDebugUnitTest --tests 'com.gathin.news.GameDayReaderTest'`
  - Expected: test fails until reader and mapping behavior exist.
- [ ] **Step 3: Implement minimal provider lookup/query with fail-closed outcomes**
  - Inspect `GameDay.java` at implementation time for exact metadata validation behavior and Android API compatibility at minSdk 26.
  - Do not modify launcher provider ownership rules or add online data fetching.
- [ ] **Step 4: Inspect merged manifest and build**
  - Run: `cd addons/news && ./gradlew processDebugMainManifest testDebugUnitTest`
  - Expected: manifest merges cleanly; provider access failures are represented as unavailable.

## Task 3: Compose complete dashboard snapshots from existing feeds and media

**Files:**
- Modify: `addons/news/app/src/main/java/com/gathin/news/NewsDream.java`
- Modify: `addons/news/app/src/main/java/com/gathin/news/Slides.java`
- Create if separation is needed to keep responsibilities clear: `addons/news/app/src/main/java/com/gathin/news/DashboardLoader.java`
- Create tests: `addons/news/app/src/test/java/com/gathin/news/DashboardLoaderTest.java`

**Interfaces:**
- `DashboardLoader.load(previousSnapshot, settings, nowMs)` runs off the UI thread and returns one complete `DashboardSnapshot` using existing RSS/Seerr/cache behavior plus `GameDayReader`.
- Failed feeds retain eligible previous/cache content with per-source stale metadata; successful sources update independently.
- The UI receives a completed snapshot only; no partially updated region state is published.

- [ ] **Step 1: Write loader selection/freshness tests**
  - Cover mixed RSS + Seerr candidates, source-specific fetch failure retaining only that source’s cached items, successful-source refresh, and Game Day unavailable state.
  - Inject fetch/cache/provider collaborators only where needed for deterministic tests; avoid a generalized dependency-injection framework.
- [ ] **Step 2: Run focused test to verify failure**
  - Run: `cd addons/news && ./gradlew testDebugUnitTest --tests 'com.gathin.news.DashboardLoaderTest'`
  - Expected: fails before loader behavior is implemented.
- [ ] **Step 3: Implement loader by reusing existing source parsing and bounds**
  - Preserve the existing source configuration defaults and Seerr auth behavior.
  - Keep network and provider work off the main thread; avoid changing the established cache size bound.
- [ ] **Step 4: Run all News unit tests**
  - Run: `cd addons/news && ./gradlew testDebugUnitTest`
  - Expected: all tests pass, including regression tests for current feed parsing and slide selection.

## Task 4: Fixed dashboard layout and truthful freshness rendering

**Files:**
- Create: `addons/news/app/src/main/java/com/gathin/news/DashboardView.java`
- Modify: `addons/news/app/src/main/java/com/gathin/news/NewsDream.java`
- Modify: `addons/news/app/src/main/res/values/styles.xml` and/or `res/drawable/screen.xml` only for colors/background required by the view.
- Add/update: `addons/news/screenshot.jpg` only after capturing the actual target-sized result, if the project contribution workflow requires an updated screenshot.

**Interfaces:**
- `DashboardView(Context)` owns the fixed view hierarchy and exposes `render(DashboardSnapshot)` plus `setLauncherVisible(boolean)`.
- Render regions: clock/source-status header, lead-story slot, fixed supporting-story/media slots, fixed match strip, bottom launcher row.
- `render` updates text/images/status within existing slot views; no rebuild/reorder of retained slots on every refresh.
- Show distinct current, cached/stale, empty, and unavailable presentation. Keep all copy short enough for TV-distance reading.

- [ ] **Step 1: Add a view-level rendering test where current Android test setup supports it**
  - If the project has no Robolectric/instrumentation harness, do not add a new dependency just for view tests. Verify model-to-view rendering through the app smoke test in Task 6 instead.
  - At minimum, test pure formatting helpers for age labels, fixture phase labels, and text truncation in a plain JUnit class.
- [ ] **Step 2: Implement static hierarchy and render behavior**
  - Use framework `FrameLayout`/`LinearLayout`/`TextView`/`ImageView`; no AndroidX widgets.
  - Set fixed layout weights/margins for 1920×1080 and avoid collapsing absent regions.
  - Preserve existing poster loading only for displayed media slots; cancel/discard late image results if the dream detached or slot identity changed.
- [ ] **Step 3: Check visual result at 1080p**
  - Run the app on the project’s Android TV emulator/device target or install via the existing project setup; capture a screenshot of fresh and degraded states.
  - Confirm the lead story is dominant, headlines are readable at distance, match states are distinguishable, and no slot moves between snapshots.

## Task 5: Remote-revealed launcher row and DreamService lifecycle

**Files:**
- Modify: `addons/news/app/src/main/java/com/gathin/news/NewsDream.java`
- Modify: `addons/news/app/src/main/java/com/gathin/news/DashboardView.java`
- Inspect before implementation: launcher/package/intent conventions and Android dream input behavior; do not invent destinations.
- Modify `AndroidManifest.xml` only if DreamService interaction/access requires a narrowly justified declaration.

**Interfaces:**
- `NewsDream` remains interactive only as required to accept remote navigation; remote input reveals launcher focus without altering dashboard layout geometry.
- Refresh interval comes from existing configuration if a suitable setting exists; otherwise use a conservative internal interval and document it. Clamp it to a safe lower bound to avoid repeated network work.
- Attach starts clock, initial refresh, periodic refresh, and needed input listeners. Detach cancels handler callbacks, stops/interrupts loader work, shuts down executors, clears image requests, and removes listeners.

- [ ] **Step 1: Add lifecycle behavior tests for refresh scheduling and cancellation if test seams permit**
  - Verify one refresh starts on attach, the next refresh is scheduled only while active, and detach prevents later UI publication.
  - Test no stale loader result can overwrite a newer snapshot or update a detached view.
- [ ] **Step 2: Implement input reveal/focus behavior**
  - At rest, launcher row is hidden and does not intercept ambient display.
  - First directional/center remote input reveals it and establishes a deterministic initial focus target.
  - Subsequent directional input traverses only valid launch targets; unsupported targets are omitted rather than launching guessed packages.
  - A bounded idle timeout hides the row and returns to ambient state without stopping periodic refresh.
- [ ] **Step 3: Implement visible-only periodic refresh and detach cleanup**
  - Prevent overlapping loads; publish snapshots only if the DreamService is still active and the result corresponds to the latest request.
  - Preserve existing handler/executor cleanup patterns and ensure all callbacks are removed on detach.
- [ ] **Step 4: Run unit tests and build**
  - Run: `cd addons/news && ./gradlew testDebugUnitTest assembleDebug`
  - Expected: tests pass and debug APK assembles without new AndroidX dependencies.

## Task 6: End-to-end TV verification and documentation

**Files:**
- Modify: `addons/news/README.md`
- Modify only if behavior warrants it: `addons/news/setup.sh`, `addons/news/setup.ps1`
- Build outputs are generated under `addons/news/app/build/`; do not commit generated artifacts.

- [ ] **Step 1: Verify build and existing test suite**
  - Run: `cd addons/news && ./gradlew clean testDebugUnitTest assembleDebug`
  - Expected: clean build and all unit tests pass.
- [ ] **Step 2: Exercise representative snapshots on the TV/emulator**
  - Fresh mixed RSS/Seerr snapshot.
  - One feed failed with cached headlines while another source refreshes.
  - Game Day upcoming, live, final, empty, provider missing, and provider exception cases.
  - Remote row hidden at rest; input reveals focus; movement does not shift dashboard regions; timeout hides it again.
  - Detach/reattach confirms no stale callbacks or overlapping loader threads.
- [ ] **Step 3: Capture and inspect the actual 1080p screen**
  - Confirm text size, contrast, clipping, poster scaling, match strip legibility, focus outline, and stability across refresh.
  - Record any device-specific limitation; do not claim visual verification based on compilation alone.
- [ ] **Step 4: Update the News README**
  - Document the persistent layout, configured content mix, refresh-only-while-visible behavior, match provider requirement and unavailable state, and remote-input launcher-row behavior.
  - Preserve existing install/setup commands unless the implementation actually changes them.
- [ ] **Step 5: Check working tree and generated files**
  - Run: `git status --short`
  - Remove only build/test outputs newly created by verification if untracked and unnecessary; leave pre-existing launcher/theme changes untouched.
  - Expected final diff is limited to News code/tests/docs and any explicitly necessary manifest/resource changes.

## Acceptance Checklist

- [ ] One persistent dashboard replaces one-slide-at-a-time rotation.
- [ ] Existing configurable RSS/Seerr content appears in fixed story slots and updates by stable identity.
- [ ] Match strip consumes Game Day `/turns`, distinguishes upcoming/live/final, and handles missing/empty/error states without fake scores.
- [ ] Per-source freshness and cached/offline states are truthful; stale match data is never presented as live.
- [ ] Refresh runs only while the DreamService is visible; detach cancels callbacks and stale publication.
- [ ] Launcher row is hidden at rest, revealed by remote input, focusable, and does not move dashboard geometry.
- [ ] News builds without AndroidX and without modifying the Game Day provider contract.
- [ ] Real 1080p visual and remote-control checks are recorded.

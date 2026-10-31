# Sports team feeds + "New In" media add-on — design

Date: 2026-10-31. Approved in chat (surface: new add-on app; content: latest additions,
continue watching, request status; sports: countdown panel via the existing Game Day add-on;
extra ask: link feeds into the apps actually watched on — Cricfy and OwnTV).

## Goals

1. Home shows each sports team's next fixture with a countdown: Arsenal (Premier League),
   Real Madrid (Champions League / La Liga), France Men's national team.
2. A new add-on shows Jellyfin latest additions, Jellyfin continue-watching, Jellyseerr
   request status, and OwnTV continue rows, each item opening the app it belongs to.
3. Live sport links into Cricfy.
4. The launcher core (`launcher/`) does not change. Everything is add-ons and configuration,
   per the repo's add-on rules (`docs/workflows/addon.md`).

## Part A — sports: configure the existing Game Day add-on

The launcher already has the `game_day` panel (the rotating strip under the clock, shared with
the F1 weekend; contract in `docs/modules.md`). The `addons/game-day` app
(`io.github.justinnewman.gameday`) feeds it from iCal calendar links. Work is setup, no code:

1. Build `addons/game-day`, install the APK on the TV.
2. `addons/game-day/calendars.txt` (git-ignored; links are personal-use subscriptions), one
   line per team: `Soccer | Arsenal | <link> | #EF0107`, `Soccer | Real Madrid | <link> | #FEBE10`,
   `Soccer | France | <link> | #0055A4`. Sources: the clubs' official "add fixtures to your
   calendar" pages, or a fixtures-subscription site for personal use — verify each link loads
   and contains 2026-27 fixtures before pushing.
3. `addons\game-day\setup.ps1 -TvHost 192.168.0.153` pushes the calendars (FilesProvider +
   `tools/tv.py` base64 push; links never printed).
4. Add `"game_day": "io.github.justinnewman.gameday"` to
   `custom/tcl-c6k-mine/theme-aurora-6/config.json` and re-push the theme.

Behaviour: panel rotates Arsenal → Real Madrid → France (+ F1 weekend when one is on);
"Kickoff in 2h 14m" → "Live now" during the game; no scores, by design.

## Part B — new add-on `newin` (the only new code)

A small standalone app at `addons/newin/`, package `com.gathin.newin` (own Gradle
project, wrappers copied from `addons/game-day`, following `docs/workflows/addon.md`).

### Module card

Label `New In`, caption `Jellyfin & Jellyseerr`, accent glow. Owner answers Show once; tile
sits ahead of the grid; OK opens the app.

### Rows (top to bottom)

1. **Live now** — queried from the Game Day add-on's own provider
   (`content://io.github.justinnewman.gameday/turns`), filtered to turns currently live.
   Shown only when a fixture is live. OK: plain-launch `com.cricfytv.sports`. Fallback if the
   provider is absent: row hidden.
2. **Continue on OwnTV** — `TvContract.WatchNextPrograms` filtered to the OwnTV package
   (package id confirmed on the TV; it is an IPTV player installed from
   github.com/ahXN00/OwnTV). Each row's `intent_uri` is OwnTV's own `owntv://play…` reopen
   link — fire it verbatim on OK. Posters from the row's poster URI where available.
   Requires `READ_TV_LISTINGS` (runtime; granted once by the setup script). If no searchable
   rows exist (see `docs/watch-next.md`), the row is hidden, not an error.
3. **Latest on Jellyfin** — `GET {jf}/Users/{uid}/Items/Latest?Limit=20` split Movies /
   Series. Poster `{jf}/Items/{id}/Images/Primary?maxWidth=400`.
4. **Continue watching** — `GET {jf}/Users/{uid}/Items/Resume`, progress bar from
   `UserData.PlayedPercentage`.
5. **On request** — `GET {seerr}/api/v1/request?take=20`, badge per status (Pending /
   Approved / Processing / Available), TMDB poster URLs. OK: no-op in v1.

OK on a Jellyfin row item: Android TV view action with the item id (the Jellyfin app's
documented action; no `jellyfin://` scheme exists — GitHub discussion #3452). Fallback:
plain-launch `org.jellyfin.androidtv`. Verified against the installed 0.19.x during
implementation; if the view action does not resolve, the fallback becomes permanent.

### Configuration and setup script

`settings.txt` in the app's private storage, one key per line (git-ignored; keys are secrets):

```
jellyfin_url=http://192.168.0.158:8096
jellyfin_key=<api key>
jellyfin_user=<user id>
jellyseerr_url=http://192.168.0.158:5055
jellyseerr_key=<api key>
live_sports_app=com.cricfytv.sports
```

`addons/newin/setup.ps1` (and `.sh`) copy game-day's mechanism: base64-push the file through
the FilesProvider with `tools/tv.py`, then `pm grant com.gathin.newin
android.permission.READ_TV_LISTINGS`. Keys are never printed or committed.

### Networking, caching, errors

- Plain `HttpURLConnection` + `org.json`; no external libraries. Fetch on open, 5 s timeout
  per source, each row independent.
- Disk cache of last-good JSON + posters (cap ~20 MB); stale data shows instantly with a
  "updated 2h ago" line, refresh happens behind it.
- A source that fails keeps its row with cached data plus an error line; a source with no
  cache and no data hides its row. Never a blank screen or crash on unreachable NAS.
- Keep it light (repo rule: 1 GB RAM TVs): framework `RecyclerView` rows with focus zoom, no
  background service, no telemetry, only `INTERNET` + `READ_TV_LISTINGS` permissions, both
  documented in the README.

## Repo integration

Per `docs/workflows/addon.md`: own Gradle project + wrappers, README with Permissions
section, `screenshot.jpg` of the rows on the TCL, one row in `addons/README.md`. JVM tests
for the parsing and row-mapping logic (status badges, Watch Next filter, cache fallback),
matching the repo's `launcher/app/src/test` style. `launcher/` never mentions the add-on.

## Verification (needs the TV awake — ADB currently refused, TV in standby)

1. On-TV probes (before building rows that depend on them):
   - `pm list packages` → confirm OwnTV's package id and Cricfy's.
   - `pm dump com.cricfytv.sports` → any VIEW intent filters (expect none; plain launch).
   - Read Watch Next from an app holding `READ_TV_LISTINGS` (not the shell) → does OwnTV
     publish searchable rows, and what do their `intent_uri`s look like.
   - Fire the Jellyfin view action for a known item id; confirm the item opens.
2. End-to-end: sports panel rotates with real fixtures; New In tile opens; each row's OK
   lands in the right app; NAS off → cached rows + error lines, no crash.
3. Repo checks: `python tests/test_addons.py` (or the repo's test entrypoint) green.

## Out of scope (explicit)

Live scores on Home (data terms forbid it; user chose countdown-only), filing Jellyseerr
requests from the TV, poster shelves inside the launcher core, and any launcher-side OK
behaviour on the game_day panel.

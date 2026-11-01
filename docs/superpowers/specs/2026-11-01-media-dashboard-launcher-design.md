# Media Dashboard on Launcher Home — Design

## Context

The News add-on's ambient dashboard (2026-10-31) established the dashboard composition this
project wants: a clock/status header, a lead region with clear priority, supporting slots, a
match strip, stable geometry, and truthful freshness — all in plain Android framework views.
The launcher (`com.gathin.tvhome`) currently offers the glass app dock, module cards
(`Cards.java`), and the Game Day race panel (`GameDay.java`), and stays offline by default.

The user wants that dashboard language on the launcher Home screen itself, with four content
categories — Movies, Series, Sports, Live Channels — fed by the household's internal services
instead of free-my-tv's TMDB/app defaults:

- Movies / Series: Seerr requests and upcoming releases, with deep links into Jellyfin.
- Sports: the existing Game Day provider contract.
- Live Channels: a self-hosted IPTV/m3u playlist.

Chosen architecture (of three considered — in-launcher page, separate add-on with an extended
module contract, porting free-my-tv's trending UI): a dashboard band inside the launcher Home.
No launcher↔add-on contract changes; no TMDB machinery imported.

## Goals

- One dashboard band on Home in the News dashboard's editorial language: lead card, supporting
  cards, quiet status treatment, stable geometry, no slide rotation.
- Four categories — Movies, Series, Sports, Live — selectable from a category rail.
- Every category fed by an internal service; nothing from TMDB or app-store trending defaults.
- The launcher remains offline by default: with no dashboard settings the band is absent and
  Home is exactly as it is today.
- Plain framework views only: no AndroidX, no web rendering, no new app.

## Non-goals

- Changing the Game Day provider contract or the module-card contract (`docs/modules.md`).
- Background refresh when Home is not visible.
- A full IPTV player UI: the band launches streams into the configured player.
- Editing or replacing the existing race panel, dock, or app rows.

## User experience

The Home screen keeps its current stack — SkyView backdrop, hero art, race panel, dock — and
gains a **dashboard band** between the status header and the dock when dashboard sources are
configured.

- A **category rail** (Movies / Series / Sports / Live) heads the band. Up from the dock reaches
  the rail first; left/right moves between categories; the band's content swaps beneath the
  rail. Apps remain front and center: the band never displaces or resizes the dock.
- **Movies / Series**: one lead card (next upcoming, else most recently available request) plus
  a supporting row. Cards show title, type, Seerr status (requested / approved / available) and
  poster where Seerr serves one. OK deep-links into the Jellyfin app's item details. If the item
  is not yet in Jellyfin, the card says so truthfully and OK is a no-op with a hint.
- **Sports**: every published Game Day turn as a fixture card with its lifecycle state
  (upcoming / kickoff / live / final). The existing rotating race panel is unchanged.
- **Live**: channel cards from the m3u playlist — logo, name, group. OK launches the stream
  through the configured player package, falling back to the system stream intent.
- The band hides completely (dock rises, geometry as today) when no sources are configured.

## Data and freshness

- One immutable snapshot per refresh: per-category card lists plus per-source status
  (fresh / cached with age / unavailable), assembled off the main thread and applied atomically.
  No partially updated band; existing cards update in place.
- Independent per-source loaders: Seerr client, m3u parser, Game Day adapter. A failed source
  never marks the others stale; freshness is tracked per source.
- Truthful freshness, verbatim from the News dashboard contract: cached content is labeled with
  age; cached live scores or stream lists are never presented as current; expired items are
  replaced by a deterministic rule rather than collapsing the layout.
- **Jellyfin resolution**: card click resolves the Jellyfin item id by querying the configured
  Jellyfin server with the item's provider id (TMDB/IMDB from Seerr) off-thread, then fires the
  app deep link. Resolution failure shows a visible unavailable state; never a guess.
- Refresh only while Home is visible; scheduling and in-flight work stop when Home pauses.

## Architecture

New classes alongside `GameDay.java`; `MainActivity` only wires them into its existing
lifecycle:

1. **`MediaSnapshot`** — immutable render model (per-category cards, per-source status). No I/O.
2. **`MediaLoader`** — single-thread executor mirroring `GameDay`; runs the sources, reconciles
   results, publishes complete snapshots to the main thread.
3. **`SeerrSource` / `PlaylistSource` / `SportsSource`** — one bounded client each.
   `SportsSource` adapts the existing `GameDay` reading into snapshot cards; it does not
   re-implement provider access. `PlaylistSource` parses m3u with plain character loops — the
   launcher's no-regex/ICU rule from `Cards.java` applies — and bounds every input.
4. **`MediaBand`** — view layer inflated from `layout/media_band.xml`, plain views, own focus
   traversal consistent with the launcher's existing navigation. Applies snapshots; never
   fetches.
5. **`MediaSettings`** — reads the dashboard settings file; guarded `CardFeature`-style: any
   parse problem logs once and disables the band.

## Failure states

- Source failure: retain last-known cards, labeled cached with age; the category's layout
  address stays stable.
- Source unconfigured/missing/empty: reserved quiet state; never borrowed or fabricated
  content (no scores from headlines, no invented channels).
- Partial refresh: previous complete snapshot holds until the next complete one.
- Malformed Seerr/m3u payloads: bounded parse; bad entries dropped with one log line; no crash.
- All sources unconfigured: the band is absent.
- Home pause: refresh scheduling and callbacks stop; resume triggers a fresh load.

## Settings and secrets

- `dashboard.txt` in the launcher's private storage, gitignored, pushed from the desktop by a
  `setup` script the way the News add-on pushes `settings.txt` (adb with `TV_HOST`).
- Keys follow the News add-on's `settings.example.txt` style: `seerr_url`, `seerr_key`,
  `jellyfin_url`, `jellyfin_key`, `jellyfin_package`, `player_package`, `m3u_url`,
  `refresh_minutes`. Exact names settle at implementation against the News example.
- An example file with blanks ships in-repo. No secrets in `config.xml` (in-repo), none in
  chat, none in logs.

## Verification

- Launcher unit tests under existing conventions: m3u parsing (well-formed, malformed, huge),
  Seerr payload handling, snapshot reconciliation, deterministic expiry.
- `./gradlew test assembleRelease` on the launcher; News add-on build unaffected (no contract
  changes).
- On-device pass on the TCL (adb, 192.168.0.153): four categories render; Jellyfin deep link
  resolves and opens; an m3u channel launches in the player; Game Day fixtures show lifecycle
  states; cached/stale labels truthful after network loss; dock focus path unchanged; band
  absent with no settings file. Build success alone is not visual acceptance.

## Assumptions and decisions

- The dashboard lives on the launcher Home screen (user's choice), in the News dashboard's
  visual language, not as a new add-on and not by porting free-my-tv's trending UI.
- Movies/Series data comes from Seerr; watching happens in Jellyfin (user's choice).
- Live Channels come from the household's self-hosted m3u playlist (user's choice).
- Sports reuse the existing Game Day provider unchanged.
- Exact slot counts, typography, colors, refresh interval and stale thresholds follow existing
  launcher/theme conventions and are settled during implementation against the concrete view
  and data code — constrained by stable geometry, remote readability at distance, truthful
  freshness, and bounded parsing.

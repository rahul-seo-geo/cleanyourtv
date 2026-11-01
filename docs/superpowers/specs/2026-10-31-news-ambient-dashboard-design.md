# News Ambient Dashboard Design

## Context

The News add-on is currently a non-interactive Android `DreamService` that rotates one item at a time. It fetches configured RSS feeds and optional Seerr media, caches responses, and stops its work when the dream detaches. The requested redesign makes it a single ambient dashboard on a 1080p Android TV, including news, a match-day strip, and a launcher row.

The dashboard remains the News screensaver. It does not become the launcher Home screen and does not refresh in the background when the screensaver is stopped.

## Goals

- Show a stable, glanceable composition rather than a sequence of full-screen slides.
- Use a configurable mix of the existing RSS and Seerr sources.
- Include match information by consuming the existing Game Day content-provider contract, without adding a sports API or credentials.
- Make freshness and cached/unavailable states visible without displacing content.
- Preserve plain Java Android framework views: no AndroidX in the News add-on.
- Keep a predictable launcher row that is hidden during ambient viewing and revealed on remote input without moving dashboard content.

## Non-goals

- Add a Home-screen surface or a second launcher-owned rendering implementation.
- Add a background worker or keep feeds fresh when the dream is not visible.
- Add an external sports-data service or change the existing Game Day provider contract.
- Reproduce Watermelon UI React components or introduce React/web rendering. Watermelon UI is inspiration for editorial hierarchy, compact supporting cards, and restrained status treatment only.

## User experience

The 1920×1080 screen uses a fixed composition with a clock/status header, a lead-story region, a smaller supporting-content region, a dedicated match-day strip, and a bottom launcher row. The lead region gives one item clear visual priority; supporting slots can contain RSS headlines and configured Seerr media items. Each slot keeps its screen position across refreshes. Data is reconciled by stable item identity where available; when an item expires or disappears, its slot is replaced according to a deterministic selection rule rather than collapsing the layout.

The launcher row is hidden during ambient viewing. Remote navigation reveals the row and its focus state; this overlay must not resize or reposition the news, match, or clock regions. Focus traversal is deterministic, with clear focus indication suitable for a TV viewed at a distance. The dashboard itself remains legible without interaction.

The match strip uses stable fixture positions and a compact lifecycle presentation: upcoming/kickoff, live, and final. It reads the installed/configured Game Day provider using the documented provider metadata and `/turns` contract. If the provider is not configured, absent, empty, or unreadable, the strip keeps its geometry and presents a quiet unavailable/empty state rather than borrowing sports headlines as if they were scores.

## Data and freshness behavior

- Refresh when the DreamService starts and periodically while it remains visible; cancel scheduled refreshes and work when it detaches.
- Continue using the existing RSS/Seerr fetch and cache behavior where it fits. Preserve last-known content on fetch failure and mark it cached with its age.
- Track freshness per source, not only globally. A failed source must not make healthy sources appear stale.
- Match freshness is independent and stricter than editorial-news freshness. A cached score must be explicitly marked stale; do not imply that cached match data is live.
- Publish a complete dashboard snapshot to the UI thread so a refresh does not expose partially updated regions. Existing views should be updated in place when their stable identity is retained.
- Do not retain or show expired content as current. Empty/unavailable slots remain reserved and clearly distinguished from fresh content.
- Keep existing cache bounds and lifecycle cleanup unless exploration or implementation proves a change is necessary.

## Architecture and boundaries

1. **Snapshot/model layer:** represent the selected stories, fixture turns, source timestamps/status, and slot identities as one immutable render snapshot. The view layer should not fetch data or interpret provider rows.
2. **Data loading/reconciliation:** extend the existing loader path to retrieve RSS/Seerr content and query the optional Game Day provider off the main thread. Convert provider rows into dashboard fixture data, merge cache/fetch outcomes, and reconcile stable slots.
3. **DreamService view layer:** replace slide rotation with a fixed dashboard hierarchy built from plain framework views. Apply each completed snapshot on the main thread; keep the clock tick and detach cleanup explicit.
4. **Interaction layer:** remote input reveals and focuses the launcher row. Launcher destinations must be confirmed against existing app/intent conventions before implementation; do not invent package targets. Dismiss/timeout behavior returns to the ambient state without stopping data refresh while the dream remains visible.

The News add-on remains separately buildable and must handle the Game Day package/provider being missing or unconfigured. The implementation must respect Android package visibility and content-provider access available to the add-on; if the existing documented contract cannot be queried from News under current manifest/device constraints, surface that as a blocker and do not alter the launcher provider contract without a new design decision.

## Failure states

- RSS/Seerr source failure: retain eligible last-known items, label as cached/stale with age, and leave the source's layout address stable.
- Game Day provider missing, inaccessible, or returning no turns: show a distinct quiet unavailable/empty state; never fabricate fixtures or infer scores from headlines.
- Partial refresh: keep the last complete dashboard snapshot until the next complete snapshot is ready.
- Dream detach: stop refresh scheduling, cancel callbacks, and release loader/provider observers and image work consistent with existing cleanup.
- Invalid/missing content: use bounded text and safe placeholders; no layout collapse or unchecked network-loaded assets.

## Visual direction and inspiration

Use Watermelon UI's dashboard and editorial/blog block catalog as references for hierarchy and composition, not as code dependencies: [Dashboards](https://ui.watermelon.sh/dashboards), [Blog blocks](https://ui.watermelon.sh/blocks/blog), and [Activities card](https://ui.watermelon.sh/animated-components/activities-card). Adapt the ideas to Android TV constraints: larger type, fewer simultaneous items, high contrast, stable focus targets, restrained motion, and no React/Tailwind runtime.

## Verification

- Build the News add-on using its existing Gradle project and verify the launcher build remains unaffected unless a shared contract change is explicitly approved.
- Exercise real view rendering for a fresh mixed snapshot, cached/offline snapshot, missing/empty Game Day provider, upcoming/live/final fixtures, and refresh with stable story identities.
- Confirm matching items update in place, source failure does not shift neighboring regions, and match data cannot be mistaken for fresh after provider failure.
- Exercise remote navigation: row hidden at rest, revealed on input, focus visible, predictable traversal, and dashboard geometry unchanged.
- Verify detach cancels refreshes and callbacks, and reattach triggers a fresh load.
- Inspect the result on a 1080p Android TV or equivalent device for distance readability, clipping, contrast, and remote focus. Emulator/build success alone does not establish this visual acceptance.

## Assumptions and decisions

- “All of this” means implement the dashboard screen, match-day strip, and launcher row as one News screensaver redesign.
- The launcher row is **shown on remote input**, the user’s selected option.
- The News area uses a **configurable mix** of existing RSS and Seerr items, per user selection.
- Refresh occurs **only while the DreamService is visible**, per user selection.
- Match data comes from the **existing Game Day provider**, per user selection.
- The architecture choice is to replace the current News screensaver presentation, not add a launcher Home surface.
- Exact slot counts, typography, colors, refresh interval, stale thresholds, item expiration window, and launcher destination set should follow existing project/theme conventions and be settled during implementation after inspecting the concrete view/data code. These are constrained by stable geometry, remote readability, truthful freshness, and no invented app targets.

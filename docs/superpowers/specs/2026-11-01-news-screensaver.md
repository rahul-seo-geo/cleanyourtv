# News screensaver add-on — design

## Goals

- Replace the Aerial Views screensaver (tile already hidden) with a Daydream that is
  useful at a glance: rotating slides of upcoming releases and news.
- Content is the combination the owner picked: Seerr upcoming films/series, world/AU
  headlines, and team news for the clubs followed in Game Day (Arsenal, Real Madrid).
- Form is a screensaver, exactly the slot Aerial Views used to fill — no launcher
  tile, no dock card.

## What it shows

Fullscreen slides on the TV's aurora background (`#070A14`, accent `#4FC3F7`), one
slide at a time, 600 ms alpha crossfade, default 18 s per slide (`slide_seconds`,
clamped 8–120):

- Headlines: newest items from each configured RSS feed (default ABC News AU, BBC
  News).
- Team news: BBC Sport per-team feeds (Arsenal, Real Madrid).
- Upcoming: Jellyseerr discover-upcoming films and series, title + release date +
  poster (via the same Seerr image-URL pattern the New In add-on uses).
- A clock (HH:mm) top-left, updated every minute.

Sections interleave round-robin (abc → bbc → arsenal → real-madrid → upcoming film →
upcoming series → …), each list trimmed to its 6 newest first, so no single feed
monopolizes the rotation.

## Data sources (probed 200 on 2026-11-01)

- RSS: `https://www.abc.net.au/news/feed/51120/rss.xml`,
  `https://feeds.bbci.co.uk/news/rss.xml`,
  `https://feeds.bbci.co.uk/sport/football/teams/arsenal/rss.xml`,
  `https://feeds.bbci.co.uk/sport/football/teams/real-madrid/rss.xml`
- Seerr: `GET {jellyseerr_url}/api/v1/discover/movies/upcoming` and
  `/api/v1/discover/tv/upcoming`, header `X-Api-Key`, no query params.

## Behavior

- One `DreamService` (`com.gathin.news/.NewsDream`), plain framework views, no
  AndroidX.
- A loader thread fetches all sources, caches each response to disk (newin's
  `Cache`), and merges into the slide list. On network failure the dream falls back
  to cached slides; with no cache at all it shows a single "offline" slide. Never a
  blank or crashed dream.
- Upcoming slides simply disappear when Seerr is unconfigured or unreachable.

## Configuration

Same pattern as New In: `settings.txt` pushed through the WRITE_SECURE_SETTINGS
provider (`com.gathin.news.files`), gitignored, `settings.example.txt` with
placeholders committed. Keys: `jellyseerr_url`, `jellyseerr_key`, `slide_seconds`,
and `feed1..N` as `url|display name` — feeds are a list, so a future France feed is
`feed5=…` with no code change. Defaults when no settings file exists: the four
feeds above, 18 s slides, no Seerr.

Activation (documented in the add-on README, done by setup script or by hand):
Settings → Device Preferences → Screen saver → News.

## Repo integration

- New folder `addons/news/`, package `com.gathin.news`, label `News`; icon is the
  newin glyph recolored `#FFFFC857` so the two add-ons are distinguishable.
- Copied-and-repackaged infrastructure from `addons/newin`: `Fetch`, `Cache`,
  `FilesProvider`, `Settings` (extended with `keysWithPrefix`), `Ago`.
- JUnit4 JVM tests for the RSS parser and the slide merge; `tests/test_addons.py`
  must stay green (screenshot is captured at bring-up).

## Out of scope (explicit)

- No launcher (`launcher/`) changes; no dock card or home row.
- No live scores or match state — Game Day already covers live fixtures.
- No France men's team feed yet: no reliable feed was verified at design time; if
  one is found it is a settings-line addition (`feed5`), not a code change.

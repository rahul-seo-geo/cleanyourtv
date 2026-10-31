# News

News is an optional Clean Your TV Daydream screensaver. It rotates ABC and BBC headlines, Arsenal and Real Madrid news, and optional Seerr upcoming films and series. Previously fetched feeds and posters remain available offline.

![News on a TV](screenshot.jpg)

## Set up

Build with JDK 17 and Android SDK 34: `./gradlew test assembleRelease`. Install `app/build/outputs/apk/release/app-release.apk` on the TV. Copy `settings.example.txt` to `settings.txt` and fill in your Seerr URL and API key, or leave both blank to show news only. `settings.txt` is ignored by git. Run `./setup.sh` with `TV_HOST` set, or `setup.ps1 -TvHost <tv address>` in PowerShell, to send settings privately.

| Setting | Purpose |
|---|---|
| `jellyseerr_url`, `jellyseerr_key` | Optional Seerr connection for upcoming films and series |
| `slide_seconds` | Time per slide, default 18 seconds (8–120) |
| `feed1`, `feed2`, … | RSS URL and display name separated by `|`; defaults to the four feeds in the example when settings are absent |

## Screensaver

Select Settings → Device Preferences → Screen saver → News. The dream starts when the TV idles; it has no launcher tile. On TCL TVs the firmware's autostart gate must allow the dream service before the system can bind it; the setup scripts handle this with a one-time `appops set com.gathin.news AUTO_START allow`.

Tested on: TCL 65C6K, Android TV 14.

## Permissions

- `INTERNET` — fetch RSS headlines, Seerr upcoming releases, and posters.
- `READ_TV_LISTINGS` — declared in the screensaver manifest; the dream does not read listings or require a grant.
- The exported settings provider requires `WRITE_SECURE_SETTINGS` from its caller to write private settings; the app does not request this permission.

The background DreamService runs only while the screensaver is active. No ads or telemetry.

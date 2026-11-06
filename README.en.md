<p align="center">
  <img src="docs/img/icon.svg" width="96" alt="Loopa">
</p>

<h1 align="center">Loopa</h1>

<p align="center">
  A lightweight open-source Android viewer for RedGIFs short videos: an ad-free feed, smooth vertical swiping and
  instant playback.
  <br><br>
  <a href="https://github.com/jainikdroid-bit/loopa/releases/latest"><b>Download APK</b></a> ·
  <a href="https://jainikdroid-bit.github.io/loopa/">Website</a> ·
  <a href="README.md">Русский</a>
</p>

> [!WARNING]
> **Adults only (18+).** The app shows content from RedGIFs, some of which is sexually explicit. Install it only if
> you are 18 or older and viewing such content is legal where you live.

> [!NOTE]
> **Unofficial app.** Loopa is not affiliated with or endorsed by RedGIFs. RedGIFs is a trademark of its owners.
> The app hosts and redistributes nothing: it only displays what the service already serves to any web browser.

<p align="center">
  <img src="docs/img/screens.svg" width="820" alt="Video grid, player and tag search">
</p>

## Features

- **Ad-free feed** — boosted and promoted videos are filtered out.
- **Instant start** — neighbouring videos are preloaded (Media3 PreloadManager), playback starts after half a second of
  buffer, watched videos come from cache.
- **Full-screen vertical swiping**, seek bar, pause, sound toggle.
- **Auto quality** — HD on fast networks, SD on slow ones; or pin HD / SD.
- **Tag search** with suggestions and counts; text search when the query isn't made of tags.
- **Sorting** — relevance, trending, top of week / month / all time, newest, plus longest / shortest first, most
  liked, most viewed, rating (likes per view). Creator pages also offer “oldest first”.
- **Filters** — duration (under 15 s, 15–60 s, over a minute) and “with sound only”. Your choice is remembered.
- **Built for bad networks.** Drops and timeouts are retried automatically; the feed and the video resume as soon as
  the connection is back. On slow links Auto quality picks SD, and if HD stalls anyway it switches to SD from the same
  second; neighbour preloading backs off so the current video gets the bandwidth. Thumbnails on screen load first and
  are cached on disk.
- **Creator pages** and tag jumps straight from the player.
- **Optional sign-in** — “For you” feed, likes, “My likes”. Sign-in is remembered and silently renewed in the
  background, so you don't have to log in again.
- **Downloads** via the system download manager into `Downloads/Loopa`.
- Small, with no trackers, analytics or ad SDKs.

The interface is currently in Russian; tags on the service are in English. Translations are welcome.

## Install

1. Get `Loopa-<version>.apk` from [Releases](https://github.com/jainikdroid-bit/loopa/releases/latest).
2. Open it on your phone and allow installs from that source.
3. Optionally verify it against `SHA256SUMS`.

Requires Android 8.0+.

## Privacy

- The app talks only to `api.redgifs.com` and RedGIFs video servers. The sign-in window is the exception: it shows the
  real `redgifs.com` site with everything that site loads.
- Without sign-in it uses the temporary guest token the service hands to every website visitor.
- Sign-in happens on the real `redgifs.com` page in an embedded window. You type your password into the site; the app
  never sees or stores it — only the session token is kept, in the app's private storage on your phone.
- Cloud backup of app data is disabled.
- The app identifies itself honestly (`User-Agent: Loopa/…`) instead of pretending to be a browser.

## Build

```bash
git clone https://github.com/jainikdroid-bit/loopa.git
cd loopa
./gradlew assembleRelease
```

Requires JDK 17 and Android SDK 35. Without `keystore.properties` the release build is signed with the debug key, which
is fine for personal use. APKs in Releases are signed with the project key; certificate SHA-256 fingerprint:

```
32:9D:15:73:05:60:8C:5D:F2:1B:D7:7B:ED:3F:9C:70:DC:1C:38:D0:F9:44:A6:F8:C9:69:26:12:90:44:9C:84
```

## Be gentle with the service

Loopa uses the same API as the website and behaves like a regular viewer: the guest token is fetched once and reused for
up to 12 hours, videos load only as you watch, there is no bulk downloading. Please don't turn it into a scraper.

## Rights holders

The app does not host videos or bypass access restrictions. If you represent RedGIFs and have concerns about this
project, please open an [issue](https://github.com/jainikdroid-bit/loopa/issues).

## License

[MIT](LICENSE). Uses AndroidX and Media3 (Apache 2.0). API paths learned from the open-source wrapper
[scrazzz/redgifs](https://github.com/scrazzz/redgifs).

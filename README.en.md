<p align="center">
  <img src="docs/img/icon.svg" width="96" alt="Adult Space">
</p>

<h1 align="center">Adult Space</h1>
<p align="center"><i>(formerly Loopa — same app, updates install over it)</i></p>

<p align="center">
  A free, lightweight open-source Android app for short videos from RedGIFs, Reddit, Erome, Eporner, xVideos, XNXX, Pornhub, RedTube, xHamster and FikFap in one ad-free
  feed: full-screen vertical swiping, fast playback, its own “For you”, search and downloads (Reddit videos with sound).
  <br><br>
  <a href="https://github.com/jainikdroid-bit/loopa/releases/latest"><b>Download APK</b></a> ·
  <a href="https://jainikdroid-bit.github.io/loopa/">Website</a> ·
  <a href="README.md">Русский</a>
</p>

<p align="center">
  <img src="docs/img/graph.gif" width="360" alt="3D interest graph: your taste as a starry sky">
  <br><sub><i>3D interest graph: your taste as a starry sky</i></sub>
</p>

> [!WARNING]
> **Adults only (18+).** The app shows content from RedGIFs, Reddit, Erome, Eporner, xVideos, XNXX, Pornhub, RedTube, xHamster and FikFap, some of which is sexually explicit. Install it only if
> you are 18 or older and viewing such content is legal where you live.

> [!NOTE]
> **Space.** Adult Space is your own universe of short videos: interests look like stars, the links between them like constellations. The 3D graph shows your taste as a night sky.

> **Unofficial app.** Adult Space is not affiliated with or endorsed by RedGIFs, Reddit, Erome, Eporner, xVideos, XNXX, Pornhub, RedTube, xHamster or FikFap. Their names are
> trademarks of their owners.
> The app hosts and redistributes nothing: it only displays what the service already serves to any web browser.

<p align="center">
  <img src="docs/img/shot-welcome.jpg" width="260" alt="Welcome screen: choosing sources">
  <img src="docs/img/shot-grid.jpg" width="260" alt="Video grid with tags">
  <img src="docs/img/shot-player.jpg" width="260" alt="Player with clip tags">
  <br><sub>Clips in the screenshots are blurred.</sub>
</p>

## Features

- **Ad-free feed** — boosted and promoted videos are filtered out.
- **Live grid previews** — cut like big video sites do: snippets from the start, a third, two thirds and the end, on a
  loop, so you get the gist at once. Thumbnails load first, video fades in when ready. **RG** / **RD** badges show
  RedGIFs or Reddit. Fewer previews on slow networks; can be turned off in the settings.
- **Fast start and smart preloading.** The grid loads ahead what is about to appear: from the fling velocity it
  works out where the feed will stop, so thumbnails there (and one screen beyond) are already in memory when you get
  there, and the first seconds of those videos are in the disk cache; the next feed page is requested three screens
  before the end. In the player, the start of the next 1–2 videos in the swipe direction is preloaded (Media3
  PreloadManager), but only once the video on screen has buffer: while it is loading, preloading pauses. Depth depends
  on the network: fast — more, mobile — one video, Data Saver — nothing; fast swiping skips the clips in between.
- **Four colour themes:** black (default), pink, red and violet — the background stays dark, the accent colour and the tint of surfaces change. Choose in ⧩ → “Settings” → “Appearance”; applied at once on every screen, including the player and the 3D graph.
- **Disk cache.** Videos — an LRU cache of 512 MB–1 GB depending on free space (or 512 MB / 1 GB / 2 GB in ⧩ → “Settings” → “Video
  cache”); the player, preloading and live previews all write to it, so rewatching plays from disk without network.
  Thumbnails — 128 MB on disk plus an in-memory LRU, decoded at cell size.
- **Built for long links** (VPN, satellite, ~800 ms ping, ~5 Mbit/s): one HTTP client (OkHttp) with a connection pool
  and HTTP/2, cached DNS and redirects, connections to video and image servers opened ahead of time; video streams in one
  request, playback starts after 1 s of buffer, 15–20 s timeouts without needless restarts; Auto quality starts in SD
  and switches to HD only when measured speed allows. A dead link shows “can't be opened” right away.
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
- **“Similar” in the player** (▦ button): a panel of videos similar in meaning — by specific tags, title words, creator
  and subreddit, from all enabled sources. Tap one and it is queued right after the current video.
- **Erome and Eporner.** Tabs for these sites with their own search and “newest” sorting; a video's creator and tags are
  searched on the same site. Optional sign-in on the site's own page in an embedded window; once signed in, “For you”
  also draws on your subscriptions and favourites there.
- **xVideos, XNXX, Pornhub, RedTube, xHamster and FikFap.** Each site gets a tab in the feed when it is enabled, with its
  own search. Their videos are mixed into “For you”, search and “Similar” by your interests. There is no sign-in for
  them. These sites sign their video links and the links expire quickly, so a video's address is only resolved when
  it is opened; a removed video shows “can't be opened” right away.
- **Welcome screen** on first launch: pick sources (including the new sites) and sign in to them one by one (every step can be skipped). Sources
  can be changed later via ⧩ → “Settings” → “Sources”.
- **Optional sign-in** — “For you” feed, likes, “My likes”. Sign-in is remembered and silently renewed in the
  background, so you don't have to log in again.
- **Reddit in the same client.** A Reddit tab with your subscriptions, any subreddits (several at once, space-separated)
  and user posts; hot, new, rising and top of hour / day / week / month / year / all time. Videos are taken from posts:
  Reddit-hosted videos (with sound), RedGIFs links, Imgur and GIFs. Sign-in happens on reddit.com in an embedded window,
  just like RedGIFs; 18+ content appears if it's enabled in your Reddit account settings.
- **Auto-update from GitHub.** On every launch (and every 6 hours) Adult Space checks the latest release, downloads the APK
  and verifies its SHA-256 against the release file; mode is manual / ask / automatic (⧩ → “Settings” → “Updates”).
- **Taste network and 3D interest graph** (⧩ menu → “Interest graph…”). Tags, creators, subreddits, words, searches and
  sites are nodes of one network linked by the clips you like; it grows as you use the app. In the 3D graph sphere size
  is weight and colour is community, with type filters, search, zoom, a weight slider on any node and “Open in feed”.
  Rendering adapts to the phone hardware: low-end phones draw less, the logic stays the same.
- **“For you” with its own algorithm.** An interest profile learns from likes, total watch time (including replays), skips,
  searches and downloads — by tags, words in titles and descriptions, subreddits and creators; old tastes fade on their
  own. Tags work as a small “taste network”: tags you like, download and search often weigh more, tags that keep
  showing up together get linked, and a tag never liked by itself but always next to your favourites gets weight
  from them; words of a search are linked to the tags of clips you like right after it, and skipping a clip the
  network expected you to like counts double. It picks RedGIFs videos and mixes in Reddit posts from matching subreddits (18+ included, which Reddit's own
  recommendations leave out), plus Erome, Eporner, xVideos, XNXX, Pornhub, RedTube, xHamster and FikFap if enabled. Each source's share is “low / medium / high”; the
  profile is shown in “My interests” and in the 3D interest graph (⧩ menu → “Interest graph…”: sphere size is the tag weight, a slider raises or lowers any tag) and stays on the phone. While interests are still few, the feed is topped up
  with popular videos so it never runs dry.
- **Hide topics:** gay content, sissy/femboy/trans (lesbian stays) and your own words. Duplicate videos and reuploads
  are removed from the feed.
- **Downloads** into `Downloads/Loopa` — player button or long-press in the grid: RedGIFs, Imgur, Erome, Eporner, xVideos, XNXX, Pornhub and RedTube
  via the system download manager (mp4); Reddit videos are fetched as separate video and audio tracks and muxed into one mp4
  without re-encoding. xHamster and FikFap videos (HLS only) can't be downloaded yet.
- Small, with no trackers, analytics or ad SDKs.

The interface is available in 10 languages (system language by default; change it via ⧩ → “Settings” → “Appearance”); tags on the service are in English. Translations live in `i18n/strings.json` — improvements welcome.

## Install

1. Get `Loopa-<version>.apk` from [Releases](https://github.com/jainikdroid-bit/loopa/releases/latest).
2. Open it on your phone and allow installs from that source.
3. Optionally verify it against `SHA256SUMS`.

Requires Android 8.0+.

## Privacy

- The app talks only to the sources themselves: the RedGIFs API and video servers; Reddit (`oauth.reddit.com` /
  `www.reddit.com`, `v.redd.it`, `i.redd.it`, `preview.redd.it`, `i.imgur.com`); `www.erome.com` and its video servers;
  the open `www.eporner.com` API; the pages and video servers of xVideos, XNXX, Pornhub, RedTube, xHamster and FikFap. For “related tags” in search it queries the open Pornhub webmasters API
  (`www.pornhub.com/webmasters`, the query text only, nothing about you). The sign-in windows are the exception:
  they show the real sites with everything those sites load.
- Without sign-in it uses the temporary guest token the service hands to every website visitor.
- Sign-in happens on the real site page (`redgifs.com`, `reddit.com`, `erome.com`, `eporner.com`) in an embedded window. You type your password into the site; the app
  never sees or stores it — only the session token or cookies are kept, in the app's private storage on your phone.
- Cloud backup of app data is disabled.
- The app identifies itself honestly (`User-Agent: Loopa/…`) instead of pretending to be a browser.

## Versions

The latest APK in [Releases](https://github.com/jainikdroid-bit/loopa/releases) is **1.20**.

| Version | What's new |
|---|---|
| 1.10 | Instagram-style preloading: scroll forecast, thumbnails then video starts ahead, next page early; disk cache 512 MB–1 GB with a menu setting; OkHttp with connection reuse, tuned for ~800 ms ping |
| 1.10.1 | Slow networks (under 9 Mbit/s): 2 live previews instead of 8, on-screen thumbnails load first, background loads no longer delay the opened clip; tiles do not flicker; cache up to 5–10 GB (video) and 1 GB (thumbnails); ♥ badge on liked clips; For You uses like ratio and author/topic diversity; search considers your interests |
| 1.10.2 | The feed is not empty on launch: the previous one (For You, Trending) shows at once and fresh clips are appended; thumbnails are fetched 3–6 screens ahead, video after them; on slow networks up to 6 live previews when the clips are fully cached |
| 1.11 | Share a RedGIFs or Reddit link (post, redd.it, v.redd.it) to Loopa and it downloads the clip right away to Downloads/Loopa; likes you gave on Reddit earlier now count toward For You tastes (My interests → Import Reddit likes) |
| 1.11.1 | Share links from a Reddit user profile (`/u/name/s/code`, `/user/…`) are downloaded too |
| 1.11.2 | Sharing a RedGIFs page (search, user, tag) opens it in the Loopa feed |
| 1.12 | Auto-update from GitHub Releases (checked every 6 hours, downloaded, SHA-256 verified, installed on a tap; “Updates…” menu); paste a RedGIFs or Reddit clip link into search and Loopa downloads it |
| 1.12.1 | Update mode to choose: manual / ask (download and ask) / automatic (download and install); ⧩ menu → “Updates…” |
| 1.12.2 | “For you” taste network: tags frequent in your likes and searches weigh more, weight flows to tags that always sit next to your favourites; search words get linked to the tags of clips you liked after it; “Close to your favourites” in “My interests” |
| 1.13 | Six new sources: xVideos, XNXX, Pornhub, RedTube, xHamster, FikFap — checkboxes on the welcome screen and in 👤 → “Sources”, tabs in the feed, mixed into “For you”, search and “Similar”; downloads work for xVideos, XNXX, Pornhub, RedTube (xHamster and FikFap not yet) |
| 1.14 | 3D interest graph (⧩ menu → “Interest graph…”): tags are spheres sized by weight, links are what you like together, communities are coloured as in graphify; tag search, zoom with buttons and fingers; “My weight for this tag” slider ×0.1…×3 makes a tag show up more or less in “For you”; “Open in feed” |
| 1.15 | Taste network from all interests: tags, creators, subreddits, title words, searches and sites are nodes of one network; a liked clip links its tags with its creator, subreddit and site, a search links to the tags of clips liked after it; weight flows across types and “For you” scores clips by the whole network; the network grows with use (up to 1500 tags, 6000 links); the 3D graph shows up to 2000 nodes with type filters, a weight slider on any node and “Open in feed” for creators, subreddits, searches and sites |
| 1.16 | The 3D graph adapts to the hardware: memory, CPU cores and 64-bit support give the phone a level (low, mid, high); on low-end phones fewer nodes, links and labels are drawn with simpler spheres and lines, and if the frame rate drops below 24 fps the graph simplifies itself. The taste network logic, search and sliders stay the same on every phone |
| 1.17 | Updates are checked on every app launch (at most once per 10 minutes), not only every 6 hours: a new version is found right away |
| 1.17.1 | 3D graph: after picking a tag (by search or from neighbours) and changing its weight, the graph rotates around its own centre rather than that tag — the sphere no longer sticks to the middle of the screen |
| 1.18 | Site intros are skipped: first 3.4 s on Pornhub and RedTube, 5 s on xHamster (measured); live previews start after it and the player starts after it, you can seek back to it, downloads stay complete. Interests are counted by total watch time of a clip (including replays and returns), not by watching to the end, so long videos count too. Shorter “For you” header without the list of sources |
| 1.19 | The app is renamed Adult Space (formerly Loopa): new constellation icon, a starry background for the 3D graph (off on weak phones), a space-themed line on the welcome screen; fixed: live previews in the grid no longer freeze — background loads were limited to 5 requests per server, and a stuck preview now has a watchdog that restarts it |
| 1.20 | Menu and theme design: the ⧩ menu is short now (sorting, duration, sound, interest graph, “Settings…”); everything else moved to a Settings screen with sections: Feed, Sources, Interests, Video and cache, Accounts, Appearance, Updates, About; the 👤 menu keeps quick sign-in/sign-out. Four themes: black (as before), pink, red, violet — the accent colour and the tint of surfaces change on every screen, including the player and the 3D graph; applied at once |
| 1.9.2 | No more frozen start: the player page isn't rebound when the feed loads more, no stale frame on swipe, neighbours preload after the current video has buffer, dead links fail fast |
| 1.9.1 | “For you” never runs dry: topped up with popular videos when interest matches are few |
| 1.9 | Erome and Eporner, sign-in to them, “Similar” in the player, welcome screen, source shares “low / medium / high” |
| 1.7.1 | Back returns to the previous results, previews play while scrolling, “For you” by tag pairs; a broken video no longer skips ahead |
| 1.7 | Interface in 10 languages |
| 1.6–1.6.8 | Live relevance search, synonyms and abbreviations, tag relations, more Reddit |
| 1.5–1.5.1 | ↑↓ sorting, Reddit and related tags in search, previews in every visible cell |
| 1.4 | Reddit via the website, “Hide”, no duplicates |
| 1.3 | Cut-style live previews, RG / RD badges |
| 1.2 | “For you” with its own interest algorithm |
| 1.1 | Reddit in the same client, Reddit downloads with sound |
| 1.0 | First release: ad-free RedGIFs |

## Build

```bash
git clone https://github.com/jainikdroid-bit/loopa.git
cd loopa
./gradlew assembleRelease
```

Requires JDK 17 and Android SDK 35. Unit tests (scroll forecast, preload depth, cache size): `./gradlew testReleaseUnitTest`. Without `keystore.properties` the release build is signed with the debug key, which
is fine for personal use. APKs in Releases are signed with the project key; certificate SHA-256 fingerprint:

```
32:9D:15:73:05:60:8C:5D:F2:1B:D7:7B:ED:3F:9C:70:DC:1C:38:D0:F9:44:A6:F8:C9:69:26:12:90:44:9C:84
```

## Be gentle with the service

Adult Space uses the same API as the website and behaves like a regular viewer: the guest token is fetched once and reused for
up to 12 hours, videos load only as you watch, there is no bulk downloading. Please don't turn it into a scraper.

## Rights holders

The app does not host videos or bypass access restrictions. If you represent one of these services or are a rights holder and have concerns about this
project, please open an [issue](https://github.com/jainikdroid-bit/loopa/issues).

## License

[MIT](LICENSE). Uses AndroidX and Media3 (Apache 2.0). API paths learned from the open-source wrapper
[scrazzz/redgifs](https://github.com/scrazzz/redgifs).

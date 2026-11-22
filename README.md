# Kixtra

A no-login Android viewer for Kick, inspired by [Xtra](https://github.com/crackededed/Xtra).

**Watching**
- Live streams are rewindable: they play the stream's VOD at the live point, so you can scroll back anytime; "Go live" jumps back. At 2–4x you catch up to live seamlessly. (A plain low-latency live mode is in Settings.)
- VODs and clips with Xtra-style quality picker (incl. audio only) and speed up to 4x — both remembered
- Resume where you left off, including streams you stopped watching while they were live
- Instant scrub previews, prepared in the background while you watch
- "Continue in VOD" from any clip
- Swipe down to a floating mini player and keep browsing; picture-in-picture and background audio

**Chat**
- Live chat and VOD chat replay in sync at any speed, with emotes
- Resizable side chat in landscape, plus an optional pop-out chat overlay on the video

**Browsing**
- Search channels, live streams and categories, with filters
- Local follows (stored on the device), sorted by most recently live, with live follows featured
- Top live streams and categories with language/sort filters; top clips by channel or category

## Getting the APK

Every push to `main` builds the app on GitHub Actions and publishes it as the
**latest** release: Releases → `latest` → `kixtra.apk`. Builds are signed with the same
private key (stored as the `KIXTRA_KEYSTORE_B64` and `KIXTRA_KEYSTORE_PASSWORD`
repository secrets), so new builds install over old ones. Local builds use the debug key.

## Notes

Kick has no public API for VODs or playback, so Kixtra uses the same internal endpoints as
kick.com and open-source tools (yt-dlp, streamlink, Kicker, OpenKick). If Cloudflare blocks
plain requests, the app quietly retries them through a hidden WebView.

License: AGPL-3.0

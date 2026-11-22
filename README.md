# Kixtra

A no-login Android viewer for Kick, inspired by [Xtra](https://github.com/crackededed/Xtra).

- Search channels, live streams and categories
- Live streams, VODs, and **live VODs that keep growing** (watch a stream from the start while it's still live)
- Xtra-style quality picker (incl. audio only) and VOD speed up to 4x — both remembered
- Live chat and synced VOD chat replay (with emotes)
- Local follows (stored on the device), browse top streams and categories
- Picture-in-picture and background audio
- Opens kick.com links

## Getting the APK

Every push to `main` builds the app on GitHub Actions and publishes it as the
**latest** release: Releases → `latest` → `kixtra.apk`. All builds are signed with the
same key (`app/kixtra.jks`), so new builds install over old ones.

## Notes

Kick has no public API for VODs or playback, so Kixtra uses the same internal endpoints as
kick.com and open-source tools (yt-dlp, streamlink, Kicker, OpenKick). If Cloudflare blocks
plain requests, the app quietly retries them through a hidden WebView.

License: AGPL-3.0

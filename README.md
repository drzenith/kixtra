# Kixtra

A no-login Android viewer for Kick, inspired by [Xtra](https://github.com/crackededed/Xtra).

- Search channels, live streams and categories, with type / live-only / language filters
- Clips: top clips, per channel and per category, by views or date over day/week/month/all
- Language + sort filters for live streams, default language and hide-18+ in Settings
- Live streams, VODs, and **live VODs that keep growing** (watch a stream from the start while it's still live)
- Xtra-style quality picker (incl. audio only) and VOD speed up to 4x — both remembered
- Live chat and synced VOD chat replay (with emotes)
- Local follows (stored on the device), browse top streams and categories
- Phone-first layout with a bottom tab bar; grids and side-by-side chat on wide screens
- Automatic fallback to software video decoding (for Waydroid/emulators)
- Picture-in-picture and background audio
- Opens kick.com links

## Getting the APK

Every push to `main` builds the app on GitHub Actions and publishes it as the
**latest** release: Releases → `latest` → `kixtra.apk`. All builds are signed with the
same private key (stored as the `KIXTRA_KEYSTORE_B64` and `KIXTRA_KEYSTORE_PASSWORD`
repository secrets), so new builds install over old ones. Local builds use the debug key.

## Notes

Kick has no public API for VODs or playback, so Kixtra uses the same internal endpoints as
kick.com and open-source tools (yt-dlp, streamlink, Kicker, OpenKick). If Cloudflare blocks
plain requests, the app quietly retries them through a hidden WebView.

License: AGPL-3.0

# Security audit brief

A brief to hand to a reviewer (written for a Codex pass). Read-only: report findings, change nothing.

## What the app is

Android app (Kotlin, Jetpack Compose, OkHttp, Room, WorkManager, Media3). It signs in to the user's own RedGifs account through the site's page in a WebView, stores the session token, syncs likes and collections and downloads clips into a folder the user chooses. It also browses Reddit (OAuth, installed-app flow), FikFap, sex.com, EroMe and 4chan, updates itself from this repository's GitHub releases, and has an optional app lock.

- App code: `app/src/main/java/app/redgrab/` (UI in `ui/`)
- Manifest and resources: `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/`
- Build: `app/build.gradle.kts`, `app/shrink-rules.pro`
- CI: `.github/workflows/release.yml`, `.github/scripts/`

## Where to look

1. **Credentials**: `SecretStore.kt`, `Prefs.kt`, `Session*.kt`. Every place a token, cookie or `Authorization` header is read, logged or sent. Can any of them reach a host it wasn't meant for (interceptors in `RedGifsApi.clientFor`, `SourceHeaders`, redirects, `Updater.request`)?
2. **WebViews**: `LoginActivity`, `RedditLoginActivity`, `VerifyActivity`, `SessionRenewer`. JavaScript bridges, which addresses may load, how the token is captured, file and content access.
3. **Manifest**: exported components, intent filters, the `redgrab://reddit` redirect (state check), backup rules, FileProvider paths, permissions.
4. **Updater**: `Updater.kt`. What is checked before an APK reaches the installer, where it is stored, PendingIntent flags, `UpdateReceiver`.
5. **Files**: names built from site data (clip IDs, playlist piece addresses in `HlsSaver.kt`, imports), path traversal, writes outside the chosen folder.
6. **App lock**: `AppLock.kt`, `MainActivity.kt`, `ui/LockScreen.kt`. Any way content shows while locked.
7. **Logs and reports**: `AppLog.kt`, `BugReport` in `Updater.kt`, `ui/LogScreen.kt`. Can secrets or personal details leave the phone?
8. **Untrusted input**: JSON and HTML from sites, sizes, SQL in `Db.kt`.
9. **CI**: secret exposure, script injection, action pinning, job permissions, what a fork or a dispatch input can reach.
10. **Shrunk build**: release builds run R8 without renaming (`shrink-rules.pro`). Anything that relies on reflection or class names and could break or be stripped.

## Already found and fixed (13.12, 13.13)

Don't re-report these unless the fix is wrong:

- Signing key and publish rights shared a job with third-party actions → split jobs, read-only tokens, actions pinned to commits, key file removed after the build.
- Lock screen was a separate window that appeared late, and dialogs could open over it → opaque cover in the activity's own window, dialogs held back while locked.
- Activity log went to the system log in release builds; reports redacted only token-shaped strings → no system log in release; stored sign-ins removed by exact value; copied log marked sensitive.
- Updater trusted the asset address and checked only the package name → only this repository's asset addresses; version and signer checked.
- Sign-in pages showed no address → host shown; file and content access off in all WebViews.
- Site responses were read without limit → 16 MB cap on text, piece and byte caps on streams.

## Known and accepted

- Sign-in pages may navigate to other sites (some logins need it).
- The RedGifs site's own login lives in the WebView's storage, as in a browser; signing out clears it.
- The player's data source accepts local schemes inside a stream playlist (no way found to get data out).
- The WhatsApp share target is identified by package name only.

## Report format

For each finding: title; severity (critical / high / medium / low / informational); confidence (traced end to end, or plausible); file and lines; who the attacker is and what they gain; a concrete fix. Then one line for each area checked and found sound, and anything that couldn't be determined from reading.

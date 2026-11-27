# Changelog

Every version of RedGrab, newest first. The app shows the part that is new to you the first
time it opens after an update, and the whole list under Settings → About → What's new.

Each version has the sections it needs out of Added, Changed and Fixed, one line per change,
and a link to the code that made it.

## 13.12 (build 25) · 2026-11-27

### Added
- "What do you want to see?" is asked once: on first use, or the first time after this update. Change it later in Settings → Online.
- The download cloud opens the queue: every clip waiting to be saved, each with a button to take it out, plus "Clear all".
- A clip RedGifs has deleted is also taken out of your RedGifs collections (Settings → Sync), so it stops coming back.

### Changed
- A clip listed without any video address is looked up on RedGifs first; if it is deleted it goes to Removed instead of Failed.
- Locked: the app is covered from the first frame, and no dialog (crash report, update, what's new) opens over the lock.
- The activity log is no longer copied to Android's system log. A copied log or report also drops your sign-ins and Reddit name.
- Updates: only this repository's release files are fetched, and a download must be newer and signed like the installed app.
- Sign-in pages show the address of the page on show.
- The GitHub token field is treated as a password by the keyboard.
- Release builds: the signing key and the right to publish are kept apart from outside code; the key's passwords can be secrets.

### Fixed
- A deleted clip no longer shows as an unfinished download: the empty file a download attempt left behind is removed.
- Release checks: the on-device tests no longer wait behind Android's notification question, and run on Android 16.

## 13.11 (build 24) · 2026-11-27

### Added
- Search suggestions as you type, only for things that exist: tags, niches, creators and subreddits, listed under the search box.
- RedGifs' full tag and niche lists are kept on the phone (refreshed weekly), so tags and niches are suggested at once.
- Several words that are all tags ("bdsm sensual") open the clips that carry every one of them. "Text search" switches to the site's own, looser search.
- Half-typed searches are completed: "bdsm sen" offers "BDSM + Sensual".
- The filter (Online tab, top right) is also in Settings → Online → Show, and has a "No filter" switch. It now applies to every site, every search and the suggestions.
- "N hidden by your filter" under a search, with a shortcut to the filter.
- "Save all" has "Don't save these": clears the count and the queue; the clips move to Removed.
- A paused download has "Clear queue".

### Changed
- Gay and trans content is hidden unless you switch it on or choose "No filter". Clips are also judged by their tags, not only by RedGifs' label.
- A tag you tap or follow is asked for exactly. Before, it went through the text search, which also returns similar words.
- Background sync is on by default (every 12 hours, Wi-Fi unless mobile data is allowed) and keeps the list up to date even with no collection chosen for download.
- A build can carry a Reddit client ID, so Reddit works without entering one.
- The update check says so when the repository is private (GitHub then answers "not found" without a token).

### Fixed
- An unreliable unit test (stream sizes) that could fail a release build.

[Details](https://github.com/tritnaha/redgrab/commit/396b2ae)

## 13.10 (build 23) · 2026-11-27

### Added
- "All sites" in the Online tab: one feed with a few clips from each site you have set up, in turn, and one search box that asks all of them.
- Typing on Reddit (or in All sites) suggests matching subreddits.
- Settings → Sync → "Full sync" reads everything again when you want it to.

### Changed
- Syncing is quick: it fetches new likes from the top and reads a collection again only when its size on RedGifs changed. Everything is read again once a week.
- The save folder is no longer listed at every start (at most every twelve hours, and in a full sync).

[Details](https://github.com/tritnaha/redgrab/commit/fe823ae)

## 13.9 (build 22) · 2026-11-27

### Added
- Settings → Navigation: Back goes to the previous tab instead of leaving the app, and "Open on" picks what the app starts with.
- The grid follows the clip view: closing it leaves you at the clip you last saw, and that clip's tile is framed for a moment.
- Follow tags as well as niches. Followed tags are kept in the app and are part of the home feed, now called "My feed".
- Search suggestions are split into Niches and Tags.
- "Use mobile data" is one setting, in Settings and in "Save all"; turning it on asks first.
- Preview copies are made for every saved clip by themselves, and for a new clip as soon as it is saved.
- A notification when an update was found in the background but couldn't be installed yet.

### Changed
- "Start in SFW mode" applies after more than five minutes away, not every time you come back from another app.
- A finished-download notification only appears when the app isn't on screen and more than one clip was saved.
- Updates are looked for when the app opens (at most hourly) and every six hours in the background.
- The GitHub token moved to Troubleshooting and is marked optional. Updates never needed it; a token GitHub rejects no longer blocks them.

### Fixed
- Going to another tab and back lost your place in the Saved and Online grids.
- The Online tab had no way to switch SFW mode off.
- "N to save" counted clips that were deleted on their site, and they stayed in the download queue.
- The Saved grid returns to where it was when the app was last closed.

[Details](https://github.com/tritnaha/redgrab/commit/b85c9fa)

## 13.8 (build 21) · 2026-11-27

### Added
- "My niches": the Online tab opens on one feed mixed from the niches you follow.
- Browse niches: every RedGifs niche, by group or by name, with Follow and Unfollow.
- The RedGifs search box suggests matching tags and niches as you type.
- "Show" (the sliders icon on the Online tab) chooses which kinds of content RedGifs feeds show.
- Saving a RedGifs clip can add it to one of your RedGifs collections, or to a new one.

### Changed
- Every clip opens in the same-sized card, so the place to tap doesn't move from clip to clip.
- The "Online saves" filter is now "From Online": it says where a clip was found. Saved ones are under "On phone" as before.

[Details](https://github.com/tritnaha/redgrab/commit/2745362)

## 13.7 (build 20) · 2026-11-26

### Fixed
- The app closed every time it was opened after updating to 13.6. Nothing was lost; this version starts normally.

[Details](https://github.com/tritnaha/redgrab/commit/dfedd8d)

## 13.6 (build 19) · 2026-11-26

### Added
- What's new: the app lists what changed the first time it opens after an update, with a link to GitHub. The whole list is under Settings → About.
- An update that has been downloaded is offered in a dialog, with what it changes, instead of a passing message.
- Turned sideways, the grids show more clips per row and the clip view uses the height.
- Settings → Privacy → "Notify when done" switches off the finished and sync notifications.

### Changed
- FikFap downloads now show real progress: the size of a stream is asked from the server before it is fetched.
- Notifications never name a site or a clip, and they are kept off the lock screen.
- Release notes on GitHub are taken from this changelog.

### Fixed
- "Check for updates" said the repository might be private when there simply was no release yet.

[Details](https://github.com/tritnaha/redgrab/commit/f472d6e)

## 13.5 (build 18) · 2026-11-26

### Added
- App lock: unlock with the phone's fingerprint, face, PIN, pattern or password (Settings → Privacy).
- Clips deleted on their site are marked "Removed": no more retries, no failed-download notifications.
- Opening the app syncs only when the last sync is older than a chosen age.

### Changed
- Sign-in tokens are stored encrypted with a key held in the Android Keystore.
- The expanded clip is sized so there is always background to tap to close it.
- Reddit's web sign-in cookies are removed once the app has its token.

[Details](https://github.com/tritnaha/redgrab/commit/63594ef)

## 13.4 (build 17) · 2026-11-26

### Added
- Saving from Online asks which of your categories the clip goes into.
- The Save button shows the download's progress, then "Saved".

### Changed
- The Online tab opens on your RedGifs "For you" feed.

[Details](https://github.com/tritnaha/redgrab/commit/4f9fb49)

## 13.3 (build 16) · 2026-11-26

### Added
- Small preview copies: saved clips get a short, small copy for the grid, so a screen of previews costs far less battery.

[Details](https://github.com/tritnaha/redgrab/commit/37f84a5)

## 13.2 (build 15) · 2026-11-25

### Changed
- The page indicator is smaller and can be dragged anywhere on the grid.
- Previews use all of the phone's decoding capacity.

### Fixed
- The fourth row of the default grid didn't play its previews.

[Details](https://github.com/tritnaha/redgrab/commit/ab90755)

## 13.1 (build 14) · 2026-11-25

### Added
- Updates from the releases on GitHub, checked when the app opens and in the background.
- About shows the build, commit, date and phone, with a link to GitHub.
- "Report a problem" gathers the details and the Activity log, with sign-in tokens removed.

[Details](https://github.com/tritnaha/redgrab/commit/c4d2c0f)

## 13.0 (build 13) · 2026-11-25

### Added
- Five more sources on the Online tab: Reddit, FikFap, sex.com GIFs, EroMe and 4chan /gif/.
- The RedGifs session is renewed in the background.
- Page indicator on the grids, with "back to top" and "go to page".
- A clip opens as a larger view over the grid; tap beside it to go back.

### Changed
- Every preview on screen plays, as far as the phone can decode at once.

### Fixed
- The RedGifs sign-in page showed nothing.
- Liked clips weren't listed.
- RedGifs niche feeds failed to load.

[Details](https://github.com/tritnaha/redgrab/compare/77e0efb...9be6d2b)

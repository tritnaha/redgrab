# Changelog

Every version of RedGrab, newest first. The app shows the part that is new to you the first
time it opens after an update, and the whole list under Settings → About → What's new.

Each version has the sections it needs out of Added, Changed and Fixed, one line per change,
and a link to the code that made it.

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

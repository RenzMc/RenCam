# RenCam — Round 6 (Live Photo polish)

Goal (from user): make the **flash-OFF** Live Photo as perfect as the flash mode, fix the
**front camera** Live Photo, localise the **queue toast**, change the **email**, add **GitHub +
YouTube** links to About, replace the **rencam.org.uk** URL with the GitHub link, then build &
push.

## Root cause found
- `8d34d3f` changed `LivePhotoManager.stopFlashBurst()` so it **always** calls
  `controller.setFlashValue(...)` when a controller exists. Before that it only did so when a burst
  actually fired (`flash_value_before_capture != null`). So the **flash-OFF** path now pushed a new
  repeating request into the live video session right before the buffer was stopped — the concrete
  difference that made the flash-off Live Photo unreliable (plain JPEGs). Fix: only touch the flash
  when a burst really fired.

## Tasks
- [x] 1. LivePhotoManager: guard `stopFlashBurst()` so the flash-OFF path never touches the camera
- [x] 2. LivePhotoManager: robust cover extraction (retry nearby timestamps if a frame fails to decode)
- [x] 3. LivePhotoManager: make the pending-capture queue drain even if the buffer is slow to restart
- [x] 4. Front camera: same reliability fixes apply; front-screen flash detected correctly (exclude `flash_frontscreen*` from LED detection)
- [x] 5. Localise the queue toast + add all 7 missing Live Photo strings to `values-in/strings.xml`
- [x] 6. Replace `renzmc.dev@gmail.com` with `renzaja11@gmail.com` (strings.xml + `_docs/*.html` + `rencam_source.txt`)
- [x] 7. About dialog: add GitHub + YouTube (Renz-Mc) clickable links
- [x] 8. Replace `https://rencam.org.uk/` with the GitHub link (MainActivity.getOnlineHelpUrl)
- [x] 9. Build (`./gradlew assembleDebug` + `assembleRelease`), commit, push to GitHub
- [x] 10. Monitor GitHub Actions build, download & attach APKs

## Notes
- Remote already has the token embedded: `git push origin main` works directly.
- Do NOT reset `flash_fired` inside `stopFlashBurst()` — `onPostRollElapsed()` reads it afterwards
  to decide flash-synced cover extraction.
- YouTube handle confirmed from the GitHub profile: `https://www.youtube.com/@Renz-Mc`.
- Local verification: debug + release APKs both build cleanly (JDK 17, compileSdk 33, build-tools 33.0.2).

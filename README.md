# ScrollGuard

An anti-doomscroll app for Android that guards Instagram at four moments:

1. **Before** — an intention gate when you open Instagram: a forced pause, "do you really want this?", and an optional "what are you here for?" picker.
2. **During** — the feed is dimmed (less addictive colors) and a faint bottom strip shows how long you've scrolled today. Dim turns off automatically while you're posting (camera/editor/story) so you create in true color.
3. **Recurring** — after N minutes of feed time, a check-in: "What did you open Instagram for? You came to *reply to someone* — done? Are you tired? Do you need rest?"
4. **Exit** — "I want to rest" → auto-open your resting playlist and leave, block social for 5 minutes (configurable), or both. The block screen shows a countdown plus suggestions: stretch, step outside, drink water, write down what's bugging you…

Fully **offline** — the app has no internet permission. It reads Instagram's screen structure only to know which screen you're on; nothing leaves the phone.

**This is M1**: everything above, *without* the gesture-direction remap (that's M2 — see `docs/` plan).

---

## Project layout

```
app/src/main/java/com/dmd/scrollguard/
├── MainActivity.kt                    # Compose settings screen + permission shortcuts
├── data/SessionStore.kt               # all settings + daily timer + block window (DataStore)
├── detect/InstagramClassifier.kt      # feed / posting / safe heuristics  ← the tuning hotspot
├── overlay/OverlayController.kt       # dim, strip, gate, check-in, rest menu, block screen
└── service/
    ├── FeedAccessibilityService.kt    # the brain: events → context → actions
    └── GuardForegroundService.kt      # keep-alive notification + boot receiver
```

## Build & install

Requirements: Android Studio (latest), a phone with Android 8+ and USB debugging on.

1. Open this folder in Android Studio → let Gradle sync (it may offer to generate the Gradle wrapper — accept).
2. Plug in your phone → Run ▶ (or `./gradlew :app:assembleDebug` and `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
3. On the phone, open ScrollGuard and complete **Setup**:
   - Allow **Display over other apps**.
   - Enable **ScrollGuard** under **Accessibility**.
4. Set your **resting playlist link** (music app → share → copy link → paste in settings).
5. Open Instagram. The gate should appear.

> First build may need small fixes (dependency versions, lint). The structure and logic are complete; treat compile errors as finishing work, not design problems.

## Tuning Instagram detection (you WILL need this)

`InstagramClassifier` decides feed vs. posting vs. safe using resource-id keywords and structure. Instagram updates change ids. To tune:

1. In `FeedAccessibilityService`, set `DEBUG_DUMP = true`.
2. `adb logcat -s ScrollGuard` while navigating Instagram.
3. Note which ids appear on the feed, in Reels, in DMs, in the camera/editor.
4. Add/adjust keywords in the `*_HINTS` lists. Rebuild.

Priority is POSTING > SAFE > FEED, so when in doubt the app stays out of the way.

## Known M1 caveats

- **Music auto-play**: launching a playlist link opens it; whether playback *starts* depends on the music app's deep-link behavior. Spotify links usually open ready to play; experiment with your app.
- **Block scope**: M1 blocks Instagram only. Extending to other social apps = add their packages to `accessibility_service_config.xml` and the service's package check (M3).
- **OEM battery killers** (Xiaomi/Huawei/Oppo…): exempt ScrollGuard from battery optimization or the service may be killed.
- The notification is required by Android for the keep-alive service; it's set to minimum importance.

## Roadmap

- **M2 — gesture remap**: transparent capture overlay + accessibility scroll; the four-direction arrow in the strip. The strip already reserves the right side for it.
- **M3 — multi-app**: TikTok, YouTube Shorts, X…
- **M4 — hardening**: OEM quirks, edge-gesture insets, FLAG_SECURE handling.

## Privacy

No internet permission. No analytics. No accounts. The accessibility service reads screen structure (view ids) only to classify which Instagram screen is open. Time data lives in local DataStore on your phone.

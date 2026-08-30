# SafeScreen AI — Live Demo Runbook

Goal: a 60–90s on-device demo that hits the **5 "why-local" vectors** (privacy · real-time · latency ·
offline · energy) while showing content blurred over a *real* app + on-device numbers. **NSFW is the
primary, every-frame pillar; AI-gen detection is the secondary, triggered signal.** Rehearse twice;
**always have the backup video**. Device: **Galaxy S25 Ultra (Snapdragon 8 Elite)**.

## Pre-flight (do before you present)
- Phone: Developer options on; **USB debugging authorized** ("Always allow from this computer"); screen
  unlocked; auto-rotate off; brightness up; **Do Not Disturb on**.
- Home screen shows the **"🔒 Private by construction"** card — that's your opening visual.
- Free space ≥ ~3 GB; battery ≥ 50%; **unplug** before any energy benchmark (USB charging skews it).
- Install: `./gradlew :app:installDebug` (or `adb install -r app-debug.apk`).
- Stage demo faces in Gallery: `adb push /tmp/ssfaces/*.jpg /sdcard/Pictures/SafeScreenDemo/` then a
  media scan. (Internal key: `01/03/05/07` real, `02/04/06/08` AI-generated.)
- Grant permissions once up front: open app → Start protection → **Display over other apps = Allow** →
  screen-capture **Start now**. Confirm the persistent "SafeScreen is protecting your screen" notification.
- **Record a backup video of the full flow now**, in case live fails.

## The demo (script) — each beat names the "why-local" vector it proves
1. **Privacy hook (15s) [privacy]:** on the home screen, point to **"🔒 Private by construction"**.
   "This checks your screen for explicit content and deepfakes — the most private data there is — so it runs
   100% on-device. No INTERNET permission; it physically cannot upload anything."
2. **Live blur over a real app (30s) [real-time + latency]:** Start protection → **Entire screen** → open
   Gallery → SafeScreenDemo → scroll. On an AI-generated face the screen frosts: **"⚠️ Protected by
   SafeScreen · NSFW x% · AI-gen y%"** with the badge **"🔒 analyzed on-device · 0 bytes left your phone."**
   Tap to reveal; scroll to a real face → passes. "It scans the screen itself — any app — and blurs *before*
   you engage."
3. **NSFW = the primary pillar (15s) [use-case]:** tap **"Scan a photo (private)"** → pick a safe
   skin/swimwear proxy → the verdict card shows the NSFW %. "NSFW runs every frame — this is the per-frame
   primary; deepfake is the triggered secondary. (Real explicit content was validated privately, never shown
   live.)"
4. **Numbers (20s) [tech/latency/energy]:** Run benchmark → latency / fps / energy + backend. "~25 ms/frame
   on the 8 Elite CPU; ported to the **Hexagon NPU via ExecuTorch's QNN backend** — targeting ~10× and lower
   energy." (Show the NPU latency from the box profile if ready.)
5. **Privacy + offline proof (15s) [privacy + offline]:** turn on **airplane mode**, repeat step 2 — still
   blurs. Optionally show the manifest (no INTERNET). "Offline, private, real-time — exactly what a cloud
   filter can't give you here."

## Fallbacks (when live misbehaves)
- **Overlay/capture flakes** → use the in-app **"Open test feed"** (same detectors blur the bundled deepfake
  faces with scores) or **"Scan a photo (private)"** (full-res, clean verdict card). Reliable, no permissions.
- **NPU not ready** → show CPU/XNNPACK numbers; "NPU port in progress, same pipeline."
- **Anything hangs** → cut to the **backup video**. Never debug live.
- **Energy looks wrong** → you're probably plugged in; the card warns you. Latency/fps are still valid.

## Reset between runs
- Stop protection (or swipe the notification) to drop the overlay; relaunch app for a clean state.
- If the overlay sticks: `adb shell am force-stop ai.safescreen`.

## What NOT to do
- Don't show real explicit imagery — the NSFW path is demoed via the test feed/metrics, never live nudes.
- Don't promise region-precise blur or text moderation (roadmap, not built).

# SafeScreen AI — Dev Setup & Run

Status: **P0 scaffold builds from the command line and runs with a heuristic detector (no models
required).** Drop in `.pte` models for real on-device inference. The S25/S22 device is only needed
for the on-device run + latency numbers — everything else builds on this Mac.

## What's already on this machine
- Android SDK at `~/Library/Android/sdk` (platforms 33–35, build-tools 35) — `adb` at `~/Library/Android/sdk/platform-tools/adb`
- Java 21 (Microsoft OpenJDK)
- Homebrew (used once to install Gradle, which bootstraps the project's `./gradlew` wrapper)

## Build & run (CLI — no Android Studio needed)

```bash
cd /Users/krishna/Desktop/apex-7/safescreen-ai

# one-time: create the Gradle wrapper (Gradle installed via `brew install gradle`)
gradle wrapper --gradle-version 8.9

# build the debug APK
./gradlew :app:assembleDebug

# with the phone connected (USB debugging on):
~/Library/Android/sdk/platform-tools/adb devices       # confirm it shows up
./gradlew :app:installDebug                              # install
~/Library/Android/sdk/platform-tools/adb shell am start -n ai.safescreen/.MainActivity
```

First build downloads AGP/Kotlin/Compose/ExecuTorch artifacts — give it a few minutes.

Out of the box the app shows a procedurally-generated demo feed and runs the **heuristic** detector
(HUD reads `backend: HEURISTIC`). Skin-toned tiles blur, landscapes pass, face tiles may get a
manipulation badge — enough to demo the full UX before models exist.

## Add real models (the "real on-device inference" milestone)

Export must use **ExecuTorch 0.6.0** to match the Android runtime AAR (`org.pytorch:executorch-android:0.6.0`);
a newer ExecuTorch writes a `.pte` the 0.6.0 runtime can't open.

```bash
python3 -m venv tools/.venv && source tools/.venv/bin/activate
pip install -r tools/requirements.txt

# NSFW (Marqo ViT-tiny) and deepfake (dima806 ViT) -> bundled assets
python tools/export_xnnpack.py --arch nsfw_marqo \
    --out app/src/main/assets/models/nsfw.pte
python tools/export_xnnpack.py --arch deepfake_dima806 \
    --out app/src/main/assets/models/deepfake.pte
```

Rebuild + install. The app auto-detects the `.pte` files and the HUD flips to `backend: CPU/XNNPACK`.
The export script prints a `max abs diff` sanity check (eager vs `.pte`) — it should be ~0.

## NPU port (P1 — Linux x86 only)

`tools/export_qnn.py` targets the Qualcomm QNN/Hexagon backend. The QNN SDK host tools are
**Linux-x86 only — they do not run on this Apple-Silicon Mac.** Use a cloud Linux x86 box for ~1h:
install the Qualcomm AI Engine Direct SDK, export with `--soc SM8450` (S22U) / `SM8750` (S25U), and
bundle the QNN `.so` runtime libs into `app/src/main/jniLibs/arm64-v8a/`. CPU/XNNPACK remains the
permanent fallback, so this is pure upside.

## Key build-time decisions
- **ExecuTorch pinned to 0.6.0** (Android AAR ↔ Python exporter) — only version with a published
  Maven AAR; avoids `.pte` schema skew.
- **App degrades gracefully**: missing/incompatible `.pte` or native libs → heuristic detector, app
  still runs. No hard dependency on the model pipeline for a working demo.
- **Gemma**: NOT on the critical path. An on-device Gemma 3 1B "Safety Advisor" explainer is a P3
  "halo" stretch behind the `Explainer` interface (default = instant `TemplateExplainer`). Per-frame
  LLM use is infeasible on an 8 Gen 1 (~100–300 ms/token).
- **QNN export is Linux-only** on this Mac → cloud Linux x86 for the Day-2 NPU numbers.

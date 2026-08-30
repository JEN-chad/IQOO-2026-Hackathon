# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**SafeScreen AI** — an on-device Android visual safety layer for the **Qualcomm × Meta ExecuTorch Hackathon**. It analyzes on-screen images in real time and intervenes (warn → blur → block) on explicit/abusive imagery and likely deepfakes, running entirely on the Snapdragon NPU via **ExecuTorch**. No frames leave the device.

**This repo is pre-implementation.** As of now it contains only the spec and plan — no code, no build system yet. The two source-of-truth documents are:

- `docs/PRD-SafeScreen-AI.md` — product requirements, architecture, locked decisions, risks.
- `docs/IMPLEMENTATION-PLAN.md` — the 3-day solo build, day-by-day, with cut-lines.

**Read both before writing any code.** They override assumptions you'd otherwise make from training data.

## Hard constraints (do not violate)

- **Solo dev, ~72h.** Scope is the binding constraint. Do not add features beyond the current priority tier.
- **On-device only.** No network in the inference path. No cloud fallback, accounts, or telemetry off-device. "Private by construction" is a core claim — demo runs in airplane mode.
- **Never display real explicit or abusive imagery.** Demo/eval use benign proxies (swimwear/art near threshold) and **public** deepfake datasets (faces only). This is non-negotiable. See PRD §8.
- **Deepfake output is a confidence signal, never a verdict.** Surface "possibly manipulated (NN%)", never "this is fake". On-device deepfake detection does not generalize.

## Build priority: the P0 → P1 → P2 ladder

Every checkpoint must end in a *demoable* state. Build in this order; never start a higher tier before the lower one demos.

- **P0 (the floor — already wins):** in-app Compose feed + real-time NSFW detection + severity-tiered blur/block + on-screen latency HUD, on **CPU/XNNPACK**.
- **P1 (the headline):** port the NSFW gate to **QNN/Hexagon NPU** (the perf numbers) + deepfake confidence signal + eval metrics + temporal smoothing.
- **P2 (the wow, expendable):** cross-app protection via **MediaProjection** screen capture + `SYSTEM_ALERT_WINDOW` overlay. Hard-timeboxed; drop without regret if it flakes.

**Cut-line when time runs out** (drop top-first): P2 overlay → deepfake on NPU → deepfake entirely → NPU port (fall back to CPU). **Never cut below P0.**

## Core architecture (see PRD §5)

Single shared pipeline, two interchangeable capture surfaces:

```
FrameSource -> Preprocessor -> Tier-1 NSFW gate (per-frame, NPU)
                                   -> CandidateRouter (scene-change / scroll-stop)
                                        -> Tier-2 deepfake verifier (triggered only)
            -> PolicyEngine (thresholds + temporal smoothing) -> InterventionRenderer
            -> TelemetryHud (per-tier ms, fps, backend)
```

- **2-tier cascade is the central design insight.** Cheap NSFW classifier runs *every* frame; the expensive, less-reliable deepfake model runs *only* on candidate frames. Do not run both per-frame — it's slow and misleading.
- Components are swappable behind interfaces: `FrameSource` (`InAppFeedSource` | `ScreenCaptureSource`), `Detector` (`NsfwDetector` | `DeepfakeDetector`), `InterventionRenderer` (`ComposeOverlayRenderer` | `WindowOverlayRenderer`), `ModelRuntime` (XNNPACK ↔ QNN). Keep them isolated so a model that won't export can be swapped without touching the app.
- **Backend sequencing:** XNNPACK/CPU first (guarantees a working loop), then port the hot path to QNN/HTP. CPU is the permanent fallback.

## Stack & commands

Kotlin + Jetpack Compose app (`app/`, package `ai.safescreen`), ExecuTorch Android runtime, Python export tooling (`tools/`). Full setup in `docs/SETUP-DEV.md`.

```bash
# Build (CLI; no Android Studio needed — SDK + Java 21 + brew gradle are enough)
gradle wrapper --gradle-version 8.9        # one-time, creates ./gradlew
./gradlew :app:assembleDebug               # build APK
./gradlew :app:installDebug                # install on connected device
~/Library/Android/sdk/platform-tools/adb devices   # adb is NOT on PATH; use full path

# Export models (use a venv pinned to executorch 0.6.0 — see tools/requirements.txt)
python tools/export_xnnpack.py --arch nsfw_marqo --out app/src/main/assets/models/nsfw.pte
python tools/eval.py --arch nsfw_marqo --data ./data --positive nsfw   # offline P/R + latency
```

**Eval = the test.** Correctness is validated by `tools/eval.py` (P/R on a holdout + latency + `.pte` size) plus on-device smoke checks — not full TDD (72h spike).

### Critical version + platform facts (don't relitigate)
- **ExecuTorch is pinned to 0.6.0** on BOTH sides (Android AAR `org.pytorch:executorch-android:0.6.0` ↔ Python exporter). It's the only version with a published Maven AAR; mixing versions = `.pte` schema load failure. The base conda env has 1.3.1 — export in the pinned venv, not there.
- **The app runs without models**: `DetectorFactory` falls back to a heuristic detector (skin-ratio NSFW, hash-based deepfake) when no `.pte`/native libs are present. HUD shows `backend: HEURISTIC` vs `CPU/XNNPACK`.
- **QNN/HTP export is Linux-x86 only** — it will NOT run on this Apple-Silicon Mac. Do the NPU port (P1) on a cloud Linux x86 box. CPU/XNNPACK is the permanent fallback.
- **Gemma is off the critical path** — per-frame LLM is infeasible on 8 Gen 1. An on-device Gemma 3 1B "Safety Advisor" is a P3 stretch behind the `Explainer` interface (default `TemplateExplainer`).

## Dev device

Develop and tune against the **Samsung Galaxy S22 Ultra (Snapdragon 8 Gen 1)** — the "canary". Target device is the S25 Ultra (8 Elite). Tune perf to the weaker device; the stronger one over-delivers.

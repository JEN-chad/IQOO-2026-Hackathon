# SafeScreen AI — Adaptive On-Device Privacy Shield

> **iQOO Hackathon 2026 Selection MVP**  
> Real-time, on-device visual safety and privacy protection for Android powered by **ExecuTorch** and edge AI.

---

## 1. Overview
SafeScreen AI is an **on-device visual privacy shield** that protects Android users from unintended exposure to sensitive, explicit, or graphic imagery. Running locally in the background via Android `MediaProjection`, SafeScreen analyzes on-screen visual content in real time and automatically applies an adaptive blur overlay before the user engages with it.

*   **100% On-Device:** Zero cloud dependencies, zero telemetry, no network calls.
*   **Privacy by Construction:** Does **not** declare the `INTERNET` permission in `AndroidManifest.xml` — the app cannot transmit frames off the device.
*   **Edge AI Runtime:** Powered by **ExecuTorch 0.6.0** with XNNPACK acceleration and support for Snapdragon Hexagon NPU delegation.

---

## 2. The Problem
Visual privacy risks and explicit content can appear unexpectedly across social feeds, messaging apps, and web browsers. Sending raw screen captures to a remote cloud for content moderation is a severe privacy violation and introduces unacceptable latency. SafeScreen solves this by bringing visual threat detection directly to the silicon edge.

---

## 3. The Solution
SafeScreen operates as an unobtrusive system service:
1.  **Continuous In-Memory Capture:** Takes downscaled screen frames in memory (no disk caching).
2.  **Edge AI Inference:** Evaluates multi-crop regions via an on-device **Vision Transformer (ViT-tiny@384)** model using **ExecuTorch**.
3.  **Adaptive Policy Engine:** Enforces user-selected protection levels (`SAFE`, `PRIVATE`, `MAXIMUM`).
4.  **Hardware-Accelerated Overlay:** Renders a secure, non-touchable blur shield over sensitive content while keeping the user in control with a **Safe Reveal** action.

---

## 4. Key MVP Features

### 🛡️ Feature 1: Protection Levels
Users can customize protection sensitivity with three distinct presets:
*   **`SAFE` (Standard Protection):** Intervenes on high-confidence graphic/explicit content (Blur $\ge 0.50$, Block $\ge 0.85$). Minimizes false positives during general use.
*   **`PRIVATE` (Balanced Shield - Default):** Intervenes on medium and high-risk sensitive content (Blur $\ge 0.30$, Block $\ge 0.70$). Recommended for commuting and shared environments.
*   **`MAXIMUM` (Strict Privacy):** Aggressively shields borderline visual content (Blur $\ge 0.18$, Block $\ge 0.45$). Designed for crowded public spaces.

### 🔒 Feature 2: Truthful Privacy Indicator
*   Clear on-screen "LOCAL AI • NO CLOUD" security status.
*   Live telemetry displaying the active runtime backend (`CPU/XNNPACK (Marqo ViT)`), inference latency, and throughput.

### 👁️ Feature 3: Safe Reveal
*   Users can tap **"TAP TO REVEAL"** on the protection overlay to temporarily view shielded content.
*   Includes a **3-second cooldown** and 64-bit average hash (`aHash`) tracking to prevent flickering or unwanted re-blurring while navigating away.

### 🧪 Feature 4: Controlled Deterministic Demo Feed
*   An in-app test feed featuring safe landscapes, portrait photos, and sensitive test proxies to verify protection levels offline and deterministically without requiring live internet feeds.

### ⚡ Feature 5: Robust Error Handling & Graceful Degradation
*   Handles overlay permission prompts, screen capture consent cancellations, and orientation changes.
*   If native model inference encounters any exception, the engine automatically degrades to `HeuristicNsfwDetector` without crashing.

---

## 5. Architecture

```mermaid
graph TD
    A[Screen Display] -->|MediaProjection| B[ScreenCaptureService]
    B -->|In-Memory Frame| C[SafeScreenEngine]
    C -->|Square Crops / Preprocess| D[Preprocessor (384x384 NCHW)]
    D -->|Float32 Tensor| E[ExecuTorch Runtime (0.6.0)]
    E -->|XNNPACK CPU / QNN NPU| F[nsfw_marqo.pte (ViT-tiny)]
    F -->|Logits [NSFW, SFW]| G[NsfwDetector / Softmax]
    G -->|NSFW Score| H[TemporalSmoother (window=3)]
    H -->|Smoothed Score| I[PolicyEngine (SAFE / PRIVATE / MAXIMUM)]
    I -->|Action: SHOW / BLUR / BLOCK| J[OverlayManager]
    J -->|RenderEffect Blur + Reveal Panel| K[User Screen Shield]
```

---

## 6. AI & Runtime Details
*   **Model:** `Marqo/nsfw-image-detection-384` (Vision Transformer ViT-tiny).
*   **Model Binary:** `app/src/main/assets/models/nsfw_marqo.pte` (22.61 MB, serialized FlatBuffers ExecuTorch program).
*   **Input Specification:** Shape `[1, 3, 384, 384]`, Planar NCHW, `Float32`, Normalized with ImageNet mean `[0.485, 0.456, 0.406]` and std `[0.229, 0.224, 0.225]`.
*   **Output Specification:** 2 float logits `[logit_NSFW, logit_SFW]`. Positive index `0` extracted via Softmax.
*   **Concurrency:** Single-threaded coroutine dispatcher (`safescreen-infer`) guarding thread-confined ExecuTorch module instances.

---

## 7. Build & Installation

### Prerequisites
*   Android SDK 35 (Platform 35 + Build-Tools 34/35)
*   Java JDK 17+ (Java 21 verified)
*   Gradle 8.9 (via `./gradlew` wrapper)

### Build Debug APK
```bash
# Build the application
./gradlew :app:assembleDebug

# Run unit tests
./gradlew testDebugUnitTest
```
Generated APK: `app/build/outputs/apk/debug/app-debug.apk`

### Install on Device
```bash
adb devices
./gradlew :app:installDebug
adb shell am start -n ai.safescreen/.MainActivity
```

---

## 8. Verification & Test Matrix

| Test Case | Scope | Status |
|---|---|---|
| Project Compilation | Gradle 8.9 + AGP 8.7.2 + Kotlin 2.0.21 | **PASS** |
| Debug APK Generation | `app-debug.apk` (~121 MB with native libs) | **PASS** |
| Unit Tests | `PolicyEngineTest` (Thresholds, Presets, Smoothing) | **PASS** |
| Model Asset Presence | `nsfw_marqo.pte` (22.61 MB) in assets | **PASS** |
| ExecuTorch Loader Wiring | `ExecuTorchRuntime.tryLoad()` + `Module.load()` | **PASS** |
| Preprocessing Contract | 384x384 RGB NCHW ImageNet normalization | **PASS** |
| Protection Levels | `SAFE`, `PRIVATE`, `MAXIMUM` switching | **PASS** |
| Safe Reveal Implementation | Cooldown timer + center-square `aHash` tracking | **PASS** |
| Controlled Test Feed | Deterministic in-app offline feed | **PASS** |
| Privacy Constraint | No `android.permission.INTERNET` in manifest | **PASS** |
| Physical iQOO Device Run | Snapdragon NPU execution & latency profiling | **REQUIRES iQOO DEVICE TEST** |

---

## 9. Limitations & Future Roadmap
*   **Current Model:** Image-level classifier with multi-crop tile evaluation. Object-level bounding box localization is deferred to a future dedicated detection/segmentation head.
*   **Future Scope (Phase 4):**
    1.  **Shoulder-Surfing Detection:** Utilizing the front-facing camera with local face-angle inference to automatically raise privacy protection when onlookers are detected.
    2.  **Office Kit Integration:** Secure phone $\leftrightarrow$ PC privacy workflows for meeting mirroring and presentation mode shielding.
    3.  **QNN/HTP Full Delegation:** Compilation of INT8 quantized weights for Qualcomm Hexagon NPU V79 sub-millisecond execution.

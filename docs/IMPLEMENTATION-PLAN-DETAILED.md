# SafeScreen AI — Detailed Implementation Plan (3-Day Solo Build)

**Companion to:** `PRD-SafeScreen-AI.md` and `IMPLEMENTATION-PLAN.md` (high-level).
This is the **engineering-grade, executable** version, grounded in current ExecuTorch / QNN / Android APIs.

## Context

SafeScreen AI is an on-device Android visual safety layer for the Qualcomm × Meta ExecuTorch hackathon. It classifies on-screen images in real time and intervenes (warn → blur → block) on explicit/NSFW imagery and likely deepfakes, running on the Snapdragon NPU via ExecuTorch — no frames leave the device.

This plan turns the validated **P0 → P1 → P2 ladder** into concrete modules, model choices, export commands, and an hour-level schedule. Build constraints: **1 dev, ~72h, dev device = Galaxy S22 Ultra (Snapdragon 8 Gen 1, SoC `SM8450`)**; target = S25 Ultra (8 Elite, `SM8750`). Tune to the weaker device.

---

## Tech stack & pinned versions

> Versions below are starting points from research — **verify exact latest stable on Day 0** (`pip index versions executorch`, Maven Central) and pin whatever installs cleanly. Do not chase versions mid-build.

- **App:** Kotlin + Jetpack Compose, single-activity. minSdk 31 (Android 12 — required for `Modifier.blur`; S22U ships 12+), targetSdk 35 (Android 15).
- **Runtime:** `org.pytorch:executorch-android` (+ `com.facebook.soloader:soloader`, `com.facebook.fbjni:fbjni`). Models in `app/src/main/assets/models/*.pte`.
- **Export (laptop, Ubuntu 22.04/WSL2):** `pip install executorch torch torchvision timm transformers`. XNNPACK ships in the wheel.
- **QNN (P1):** Qualcomm AI Engine Direct (QNN) SDK + NDK r26c/r28c + GCC 13. Requires bundling QNN `.so` libs (`libQnnHtp.so`, `libQnnSystem.so`, HTP stub, `libqnn_executorch_backend.so`) in the APK `jniLibs`.
- **Eval:** Python (`tools/eval.py`), small labeled holdout.

---

## Repository / project structure

```
safescreen-ai/
  app/                                  # Android app module
    src/main/
      AndroidManifest.xml
      assets/
        models/{nsfw.pte, deepfake.pte}
        demo/{benign/, nsfw_proxy/, deepfake_samples/}
      java/ai/safescreen/
        MainActivity.kt
        feed/InAppFeedScreen.kt          # Surface A: Compose feed
        capture/ScreenCaptureService.kt  # Surface B: MediaProjection FGS (P2)
        capture/OverlayService.kt        # Surface B: overlay window (P2)
        pipeline/FrameSource.kt          # interface + InAppFeedSource / ScreenCaptureSource
        pipeline/Preprocessor.kt         # Bitmap -> normalized float tensor
        pipeline/ModelRuntime.kt         # ExecuTorch Module wrapper, backend-selectable
        pipeline/Detector.kt             # interface + NsfwDetector / DeepfakeDetector
        pipeline/CandidateRouter.kt      # when Tier-2 runs
        policy/PolicyEngine.kt           # scores -> Decision + temporal smoothing
        render/InterventionRenderer.kt   # interface
        render/ComposeOverlayRenderer.kt # Surface A intervention UI
        render/WindowOverlayRenderer.kt  # Surface B overlay (P2)
        ui/TelemetryHud.kt               # ms/fps/backend overlay
        ui/SettingsPanel.kt              # live threshold sliders
  tools/
    export_xnnpack.py                    # PyTorch -> .pte (CPU)
    export_qnn.py                        # PyTorch -> quantized .pte (HTP)
    eval.py                              # accuracy/P/R + latency + size
  docs/                                  # PRD + plans
```

**Module boundaries** (each independently testable): `Preprocessor` (Bitmap→Tensor), `ModelRuntime` (load `.pte`, run, time), `Detector` (score), `PolicyEngine` (score→Decision), `InterventionRenderer` (Decision→UI). Models are swappable behind `Detector`; backends behind `ModelRuntime`.

---

## Model selection (final picks + rationale)

Picked for **export-ability first**, accuracy second. All Apache-2.0 unless noted.

| Role | Primary | Why | NPU/HTP fallback |
|------|---------|-----|------------------|
| **NSFW (Tier-1, per-frame)** | `Marqo/nsfw-image-detection-384` (ViT-tiny, 5.6M params, 384px, ~11–22MB f32) | Tiny + accurate (~98%), clean PyTorch weights | **ViTs can partially fall back on HTP.** If QNN delegation/quantization of the ViT is poor, swap to a conv-net NSFW (ResNet/EfficientNet, e.g. `emiliantolo/pytorch_nsfw_model`) — pure-conv delegates to HTP far more reliably. Decide by the Day-2 delegation smoke test. Also: drop input to 224px if 384px misses the fps target. |
| **Deepfake (Tier-2, triggered)** | EfficientNet-B0 image deepfake detector (FaceForensics++/DFDC, ~5–10MB) | Small, conv-net, exports + delegates cleanly; runs fine on CPU as a triggered check | Backup: `dima806/deepfake_vs_real_image_detection` (ViT-base, 224px) — accurate but ~3yr drift; raise threshold. |

**Rejected:** `prithivMLmods` SigLIP deepfake (92.9M, 512px, ~370MB) — too heavy for per-candidate on-device in 72h, and SigLIP-on-HTP is risky. Keep as an offline accuracy reference only. **Deepfake output is always a confidence signal ("possibly manipulated NN%"), never a verdict.**

**Preprocessing (replicate in Kotlin):**
- ViT NSFW / ViT deepfake: resize to model size (384 / 224), RGB, normalize `mean=[0.485,0.456,0.406] std=[0.229,0.224,0.225]`, NCHW `[1,3,H,W]`.
- EfficientNet deepfake: confirm its training transform (typically same ImageNet mean/std, 224px).
- Use `Bitmap.getPixels(int[])` (one call) → fill a reused `FloatArray`, **not** per-pixel `getPixel()`; do it off the main thread.

---

## Export pipeline (the Day-0 risk)

**XNNPACK / CPU (`tools/export_xnnpack.py`):**
```python
import torch
from torch.export import export
from executorch.exir import to_edge_transform_and_lower
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner

m = load_pytorch_model().eval()                       # from HF/timm
ex = export(m, (torch.randn(1,3,H,W),))
edge = to_edge_transform_and_lower(ex, partitioner=[XnnpackPartitioner()])
edge.to_executorch().write_to_file(open("nsfw.pte","wb"))
```
Then validate numerics: same input → PyTorch logits ≈ on-device logits.

**QNN / HTP (`tools/export_qnn.py`, P1):** PT2E post-training quantization (8a8w default) → `generate_qnn_executorch_compiler_spec(soc_model="SM8450")` (use `SM8750` for the S25) → lower with the QNN partitioner → `to_executorch()`. **Quantization is effectively required for the HTP path** and is the #1 accuracy risk — validate on-device output after quantizing; if the ViT degrades, fall back to the conv-net NSFW model. Bundle the QNN `.so` libs in `jniLibs`.

---

## Runtime wiring (key APIs)

- **Load/infer (ModelRuntime):**
  ```kotlin
  val module = Module.load(assetFilePath("models/nsfw.pte"))
  val input = Tensor.fromBlob(floatArray, longArrayOf(1,3,H,W))
  val out = module.forward(EValue.from(input))[0].toTensor().dataAsFloatArray
  // wrap with System.nanoTime() around forward() for the HUD
  ```
- **Surface A frame source:** we own the demo bitmaps, so classify the **decoded source bitmap directly** when an item scrolls into view / on scroll-settle (no `graphicsLayer` capture needed). Use `LazyColumn` + `rememberLazyListState()`; trigger classification via `derivedStateOf` on visible items.
- **Intervention (Surface A):** `Modifier.blur(radius, BlurredEdgeTreatment(RoundedCornerShape(..)))` for Medium (+ tap-to-reveal `clickable` overlay); replace with a safety card `Box` for High; corner badge for Low (manipulation).
- **Surface B (P2):** `MediaProjectionManager.createScreenCaptureIntent()` → FGS with `foregroundServiceType="mediaProjection"` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` → `ImageReader` (`RGBA_8888`) + `createVirtualDisplay` → `acquireLatestImage()` frames. Overlay via `TYPE_APPLICATION_OVERLAY` (after `Settings.canDrawOverlays`). **Cannot blur the underlying app's pixels** — use **capture-blur-redraw**: blur the captured frame region and paint that blurred bitmap in the overlay over the region (looks like a real blur), or fall back to an opaque safety card.
- **Throttling:** process latest frame at 10–15 fps (frame-time gate); always `image.close()`; inference + preprocess off main thread (`Dispatchers.Default`). Temporal smoothing in `PolicyEngine` (rolling decision over N frames) to stop strobing.

---

## Day-by-day execution (hour-level, mapped to P0/P1/P2)

### Day 0 — De-risk the toolchain (½ day; do FIRST)
1. Android Studio project (Kotlin/Compose), min/target SDK, run hello-world on S22U over ADB.
2. Add ExecuTorch AAR; load + run a trivial `.pte` on **CPU** on-device → proves runtime.
3. `export_xnnpack.py`: export **Marqo NSFW** → `nsfw.pte`; validate PyTorch≈on-device logits.
4. (If time) export EfficientNet **deepfake** → `deepfake.pte` (CPU).
5. Assemble demo assets (benign / nsfw-proxy: swimwear+classical art / public deepfake samples).
- **DoD:** real `.pte` runs on S22U (CPU) with matching numerics. *If export fights you, spend all of Day 0 here.*

### Day 1 — P0: complete on-device demo (CPU)
1. `InAppFeedScreen` (LazyColumn over demo assets) + `InAppFeedSource`.
2. `Preprocessor` (Bitmap→tensor, ImageNet norm, reused buffers, off-main-thread).
3. `ModelRuntime` + `NsfwDetector` (CPU) with timing.
4. `PolicyEngine`: scores→severity (None/Medium/High), tunable thresholds.
5. `ComposeOverlayRenderer`: blur+tap-to-reveal (Medium), safety card (High).
6. `TelemetryHud` (Tier-1 ms, fps, backend="CPU"); `SettingsPanel` sliders.
7. Verify offline (airplane mode).
- **DoD (M1+M2):** scroll feed offline → benign passes, NSFW proxy blurs w/ reveal, high blocks, HUD shows CPU latency. **Complete, demoable.**

### Day 2 — P1: NPU numbers + deepfake signal
- **Track A (numbers):** `export_qnn.py` → quantized `nsfw.pte` (SM8450); bundle `.so`; `ModelRuntime` CPU↔NPU switch; HUD shows **NPU latency/fps**; capture CPU-vs-NPU table. *If ViT delegation/quant is poor → swap to conv-net NSFW.*
- **Track B (deepfake):** `DeepfakeDetector` (CPU); `CandidateRouter` (trigger on scroll-settle / scene-change); `PolicyEngine` Low severity → "possibly manipulated NN%" badge.
- **Track C (stability):** temporal smoothing; `tools/eval.py` → accuracy/P/R + latency/backend + `.pte` sizes (the numbers slide).
- **DoD (M3+M4+M5):** NSFW on NPU w/ HUD latency; deepfake badge on public samples; eval numbers exist.

### Day 3 — P2 stretch + polish + pitch (hard timebox to ~2pm for P2)
- **P2 (until ~2pm only):** `ScreenCaptureService` (MediaProjection FGS) + `ScreenCaptureSource`; `WindowOverlayRenderer` (capture-blur-redraw); reuse pipeline; demo blurring NSFW proxy in a *different* app. **Not solid by 2pm → drop, no regrets.**
- Polish: safety copy, onboarding ("why"), airplane-mode privacy flourish, icon/name.
- **Record backup demo video** of P0+P1 (non-negotiable).
- Pitch deck: problem → on-device privacy → live demo → numbers → vision. Dry-run on device twice.
- **DoD (M6 stretch):** cross-app blur shown or cleanly dropped; polished demo + video + deck.

---

## Cut-line (drop top-first, never below P0)
1. P2 MediaProjection overlay → 2. Deepfake on NPU (keep CPU) → 3. Deepfake entirely (NSFW-only still wins) → 4. NPU port (fall back to CPU). **P0 floor = on-device, real-time, intervening, latency HUD.**

---

## Verification

- **Export correctness:** per model, assert on-device logits ≈ PyTorch logits on fixed inputs (Day 0/2).
- **Model quality:** `tools/eval.py` → precision/recall on the small labeled holdout per model + per-backend latency + `.pte` size.
- **On-device smoke (per checkpoint):** install, airplane mode, scroll feed → confirm benign passes / NSFW-proxy blurs+reveals / high blocks / manipulation badge appears; HUD shows live ms+fps+backend.
- **Perf gate:** Tier-1 sustains ≥10–15 fps perceived on S22U without thermal collapse over a ~2-min demo run (drop input resolution / throttle if not).
- **Demo safety:** confirm only benign proxies + public face datasets are bundled — zero real explicit imagery.

---

## Key decisions made
- **NSFW primary = Marqo ViT-tiny**, with a conv-net NSFW fallback reserved specifically for the QNN/HTP port (ViT delegation risk).
- **Deepfake primary = small EfficientNet-B0 detector** on CPU, triggered; SigLIP rejected as too heavy; output is a confidence signal only.
- **Surface A classifies source bitmaps directly** (no graphicsLayer capture).
- **Surface B uses capture-blur-redraw** for a realistic blur (overlays can't edit underlying pixels).

## Risks (top, with mitigations)
1. **Quantization breaks accuracy on HTP** (high/high) → validate on-device post-quant; conv-net fallback; CPU path always available.
2. **ViT partially falls back on HTP** (med/high) → Day-2 delegation smoke test; conv-net NSFW fallback.
3. **QNN `.so` bundling / SDK-NDK version hell** (med/high) → pin versions Day 0; CPU is the floor; NPU is upside.
4. **Android 15 overlay+FGS ordering / per-session consent** (med/med) → overlay window visible before FGS start; fresh consent each session; P2 only.
5. **Per-frame thermals on 8 Gen 1** (med/med) → throttle 10–15 fps, smoothing, lower resolution; tune to S22U.
6. **Solo time overrun** (high/high) → P0/P1/P2 ladder + cut-line + backup video.

## Open items (resolve during build, non-blocking)
- Exact ExecuTorch/QNN versions (pin Day 0). Final deepfake weights (confirm Day 0/2 export). Optional face detector to gate Tier-2 (P1 nice-to-have; MVP uses scroll-settle/scene-change).

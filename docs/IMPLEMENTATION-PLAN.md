# SafeScreen AI — Implementation Plan (3-Day Solo Build)

**Companion to:** `PRD-SafeScreen-AI.md`
**Constraint:** 1 dev, ~72 hours, dev device = S22 Ultra (8 Gen 1).
**Operating principle:** every checkpoint ends in a *demoable* state. P0 alone wins; P1/P2 are ambition delivered in risk order.

---

## Tech Stack

- **App:** Kotlin + Jetpack Compose (single-activity).
- **Inference:** ExecuTorch Android runtime (AAR), `.pte` models.
- **Backends:** XNNPACK (CPU, baseline) → QNN/Hexagon HTP (NPU, perf).
- **Export (laptop):** Python + PyTorch + ExecuTorch export/quantization toolchain → `.pte`.
- **Capture:** Compose render control (P0); MediaProjection + `SYSTEM_ALERT_WINDOW` overlay (P2).
- **Eval:** small Python script (accuracy/P/R + latency + model size).

---

## Architecture skeleton (build order matches PRD §5.2)

```
app/
  FrameSource (InAppFeedSource | ScreenCaptureSource)
  Preprocessor
  ModelRuntime (XNNPACK | QNN)
  Detector (NsfwDetector | DeepfakeDetector)
  CandidateRouter
  PolicyEngine (thresholds + temporal smoothing)
  InterventionRenderer (ComposeOverlayRenderer | WindowOverlayRenderer)
  TelemetryHud
tools/
  export_models.py   (PyTorch -> .pte, XNNPACK then QNN)
  eval.py            (accuracy/P/R + latency + size)
assets/
  demo/{benign, nsfw_proxy, deepfake_samples}
  models/{nsfw.pte, deepfake.pte}
```

**Definition of "demoable":** app installs on S22U, runs offline, and visibly intervenes on at least the NSFW path with the HUD showing latency.

---

## DAY 0 — De-risk the toolchain first (½ day, do before anything pretty)

> The #1 cause of hackathon death is discovering on Day 3 that the model won't export. Kill that risk now.

- [ ] Android Studio project: Kotlin + Compose, min SDK set, runs hello-world on S22U (USB debug).
- [ ] Integrate ExecuTorch Android runtime (AAR); load + run a trivial `.pte` on **XNNPACK/CPU** on-device. Prove the runtime works.
- [ ] On laptop: pick **NSFW model**, export PyTorch → `.pte` (XNNPACK), quantize. **Smoke test:** does it export and produce sane scores? If not → swap model immediately.
- [ ] Repeat export smoke test for **deepfake model** (lower priority; OK to defer if NSFW is shaky).
- [ ] Assemble demo assets (benign / nsfw-proxy / public deepfake samples).

**DoD:** ExecuTorch runs a real `.pte` on the device on CPU; NSFW model exports. **If export is fighting you, spend the whole of Day 0 here — nothing else matters yet.**

---

## DAY 1 — P0: complete on-device demo on CPU

Goal: a full, winnable demo running on XNNPACK/CPU. No NPU, no deepfake, no cross-app yet.

- [ ] `InAppFeedSource`: Compose scrollable feed/gallery rendering demo assets; emits "currently visible image" + change hint.
- [ ] `Preprocessor`: Bitmap → 224×224 → normalized tensor.
- [ ] `ModelRuntime` + `NsfwDetector`: load `nsfw.pte` (CPU), `infer() -> nsfw_score` with timing.
- [ ] `PolicyEngine`: scores → severity (None/Medium/High) with tunable thresholds.
- [ ] `ComposeOverlayRenderer`: severity-tiered UX — blur+“tap to view anyway” (Medium), block+safety card (High). True pixel control.
- [ ] `TelemetryHud`: Tier-1 ms, fps, backend label ("CPU").
- [ ] Settings panel: live threshold sliders.
- [ ] Verify offline (airplane mode).

**DoD (P0 / M1 + M2):** scroll feed offline → benign passes, NSFW proxy blurs with reveal, high blocks, HUD shows CPU latency. **This is a complete demo. If Days 2–3 implode, you still present this.**

---

## DAY 2 — P1: NPU numbers + deepfake signal

Goal: turn the headline dials — move the hot path to the Hexagon NPU and add the manipulation signal.

**Track A — NPU port (the numbers):**
- [ ] Export `nsfw.pte` for **QNN/HTP** backend (quantized).
- [ ] `ModelRuntime` backend switch (CPU ↔ NPU); load QNN model on-device.
- [ ] Measure; HUD shows **NPU latency + fps**. Capture CPU-vs-NPU comparison for slides.

**Track B — Deepfake signal:**
- [ ] `DeepfakeDetector` (`deepfake.pte`, CPU is fine here).
- [ ] `CandidateRouter`: trigger Tier-2 on frame-change / scroll-stop (MVP: skip dedicated face detector).
- [ ] `PolicyEngine`: add Low severity → "Possibly manipulated (NN%)" badge.
- [ ] `TelemetryHud`: add Tier-2 ms when it fires.

**Track C — Stability:**
- [ ] Temporal smoothing/hysteresis in PolicyEngine (no strobing on borderline frames).
- [ ] `eval.py`: accuracy/P/R per model + latency per backend + `.pte` sizes → numbers slide.

**DoD (P1 / M3 + M4 + M5):** NSFW runs on NPU with latency on HUD; deepfake badge shows on public samples; eval numbers exist. **Strong technical story locked.**

---

## DAY 3 — P2 stretch + polish + pitch (timeboxed hard)

> Hard timebox on stretch. Polish + a recorded demo beat a half-working wow.

- [ ] **Until ~2pm only — P2 MediaProjection (Surface B):**
  - [ ] Foreground service + MediaProjection permission flow; `ScreenCaptureSource` via `ImageReader`.
  - [ ] `WindowOverlayRenderer`: `SYSTEM_ALERT_WINDOW` overlay painting blur rectangle / safety card on top of another app.
  - [ ] Same pipeline reused; demo blurring NSFW proxy inside a *different* app.
  - [ ] **If not solid by 2pm → drop it, no regrets.** P0/P1 already win.
- [ ] Polish: safety copy, onboarding (the "why"), no-network/airplane flourish, app icon/name.
- [ ] **Record backup demo video** of the full P0+P1 flow (non-negotiable insurance).
- [ ] Pitch deck: problem → on-device privacy → live demo → **numbers (latency/size/NPU)** → vision.
- [ ] Dry-run the live demo on the actual device twice.

**DoD (M6 stretch):** cross-app blur demonstrated *or* cleanly dropped; polished P0+P1 demo + backup video + deck ready.

---

## Cut-line policy (when time runs out)

Drop in this order, top first:
1. P2 MediaProjection cross-app overlay.
2. Deepfake on NPU (keep on CPU).
3. Deepfake entirely (NSFW-only is still a complete, on-theme demo).
4. NPU port (fall back to CPU/XNNPACK — still on-device, just slower numbers).

**Never cut below P0.** P0 = on-device, real-time, intervening, with a latency HUD. That is the floor, and it already clears the bar.

---

## Daily checkpoints

| End of | Must be true |
|--------|--------------|
| Day 0 | ExecuTorch runs a real `.pte` on S22U (CPU); NSFW model exports. |
| Day 1 | Full P0 demo runs offline on-device (CPU). |
| Day 2 | NSFW on NPU + latency on HUD; deepfake badge; eval numbers. |
| Day 3 | Polished demo + backup video + deck; P2 done or cleanly dropped. |

---

## Verification (lightweight, not full TDD — this is a 72h spike)

- The **eval harness is the test** for model correctness (accuracy/P/R on holdout).
- On-device **smoke checks** per checkpoint: install, run offline, confirm intervention + HUD.
- Manual demo dry-runs on the S22U before judging.

# SafeScreen AI — Measured Results (so far)

All numbers measured **on-device** on a **Galaxy S22 Ultra (Snapdragon 8 Gen 1, SM8450, Android 14)**,
**CPU/XNNPACK** backend via ExecuTorch 0.6.0. NPU/QNN numbers pending (Linux export). Honest caveats noted.

## Detection — does it work on real content?
Tested live via the background screen monitor (MediaProjection → detectors → blur overlay):

| Content (on screen) | NSFW (combined) | breakdown | Result |
|---|---|---|---|
| Real explicit image | **0.70** | ml 0.43 + 0.7·skin 0.38 | **Blurred** ✅ |
| Normal screen (launcher / app / text) | **~0.01** | ml ~0.00, skin ~0.02 | Clear ✅ |
| Landscape / food (in-app feed) | ~0.001–0.005 | ml | Clear ✅ |

- The NSFW signal is a **weighted combination**: `combined = ml_nsfw + 0.7·skin_ratio` (max over 3 screen
  crops). The small ML classifier alone under-fired on real porn (~0.05–0.43); the skin-tone backstop
  closes the gap. Clean ~0.70-vs-~0.01 separation between explicit and normal content.

### NSFW model A/B — MobileNetV4 vs Marqo on REAL content (scored programmatically, never viewed)
Three real test images (WhatsApp-compressed) on the S25 Ultra, scored offline through both models with the
3-crop max (the on-device path). Catch-rate revealed the conv model is **unreliable** on real content:

| Image | MobileNetV4 (combined) | **Marqo (calibrated)** |
|---|---|---|
| explicit #1 | 0.066 → **miss** | **0.711 → blur** |
| explicit #2 (borderline/compressed) | 0.069 → miss | 0.358 → blur @0.30 |
| benign (high-skin) | 0.012 → clear | 0.094 → clear |

- **MobileNetV4 missed even the clearly-explicit image** (ml 0.07; the skin backstop can't rescue it — ml is
  below the 0.12 gate). On a *different* earlier image it hit 0.70, so its catch-rate is content-dependent.
- **Marqo (`Marqo/nsfw-image-detection-384`, ViT-tiny, 22.6 MB) is far better** — 0.711 vs 0.066 on the same
  image, and tight on benign (max 0.16 across 48 benign images). **We switched the NSFW model to Marqo** with
  a **calibrated, model-aware policy**: trust its score directly (no skin backstop / no UI-FP cap — those were
  MobileNetV4 crutches that would have *capped* Marqo's 0.71→0.45), blur threshold **0.30** (safe given the
  0.16 benign ceiling). Marqo exports to ExecuTorch + **loads/runs on the Android runtime** (CPU/XNNPACK);
  ViT→Hexagon delegation is still to be proven (the conv model stays the NPU-headline candidate).
- Caveat: ViT@384 is heavier than the conv@224 — on-device latency for the 3-crop screen path needs
  measuring (may move to 1 full-frame pass). Live on-device confirmation pending.

## Deepfake — measured, and why it stays badge-only
We ran the bundled **EfficientNet-B0 (FaceForensics++ c23)** model on an out-of-distribution public set
(`Hemg/AI-Generated-vs-Real-Images-Datasets`, 50 real + 50 AI-generated, host eval):

| Class | mean P(fake) | | Metric @0.5 |
|---|---|---|---|
| Real images | **0.370** | | Accuracy **0.54** |
| AI-generated | **0.411** | | Precision / Recall **0.54 / 0.52** |

- That's **barely above chance** — the two classes are nearly inseparable (0.37 vs 0.41). The model was
  trained on **face-only FF++ manipulations** and does **not generalize** to general AI imagery. This is
  the empirical reason deepfake is **a confidence badge, never a blur/verdict** — consistent with the PRD's
  "on-device deepfake detection does not generalize" stance. We surface "possibly manipulated (NN%)" only.
- Honest scope: this measures *cross-distribution generalization* (the realistic case), not in-domain FF++
  accuracy (which the source repo reports higher). The point stands: we must not present it as a verdict.

## Latency — MEASURED on the target device (S25 Ultra, 8 Elite / SM8750)
Live screen-monitor path (the real production loop: MediaProjection frame → 3-crop NSFW + deepfake),
**CPU/XNNPACK**, **n=48 frames**, Android 15. These are measured, not estimated:

| Stage (CPU/XNNPACK, 8 Elite) | avg | p50 | p90 | max |
|---|---|---|---|---|
| **NSFW** (MobileNetV4-conv-small, 3 crops/frame) | **14.5 ms** | 14 | 16 | 22 |
| NSFW per-crop (single 224 inference) | **~4.8 ms** | | | |
| **Deepfake** (EfficientNet-B0, 1 frame) | **11.0 ms** | 11 | 13 | 15 |
| **Combined per frame** | **25.5 ms** | | | — |

- **25.5 ms/frame of compute = ~83 % idle headroom** under the 150 ms throttle (the cap, not the compute,
  sets the ~6 fps cadence; raw compute could sustain ~39 fps). Both models load on-device in <20 ms.
- Both `.pte` load reported `backend=CPU/XNNPACK`, `models=true` — **real ExecuTorch inference on the target
  SoC, no crashes** (the serialized single-thread inference fix holds). Normal content (app/home screen) →
  `nsfw 0.006, action=SHOW` (clear, no false positive); deepfake `manip` flips 1.000↔0.015 on whole-screen
  content — the on-device proof that it must stay **badge-only**.
### ⚡ NSFW on the Hexagon NPU — MEASURED (ExecuTorch QNN, SM8750) — THE HEADLINE
Compiled our ExecuTorch NSFW MobileNetV4 to a **fully-HTP-delegated int8 QNN `.pte`** (2.88 MB; every op
delegated — the lowered graph is a single `executorch_call_delegate`, **zero CPU fallback**) and profiled it
on the **S25 Ultra (8 Elite)** with `qnn_executor_runner`, 100 iterations:

| NSFW MobileNetV4 — single 224 inference | latency | size |
|---|---|---|
| CPU / XNNPACK (8 Elite) | ~4.8 ms | 10 MB (fp32) |
| **Hexagon NPU / HTP (8 Elite)** | **0.212 ms** | **2.88 MB (int8)** |
| **Speedup** | **~22×** | 3.5× smaller |

- Raw runner output: `100 inference took 21.213 ms, avg 0.212 ms` on the HTP. The **3-crop per-frame NSFW
  gate drops from ~14.5 ms (CPU) to ~0.64 ms on the NPU.** Consistent with AI Hub's reference (MobileNet-v3
  = 0.46 ms on 8 Elite NPU); ours is smaller, hence faster.
- Implies a large energy-per-inference reduction (far less compute time on a purpose-built NPU); exact
  inferences/Joule via the unplugged benchmark next.
- **Toolchain (reproducible):** ExecuTorch 0.6.0 built from source with the QNN backend + **QNN SDK 2.31** on
  a cloud Ubuntu x86 box; `build_executorch_binary` PTQ **8a8w per-channel** (48 calibration images) →
  `QcomChipset.SM8750` → full-graph HTP delegation. See `NPU-EXPORT-RUNBOOK.md`.
- Next: bundle the QNN `.pte` + runtime libs into the app (HUD shows `QNN/HTP`); compile the deepfake model
  the same way; measure energy.

### Prior estimates (S22 Ultra, 8 Gen 1 — dev device, to re-measure)
NSFW ~6–9 ms single / ~20–40 ms 3-crop; deepfake ~18–28 ms single. Earlier the NSFW model was a ViT@384 at
**~1 s/frame**; switching to MobileNetV4-conv-small@224 then multi-crop cut it to the numbers above.

## Model footprint
| Model | Arch | Input | Size (.pte, fp32) |
|---|---|---|---|
| NSFW | MobileNetV4-conv-small | 224 | **10.0 MB** |
| Deepfake | EfficientNet-B0 (FF++) | 224 | **16.0 MB** |
| **Total** | | | **~26 MB** · arm64 APK ~69 MB (with ExecuTorch native libs) |

## Privacy
- **No `INTERNET` permission** in the manifest — the app cannot exfiltrate frames. Works in airplane mode.
- Frames processed in memory; never written to disk or sent anywhere.

## Fairness & false-positive control (NSFW skin backstop)
Hardened on host (safe data only) for two issues the eval surfaced:
- **Fairness:** the original RGB skin rule required `r>95` and **failed on the darkest tone (Fitzpatrick
  VI)** — under-protecting exactly the users this tool is for. Replaced with a tone-invariant **YCbCr**
  rule that fires across **all six Fitzpatrick tones** (validated on swatches).
- **False positives (warm scenes):** warm-toned benign scenes read as "skin" (autumn 0.92, city 0.87
  skin-ratio). We **gate the skin term on the ML model having signal** (`ml ≥ 0.12`): a sunset (ml≈0) stays
  clear, while real nudity (ml≈0.43 + skin) blurs. Validated — every benign image → combined **< 0.05**.
- **False positives (UI screens):** the small NSFW ML model is *noisy on app UIs* — a **dial pad** scored
  `ml=0.55–0.70` (no skin) and blurred. Fixed by requiring signal **agreement**: a moderate ML score with
  ~zero skin is capped below the blur line; only skin-corroborated content (`skin ≥ 0.15`) or a very
  confident ML (`≥ 0.85`) acts. **Verified on-device (S25 Ultra):** the dialer now scores `ml=0.695,
  skin=0.000 → SHOW` (fully visible, no blur), while real nudity (skin-heavy) still blurs.

## Honest caveats (we state these)
- **CPU/XNNPACK today; NPU/QNN pending** (Linux x86 export host). Expect a further 2–4× on the Hexagon NPU.
- **Energy** not yet measured — the in-app benchmark needs the phone **unplugged** (USB charging skews
  battery current); whole-device estimate only (NPU-isolated power needs Snapdragon Profiler).
- **Deepfake is currently badge-only**: it false-fires on whole-screen, non-face content and is ~chance
  out-of-distribution (measured 0.54 acc — see "Deepfake" above), so it doesn't drive blur until gated by a
  face detector. NSFW drives the live blur; deepfake stays a "possibly manipulated (NN%)" signal.
- **NSFW model** is a small off-the-shelf classifier; the skin backstop compensates for its under-firing
  on real content. A fine-tuned model would raise the ML term.
- Detection is **whole-frame / multi-crop** (classifier, not localizer): the whole screen blurs, not a
  sub-region.

## What these numbers support in the pitch
On-device, real-time (~6 fps) dual-detector safety on a 2022 phone, ~26 MB of models, **private by
construction** — with a clear path to lower latency/energy on the Hexagon NPU.

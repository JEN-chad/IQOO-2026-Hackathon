# SafeScreen AI

**An on-device visual safety layer for Android.** SafeScreen AI runs in the background and continuously
scans your screen — in *any* app — for explicit/abusive imagery and AI-generated (deepfake) media, then
**blurs and warns** before you engage. Every model runs locally on the Snapdragon NPU via **ExecuTorch**.
The app ships with **no `INTERNET` permission** — your screen never leaves your device.

**Why on-device (the 5 vectors judges reward):** lower latency · offline · privacy · energy · real-time.
On two of them cloud is *disqualifying* — to classify whether an image is explicit or a deepfake of you, a
cloud would have to *receive your most private images*, and the blur has to land *before* you see them.

> Built for the Qualcomm × Meta ExecuTorch Hackathon. Validated on the **Galaxy S25 Ultra (Snapdragon 8 Elite)**; also runs on the S22 Ultra (8 Gen 1).

## Why
Harmful and manipulated content spreads faster than cloud moderation can react — and asking a victim to
upload a private screenshot to a server to ask "is this safe?" is itself a harm. SafeScreen puts the
safety check at the edge: private by construction, real-time, and it works regardless of which app the
content appears in. (Full story: [docs/JOURNEY.md](docs/JOURNEY.md).)

## How it works
```
Screen (MediaProjection, ~12 fps)
   → Preprocess → ┬─ NSFW classifier  (MobileNetV4-conv-small, ExecuTorch)   ← PRIMARY, every frame
                  └─ Deepfake detector (EfficientNet-B0 / FaceForensics++)    ← SECONDARY, triggered/badge
   → PolicyEngine (severity) → draw-over-apps overlay: blur + "NSFW xx% · AI-gen yy%", tap-to-reveal
```
- **Two on-device detectors**, ~26 MB total, **25.5 ms/frame measured on the S25 Ultra (8 Elite, CPU)** —
  NSFW runs every frame (primary); deepfake is the triggered secondary signal.
- **System-wide:** a foreground service + overlay protect *any* app, not one integration.
- **Private by construction:** no `INTERNET` permission — works in airplane mode; the overlay shows
  "analyzed on-device · 0 bytes left your phone".
- **On-device benchmark** built in: latency, throughput, and a whole-device energy estimate.

## Status
| Capability | State |
|---|---|
| Real-time **NSFW** blur (ML + skin backstop), system-wide — **PRIMARY** | ✅ validated on real content (explicit → blur, normal → clear); UI false-positives fixed via skin-gate ([results](docs/RESULTS-SO-FAR.md)) |
| System-wide background monitor + overlay with live scores | ✅ working on **S25 Ultra — 25.5 ms/frame, ~12 fps**, no crashes |
| On-device benchmark (latency / throughput / energy) | ✅ built |
| **Deepfake / AI-gen** detector — **SECONDARY** | ✅ honest badge (measured ~chance out-of-distribution; face-gating planned to drive blur) |
| Hexagon **NPU** via **ExecuTorch QNN** (int8) on the 8 Elite | ⏳ build host **pre-staged** (ExecuTorch 0.6.0 + both models validated + calibration ready); waiting only on the QNN SDK to compile |
| Privacy: no INTERNET permission · airplane-mode · on-device badges | ✅ verifiable + shown in-app |
| Text moderation (OCR/VLM) | out of scope for now (images/video only) |

## Build & run
```bash
./gradlew :app:installDebug        # build + install on a connected device (USB debugging authorized)
```
Then: open SafeScreen → **Start protection** → grant *Display over other apps* + screen capture. Use
**Run benchmark** for on-device numbers, or **Open test feed** to see detection without permissions.
Full setup, model export, and the NPU port: **[docs/SETUP-DEV.md](docs/SETUP-DEV.md)**.

## Repo map
- `app/` — Android app (`ai.safescreen`): `capture/` (MediaProjection service + overlay),
  `pipeline/` (preprocess, ExecuTorch runtime, detectors), `policy/` (severity engine),
  `render/` (Compose interventions), `bench/` (power meter + benchmark), `ui/` (HUD, settings), `feed/`.
- `tools/` — `export_xnnpack.py` (CPU), `export_qnn.py` (NPU, Linux, per-channel int8 + delegation check),
  `eval.py` (P/R + latency).
- `docs/` — [PRD](docs/PRD-SafeScreen-AI.md) · [Journey](docs/JOURNEY.md) · [Results](docs/RESULTS-SO-FAR.md) · [Pitch](docs/PITCH.md) ·
  [Demo runbook](docs/DEMO-RUNBOOK.md) · [NPU efficiency research](docs/NPU-EFFICIENCY-RESEARCH.md) ·
  [Dev setup](docs/SETUP-DEV.md) · [Implementation plan](docs/IMPLEMENTATION-PLAN-DETAILED.md).

## Honest limitations
- Deepfake detection generalizes poorly across generators — **measured ~0.54 accuracy out-of-distribution**
  (consistent with 2026 benchmarks where even SOTA detectors are ~chance on modern generators). We surface
  it as a "possibly manipulated (NN%)" badge, never a verdict.
- Detection is whole-frame (classifier, not localizer): the whole screen blurs, not a sub-region.
- Energy is a whole-device estimate (NPU-isolated power needs Snapdragon Profiler); valid only unplugged.
- ExecuTorch **0.6.0** on both the Android AAR and the Python exporter (keeps `.pte` compatible); the
  Hexagon NPU port builds 0.6.0 from source with the QNN backend (QNN SDK 2.31) — no app migration needed.

## Models & license
NSFW: `taufiqdp/mobilenetv4_conv_small` (Apache-2.0). Deepfake: `Xicor9/efficientnet-b0-ffpp-c23`
(FaceForensics++). Demo content: public-domain photos + research-dataset faces (no real explicit imagery).

# SafeScreen AI — Model Research & Results-Driven Plan

**Goal:** maximize the two scored dimensions of the Qualcomm × Meta ExecuTorch hackathon —
**Technical** (NPU utilization / latency / energy) and **Functionality** (problem-solving / UX / use-case) —
by choosing the *latest benchmarked* models that (a) run on-device via **ExecuTorch on Snapdragon**, (b) are
**NPU-friendly** (reparameterized-conv delegates cleanly to the Hexagon HTP; attention-heavy nets risk
CPU-fallback + quantization loss), and (c) quantize to int8 / w8a16.

**Scope & priority (locked):** **(1) NSFW detection + blurring is the PRIMARY pillar** — runs every frame,
drives the actual blur/block. **(2) AI-generated-content detection is SECONDARY** — triggered, face-gated,
honest badge. Both target **images and video frames** (the screen monitor already covers video — it is just
frames over time). **Text content is out of scope for now** (no per-frame LLM / OCR-of-text path).

> **Hard filter:** AI Hub is a research *source* (curated Snapdragon-proven architectures + reference
> numbers), **not** our deploy runtime — it emits TFLite/QNN/ONNX, not ExecuTorch `.pte`. We deploy via
> ExecuTorch's own QNN backend. (See `NPU-EXPORT-RUNBOOK.md`.)

---

## 0. Reference NPU numbers (Qualcomm AI Hub — *measured on real devices*)
These anchor our expectations for our own models:

| Model | Params | Input | **8 Elite NPU** | 8 Gen 3 | 8 Gen 1 | Runtime |
|---|---|---|---|---|---|---|
| MobileNet-v3-Large | 5.47 M | 224² | **0.46 ms** (w8a16) | 0.66 ms | 1.2 ms | QNN |
| MediaPipe Face Detector | 135 K | 256² | **0.14 ms** (w8a8) | — | — | QNN/TFLite |
| MediaPipe Face Landmark | 603 K | — | 0.10 ms | — | — | QNN |

- On the 8 Elite NPU, **56+ catalog models run < 5 ms** (vs 13 on CPU). Conv-heavy nets (ConvNeXt-Tiny,
  EfficientNet-v2-S) are where the NPU dominates.
- **Implication:** our NSFW MobileNetV4-conv-small is ~**4.8 ms on the 8 Elite CPU** today → on the NPU it
  should land near **sub-millisecond (~0.5 ms) ≈ 10× faster**, far lower energy. That is the headline.
- A face detector costs **~0.14 ms** on the NPU — effectively free → the face-gated cascade is "for free."

Sources: [MobileNet-v3-Large](https://huggingface.co/qualcomm/MobileNet-v3-Large),
[MediaPipe-Face](https://huggingface.co/qualcomm/MediaPipe-Face-Detection),
[AI Hub catalog](https://aihub.qualcomm.com/mobile/models),
[LiteRT×Qualcomm NPU](https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/).

---

## 1. Model survey — latest benchmarked, by category

### A. Efficient backbones (the NSFW gate / general classifier)
| Model | Top-1 (IN-1k) | Mobile latency | NPU fit | Notes |
|---|---|---|---|---|
| **MobileNetV4-conv-small** *(current)* | ~73% | ~4.8 ms CPU (8 Elite) | **✅ clean** | conv-only, our NSFW backbone |
| **RepViT-M1.0 / M2.3** | 80% / 83.7% | 1.0 / 2.3 ms (iPhone12) | **✅ clean** | reparam-conv at inference → great on HTP |
| **FastViT (Apple)** | +4.2% vs MobileOne @ iso-latency | sub-2 ms | ✅ (reparam) | hybrid, reparameterizable |
| **iFormer-M (ICLR'25)** | 80.4% | 1.1 ms | ⚠️ has attn | beats MobileNetV4-Conv-M, RepViT-M1 |
| **MobileOne (Apple)** | ~75–80% | ~1 ms | ✅ clean | pure reparam-conv |
| ConvNeXt-Tiny / EfficientNet-v2-S | high | <5 ms NPU (8 Elite) | ✅ conv | AI Hub-benchmarked, conv-heavy |

**Verdict:** for the **NPU headline**, stay with **reparameterized CONV** (MobileNetV4-conv / RepViT /
FastViT / MobileOne) — they delegate fully to HTP and quantize per-channel without the accuracy cliff that
hits ViTs. iFormer/ViT only if delegation is verified.
Sources: [RepViT](https://arxiv.org/abs/2307.09283), [FastViT](https://arxiv.org/abs/2303.14189),
[iFormer ICLR'25](https://arxiv.org/html/2501.15369v1).

### B. NSFW / content-moderation (latest open models)
| Model | Acc | Arch | Size | NPU fit | Use |
|---|---|---|---|---|---|
| **Falconsai/nsfw_image_detection** | 98.04% | ViT | ~85 MB | ⚠️ ViT | strong, popular |
| **Marqo/nsfw-image-detection-384** | 98.56% | ViT-tiny@384 | **tiny (18–20× smaller)** | ⚠️ ViT | best acc/size |
| **AdamCodd/vit-base-nsfw-detector** | ~? | ViT | mid + **quantized ONNX** | ⚠️ ViT | has int8 ONNX |
| **MichalMlodawski/…-large** | — | FocalNet | large | ⚠️ | 3-class Safe/Questionable/Unsafe |
| Image-Guard-2.0 (prithivMLmods) | — | SigLIP2 <100M | mid | ⚠️ | multi-label safety |
| **MobileNetV4-conv-small** *(current)* | weaker (noisy → dialpad FP) | conv | 10 MB `.pte` | **✅ clean** | our NPU path |

**Tension:** the strong NSFW checkpoints are **ViT/transformer** (accuracy, but NPU-risk + a ViT already
crashed on our AAR); our conv model is NPU-clean but **noisy** (false-fired on a dial pad). Resolve by
experiment **E4** below — likely **conv on the NPU path + (optionally) a ViT NSFW on CPU for accuracy/FP**,
since the 8 Elite has huge CPU headroom (25.5 ms/frame, ~83% idle).
Sources: [Marqo-384](https://huggingface.co/Marqo/nsfw-image-detection-384),
[Falconsai](https://huggingface.co/Falconsai/nsfw_image_detection),
[AdamCodd](https://huggingface.co/AdamCodd/vit-base-nsfw-detector).

### C. Deepfake / AI-generated detection (our weakest pillar — and it's *fundamentally* hard)
A **2026 zero-shot benchmark** (16 methods / 23 detectors / **2.6 M images / 291 generators**) is decisive:
| Detector | Mean acc | Practicality |
|---|---|---|
| **Community-Forensics** | **78%** | 5-model **ensemble**, heavy → not on-device |
| DRCT CLIP-ViT | ~72% | CLIP-ViT, large |
| **SAFE** | 68.8% | single-model (more practical) |
| **PatchCraft** | 67.5% | **single-model** (most deployable) |
| worst (CNNSpot) | 37.5% | — |

- **Modern commercial generators (Flux Dev, Firefly v4, Midjourney v7) defeat *most* detectors → 18–30%.**
- **Training-data alignment matters more than architecture** (20–60% variance).
- **Conclusion (evidence-backed):** on-device deepfake detection **does not generalize** — exactly our
  measured ~chance result. **Keep it a face-gated, honest "possibly manipulated (NN%)" badge — never a
  verdict.** If we upgrade the model at all, **PatchCraft / SAFE** are the only single-model, deployable
  options; the real win is **face-gating + face-crop** (in-distribution input), not swapping the net.
Sources: [Benchmark arXiv 2602.07814](https://arxiv.org/abs/2602.07814),
[Awesome-AIGC-Detection](https://github.com/ant-research/Awesome-AIGC-Image-Video-Detection).

### D. Face detection (the CandidateRouter that gates Tier-2)
| Model | Size | Params | Speed | Export |
|---|---|---|---|---|
| **YuNet** | **< 1 MB** | 75.9 K | ms-level, anchor-free | OpenCV Zoo (ONNX) |
| **BlazeFace** | 1–2 MB | small | sub-ms mobile GPU | MediaPipePyTorch (PyTorch→`.pte`) |
| **MediaPipe-Face** | 260 KB (w8a8) | 135 K | **0.14 ms NPU (8 Elite)** | AI Hub PyTorch source |
| SCRFD (InsightFace) | larger | — | accurate, moderate | ONNX |

**Verdict:** **BlazeFace / MediaPipe-Face** — tiny, Apache-licensed PyTorch source we can export to
ExecuTorch `.pte` ourselves; ~0.14 ms on the NPU.
Sources: [YuNet](https://link.springer.com/content/pdf/10.1007/s11633-023-1423-y.pdf),
[BlazeFace](https://arxiv.org/pdf/1907.05047), [MediaPipe-Face](https://huggingface.co/qualcomm/MediaPipe-Face-Detection).

---

## 2. Recommended choices (decisions)
1. **NPU headline model:** keep **MobileNetV4-conv-small (NSFW)** on the QNN/HTP path — conv, clean
   delegation, ~0.5 ms expected. This is the Technical 40% centerpiece.
2. **Face detector (functionality):** **BlazeFace / MediaPipe-Face** → ExecuTorch `.pte` → CandidateRouter
   gating the deepfake stage.
3. **Deepfake:** keep **EfficientNet-B0 FF++**, now **face-gated on face crops** (in-distribution), badge-only.
4. **NSFW accuracy (optional, functionality):** A/B a **ViT NSFW (Falconsai / AdamCodd-ONNX)** on CPU vs the
   conv model — adopt whichever gives the best FP/catch-rate (CPU has headroom).

---

## 3. Results-driven experiments — each ends in a NUMBER + a go/no-go gate
| # | Dimension | Experiment | Expected result | Gate |
|---|---|---|---|---|
| **E1** | Technical | NSFW conv → QNN HTP `.pte` (int8 8a8w, calib) → profile on 8 Elite | **~0.5 ms** (ref 0.46 ms), full delegation | <1 ms + 0 CPU-fallback → integrate (Phase 2) |
| **E2** | Technical | Deepfake EfficientNet-B0 → QNN HTP → profile | a few ms NPU | clean delegation → keep |
| **E3** | Functionality | Export face detector `.pte`; gate deepfake on face crops | FP on non-face UI → ~0; deepfake acc on crops ↑ vs whole-frame | FP drop measurable → keep cascade |
| **E4** | Functionality | NSFW A/B: conv vs ViT (Falconsai) — FP on benign screens (dialpad) + your private catch-rate + latency | ViT fewer FP; conv faster/NPU-clean | pick per FP/latency trade |
| **E5** | Technical | Energy NPU vs CPU (unplugged): mW, mJ/inf, inf/J | NPU lower energy/inf | report with honest caveat |

---

## 4. Execution path (cloud Linux now available)
- **Technical (E1/E2/E5):** provision a **cloud Ubuntu 22.04 x86** box (8 vCPU/16 GB) → install **QNN SDK
  2.37 + Android NDK r26c + ExecuTorch 1.x from source** → run the NPU export + on-device profiling per
  `NPU-EXPORT-RUNBOOK.md` (target `SM8750`, Hexagon **V79**). One-time ~45 min setup, then ~3 min/model.
- **Functionality (E3/E4):** do **now on this Mac + the connected S25 Ultra** (XNNPACK export, no Linux
  needed): export the face detector, wire the CandidateRouter, A/B the NSFW model.
- The two tracks run in parallel; neither blocks the other.

## 5. Risks
- **ViT on HTP** (CPU-fallback ops / int8 accuracy cliff) → keep conv on the NPU path; ViT only if delegation
  verified or kept on CPU.
- **Deepfake won't generalize** (evidence above) → honest face-gated badge, not a verdict.
- **Partial QNN delegation** → verify with `get_delegation_info`; fix or report the bottleneck op.
- **Version drift** → build ExecuTorch AOT + AAR from one source checkout (schema matches by construction);
  keep the 0.6.0 CPU APK as the demo fallback.

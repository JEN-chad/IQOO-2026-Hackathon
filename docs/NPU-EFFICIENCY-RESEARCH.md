# NPU Efficiency on Hexagon HTP via ExecuTorch — Cited Research Brief

Purpose: ground SafeScreen AI's NPU-optimization (the hackathon's 40% "Technical: NPU utilization,
latency, performance, energy efficiency" criterion) in peer-reviewed papers + official docs, for our
exact stack — small conv classifiers (MobileNetV4-conv-small NSFW, EfficientNet-B0 deepfake) on
Snapdragon 8 Gen 1 (SM8450) / 8 Elite (SM8750) Hexagon HTP via ExecuTorch + QNN.

Method: deep multi-source search → 22 sources → 106 claims → 25 adversarially verified (21 confirmed,
4 refuted). Confidence noted per finding.

## TL;DR (the highest-leverage decisions)
1. **Quantize to int8 with PER-CHANNEL symmetric weights + per-tensor activations (QNN default 8a8w).**
   This is the single biggest lever. Naive *per-tensor* int8 **catastrophically breaks** these
   architectures; per-channel keeps them within ~1–2% of FP32.
2. **Get the WHOLE graph onto HTP.** Every non-delegated op silently falls back to the slow CPU
   portable library. Verify with `get_delegation_info()`; target **0 non-delegated nodes**.
3. **Don't assume PTQ is enough — measure, and use QAT if accuracy drops.** (The "PTQ ≈ FP32 at
   8-bit" generalization was *refuted* for MobileNet-class nets.)
4. **Avoid uniform int4** on these compact nets (worst-case accuracy collapse); use mixed precision
   only on robust layers if pushing below int8.
5. **Build a separate `.pte` per SoC** (`soc_model` is fixed at compile time): SM8450 and SM8750.
6. **Report energy rigorously**: energy-per-inference, inferences/Joule, mW — measured, not estimated.

---

## 1. Quantization (the core efficiency lever)

- **Per-channel int8 weights are mandatory for depthwise-separable nets** *(high confidence, 3-0)*.
  Per-tensor int8 PTQ drops MobileNetV2 **71.72% → 0.12%** top-1 and MobileNet-v1 to ~0.001;
  per-channel recovers to within ~1–2% of FP32. Cause: per-output-channel weight ranges vary too much
  after BatchNorm folding in depthwise convs. Qualcomm QAIRT explicitly requires per-channel symmetric
  weights for Conv/DepthwiseConv. *(Nagel 2019 ICCV [1906.04721]; Krishnamoorthi 2018 [1806.08342];
  Nagel 2021 [2106.08295]; QAIRT docs.)*
- **Per-channel weight quant is ~free on the accelerator** (separate per-channel scale, no accumulator
  rescale); HTP natively supports it — so the old "per-tensor is more HW-efficient" argument is
  **outdated for this platform** *(high, 3-0; Nagel 2021 §2.4.2; QAIRT docs)*.
- **int8 physics**: FP32→INT8 = 4× less tensor memory, ~16× cheaper MAC compute (idealized; quadratic
  in bit-width), and less data movement — and *data movement dominates inference energy*. ⚠️ Real
  wall-clock NPU speedup is typically **2–4×** (memory bandwidth, dequant, non-MAC ops), not 16×.
  *(high, 3-0; Nagel 2021; Krishnamoorthi 2018; Horowitz 2014: INT8 mul ≈0.2pJ vs FP32 ≈3.7pJ.)*
- **PTQ vs QAT**: PTQ first, but **do not assume it suffices at 8-bit** for MobileNet-class models —
  the broad "PTQ within 0.7% of FP32" claim was **REFUTED (1-2)**. QAT narrows the gap to ~1%.
  *(Krishnamoorthi 2018.)*
- **int4 / mixed precision**: uniform int4 is worst for compact convs (MobileNetV2 ~26.8% non-finetuned
  collapse). Conservative mixed int4/int8 (sensitive depthwise layers kept high) loses ~0.6% avg while
  capturing ~40% of int4's speedup — but those numbers are from a non-HTP NPU, so qualitative only
  *(medium, 2-1; FlexiQ EuroSys'26 [2510.02822])*.
- **QNN supports it all**: `QNNQuantizer` (default **8a8w**), PTQ & QAT, ladder 8a8w/16a16w/16a8w/
  16a4w/16a4w_block, fine-grained mixed precision via `custom_quant_annotations` (per-node),
  `submodule_qconfig_list` (per-module), `discard_nodes`, `block_size_map` *(high, 3-0; ExecuTorch
  Qualcomm docs)*. Runtime knobs `--weight_bw/--bias_bw/--act_bw`, `--pack_4_bit_weights`.

**→ Our choice: per-channel symmetric int8 (8a8w) PTQ as baseline; measure accuracy vs FP32 on our eval
set; escalate to QAT only if it drops too far. Keep activations int8; consider 16-bit activations
(16a8w) if a sensitive layer hurts.**

## 2. ExecuTorch QNN/HTP delegation (don't leak ops to CPU)

- **Two-phase delegation** *(high, 3-0)*: AOT — partitioner tags supported nodes, `preprocess()`
  compiles tagged subgraphs into a QNN context blob embedded in the `.pte`; runtime — `init()/execute()`
  run the blob on HTP. Only tagged, connected nodes delegate. *(ExecuTorch delegate/partitioner docs.)*
- **SoC fixed at compile time**: `generate_qnn_executorch_compiler_spec(soc_model=QcomChipset.SM8450 …)`
  → SoC-specific `.pte`. **8 Gen 1 and 8 Elite need separate builds.** *(high, 3-0; Qualcomm backend docs.)*
- **Verify full delegation** *(high, 3-0)*: `from executorch.devtools.backend_debug import
  get_delegation_info`; after `to_backend()`, `get_summary()` reports delegated subgraphs + delegated /
  **non-delegated** node counts, and `get_operator_delegation_dataframe()` gives a per-op table showing
  exactly which ops are NOT on HTP. **Target: 0 non-delegated nodes.** Undelegated ops fall back to the
  portable CPU library ("not intended for performance-sensitive operators"). *(ExecuTorch debug docs.)*

**→ Our choice: after QNN lowering, print `get_delegation_info()`; if any op is non-delegated, identify
and remove/replace it (suspect: SE-block sigmoid/hardswish, global pool, reshapes) until it's 0.**

## 3. Energy measurement (report it credibly)

- Report **energy-per-inference, inferences/Joule, average power (mW), perf-per-watt** — *measured on
  device, not estimated*. Methodology grounded in **DeepEn2023** (ACM/IEEE SEC'23 [2310.18329]), a
  peer-reviewed on-device DL energy study (ms-granularity power via Monsoon-style monitor; released
  kernel/model/app energy datasets) *(high, 3-0)*.
- Tooling chain: **Snapdragon Profiler** (NPU utilization + power rails), `dumpsys batterystats`, and
  on-device current sensors (`BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`) as a pragmatic proxy.

**→ Our choice: a fixed-N inference loop with BatteryManager current×voltage for an on-device energy
estimate in-app; Snapdragon Profiler for the rigorous number on the slide.**

## 4. Architecture → NPU mapping
Depthwise-separable convs (MobileNet/EfficientNet/MobileNetV4) minimize MACs/params but are the
*hardest to quantize* (the depthwise layers), which is exactly why per-channel int8 is non-negotiable.
SE blocks (global pool + sigmoid/hardswish) and reshapes are the ops most likely to break HTP
delegation — flag them in the delegation check.

## Honest caveats (what the research could NOT establish)
- **No on-target measured latency/energy** for MobileNetV4-conv-small / EfficientNet-B0 on SM8450/SM8750
  HTP exists in the sources — so **our own benchmark IS the contribution** (and the pitch number).
- Accuracy figures generalize from MobileNetV2 / EfficientNet-lite (architecturally sound, but
  inferential for our exact models).
- Absolute speedup numbers from older sources (Pixel 2 / 835) are directional, not predictive.

## Primary sources
- ExecuTorch Qualcomm backend: https://docs.pytorch.org/executorch/stable/backends-qualcomm.html
- ExecuTorch delegate debug: https://docs.pytorch.org/executorch/stable/debug-backend-delegate.html
- ExecuTorch partitioner: https://docs.pytorch.org/executorch/stable/compiler-delegate-and-partitioner.html
- Qualcomm QAIRT quantization: https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-10/quantization.html
- Nagel et al. 2021, "A White Paper on Neural Network Quantization": https://arxiv.org/pdf/2106.08295
- Nagel et al. 2019 (ICCV), "Data-Free Quantization": https://arxiv.org/pdf/1906.04721
- Krishnamoorthi 2018, "Quantizing deep CNNs ... a whitepaper": https://arxiv.org/pdf/1806.08342
- FlexiQ (EuroSys'26), mixed int4/int8: https://arxiv.org/pdf/2510.02822
- DeepEn2023 (SEC'23), on-device energy: https://arxiv.org/abs/2310.18329
- Snapdragon Profiler: https://www.qualcomm.com/developer/software/snapdragon-profiler

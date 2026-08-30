# SafeScreen AI — Product Requirements Document (PRD)

**Version:** 0.1 (Hackathon MVP)
**Date:** 2026-06-22
**Event:** Qualcomm × Meta ExecuTorch Hackathon (lablab.ai)
**Author / Owner:** Solo build (Android + ML export)
**Dev device:** Samsung Galaxy S22 Ultra (Snapdragon 8 Gen 1) — *the canary*. Target device: S25 Ultra (8 Elite).
**Status:** Draft for review

---

## 0. TL;DR

SafeScreen AI is an **on-device visual safety layer** for Android. It analyzes what is on screen in real time and, when it detects explicit/abusive imagery or manipulated (deepfake) media, it **warns, blurs, or blocks** the content *before* the user fully engages with it. Everything runs locally on the Snapdragon NPU via **ExecuTorch** — no frames ever leave the device.

The MVP is delivered as a **P0/P1/P2 ladder** so that a solo builder always has a complete, winnable demo:

- **P0 (must ship):** In-app protected feed + real-time NSFW detection + severity-tiered intervention + on-screen latency HUD, running on-device (CPU/XNNPACK minimum).
- **P1 (target):** NSFW gate ported to QNN/Hexagon **NPU** (the perf numbers) + deepfake/manipulation verifier as a confidence signal + temporal smoothing + eval numbers.
- **P2 (stretch):** Cross-app protection via MediaProjection screen capture + overlay.

---

## 1. Problem & Why On-Device

Harmful and manipulated content reaches people **faster than current safety systems can respond**. Most moderation is server-side and *post-hoc* — by the time it acts, the content has been viewed, screenshotted, forwarded, or amplified. Victims (disproportionately women, teens, and children) have little control once content is shared.

Cloud moderation is the wrong tool for the *first line of defense*:
- **Latency:** a round trip is too slow for "before you see it."
- **Privacy:** asking a victim to upload a private/abusive screenshot to a server to ask "is this bad?" is itself a harm.
- **Coverage:** much harmful content is ephemeral, on-screen, in apps the platform doesn't moderate.

**SafeScreen AI's bet:** a *local* safety layer at the edge — capture → classify → intervene, all on the NPU — is faster, more private, and works regardless of which app the content appears in.

### Why this wins *this* hackathon
Judging rewards (in priority order): (1) it actually runs on-device on Snapdragon via ExecuTorch, (2) measured latency/size/perf numbers, (3) a working live demo, (4) a real, urgent problem. SafeScreen hits all four — and the **on-screen latency/NPU HUD** turns "trust me it's on-device" into visible proof.

---

## 2. Goals & Non-Goals

### Goals (MVP)
- G1. Detect explicit/NSFW imagery on screen in real time, on-device.
- G2. Detect likely manipulated/deepfake imagery and surface it as a confidence signal.
- G3. Intervene with a severity-tiered response (warn → blur+reveal → block) before engagement.
- G4. Prove it runs on the Snapdragon **NPU** via ExecuTorch with visible latency/throughput numbers.
- G5. Guarantee privacy: 100% on-device, no network, frames never persisted.

### Non-Goals (explicitly out of scope for the hackathon)
- N1. Play-Store-grade robustness / policy compliance for system-wide capture.
- N2. Production-grade deepfake *verdicts*. We output a **manipulation likelihood signal**, never a definitive "this is fake."
- N3. Video/audio deepfake analysis, text/misinformation NLP, link scanning. (Misinformation is in the vision narrative only, **not** the MVP build.)
- N4. Cloud fallback, accounts, sync, multi-user, parental dashboards.
- N5. Showing any real explicit or abusive imagery, ever (see §8 Demo & Eval).

---

## 3. Users & Use Cases

**Primary personas**
- A teen/young user who may be sent or shown unsafe imagery.
- A woman targeted by non-consensual intimate imagery or impersonation/deepfakes.
- A guardian who wants a private, on-device shield with no cloud exposure.

**MVP use cases**
1. **Explicit-content shield:** while viewing an image feed/gallery, explicit content is auto-blurred with a consent gate before it's seen.
2. **Manipulation awareness:** a face image likely to be synthetic/altered gets a "possibly manipulated (NN%)" badge before the user trusts/shares it.

---

## 4. Key Decisions (locked)

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| D1 | Capture surface | **Hybrid:** in-app feed (P0, guaranteed) + MediaProjection cross-app overlay (P2, wow) | In-app gives *true* redaction and a demo that can't flake; MediaProjection gives the "everywhere" story as upside, not a single point of failure. |
| D2 | Detection scope | NSFW **and** deepfake, reconciled via a **2-tier cascade** | "Both, per-frame" is only sane if the cheap NSFW gate runs every frame and the heavy, less-reliable deepfake check fires only on candidates. |
| D3 | "Real-time" | Continuous, via cascade + frame throttling (target ~15 fps Tier-1 on 8 Gen 1) | Per-frame *feel* without running both models on every frame. |
| D4 | Intervention | Severity-tiered: warn → blur+reveal → block | Best product story; reversible blur protects trust against false positives. |
| D5 | Backend sequencing | **XNNPACK/CPU first**, then port hot path to **QNN/HTP (NPU)** | Guarantees a working loop Day 1; NPU port produces the headline numbers without "nothing runs" risk. |
| D6 | Models | Pick **export-friendly** open models (export-ability > raw SOTA) | The toolchain (PyTorch → `.pte` → QNN, quantized) is where days die. Architecture is model-agnostic behind a `Detector` interface. |
| D7 | Team | Solo → strict P0/P1/P2 sequencing | At every checkpoint there is a complete demo. |
| D8 | Demo/eval | Synthetic + benign proxies + public deepfake datasets + small offline eval harness | Produces slide numbers and a judge-safe live demo with zero harmful imagery. |

---

## 5. System Architecture

### 5.1 Pipeline (shared by both surfaces)

```
[Frame Source] -> [Preprocess] -> [Tier-1: NSFW gate (per frame, NPU)]
                                        |
                          candidate? (scene change / scroll-stop / ambiguous score)
                                        v
                              [Tier-2: Deepfake/manipulation verifier (triggered)]
                                        |
                                        v
                   [Policy Engine: scores -> severity decision (+ temporal smoothing)]
                                        |
                                        v
                   [Intervention Renderer: badge / blur+reveal / block]
                                        |
                                        v
                              [Telemetry HUD: per-tier ms, fps, backend]
```

### 5.2 Components (clear units, swappable interfaces)

- **`FrameSource`** *(interface)* — yields frames + a "frame changed" hint.
  - `InAppFeedSource` (P0): the currently visible image(s) in our own Compose feed/gallery.
  - `ScreenCaptureSource` (P2): MediaProjection `ImageReader` frames from any app.
- **`Preprocessor`** — downscale to model input (e.g., 224×224), normalize, to tensor. Runs efficiently (GPU/Bitmap path).
- **`Detector`** *(interface)* — wraps an ExecuTorch `Module`; `infer(tensor) -> score(s)`.
  - `NsfwDetector` (Tier-1, per-frame).
  - `DeepfakeDetector` (Tier-2, triggered).
- **`ModelRuntime`** — loads `.pte`, backend-selectable (XNNPACK ↔ QNN/HTP), exposes timing.
- **`CandidateRouter`** — decides when Tier-2 runs (scene change / scroll-stop / Tier-1 ambiguity). MVP trigger = frame-change + (optional) face-present.
- **`PolicyEngine`** — maps scores → `Decision{severity, action, reason}` with tunable thresholds + temporal smoothing (hysteresis) to stop flicker.
- **`InterventionRenderer`** *(interface)* —
  - `ComposeOverlayRenderer` (P0): blur/redact/block inside our app (true pixel control).
  - `WindowOverlayRenderer` (P2): `SYSTEM_ALERT_WINDOW` overlay painting blur rectangles / safety cards on top of other apps.
- **`TelemetryHud`** — on-screen debug overlay: Tier-1 ms, Tier-2 ms, fps, active backend (CPU/NPU), model size. *(The judging gold.)*

### 5.3 The 2-tier cascade (reconciling "both, co-equal, per-frame")

- **Tier-1 — NSFW gate:** small image classifier, runs **every sampled frame** on the NPU. Cheap (target <20 ms). This is what makes "continuous" honest.
- **Tier-2 — Deepfake/manipulation verifier:** compact CNN, runs **only on candidate frames** (changed scene / scroll-stop / contains a face). Output is a **confidence signal**, presented as "possibly manipulated (NN%)", never an absolute verdict.
- Spending the expensive, less-reliable model on every frame would be both slow and misleading; the cascade is the design's core insight.

### 5.4 Performance design
- **Throttling:** capture at device rate; throttle inference to a target fps (process every Nth frame). Tier-2 amortized low because it's triggered.
- **Budget (S22U / 8 Gen 1, NPU):** Tier-1 target <20 ms/frame → ≥15 fps; Tier-2 on candidates only.
- **Temporal smoothing:** rolling decision over N frames so the overlay doesn't strobe on borderline scores.
- **Backend:** start XNNPACK CPU (works everywhere), port Tier-1 to QNN/HTP for numbers; Tier-2 to NPU only if time (P2).

### 5.5 Privacy & data handling
- 100% on-device. **No network calls** in the inference path (demo with airplane mode on).
- MediaProjection / camera / feed frames processed **in memory**, never written to disk, never uploaded.
- No analytics, no telemetry off-device. "Private by construction" is a headline, not a footnote.

---

## 6. Detection & Models

- **NSFW (Tier-1):** an export-friendly small classifier (MobileNetV3 / EfficientNet-lite / small ViT class). Selection criterion: clean PyTorch→ExecuTorch export + quantization, small `.pte`, acceptable accuracy on the eval set. Final weights confirmed Day 0 via an export smoke test.
- **Deepfake (Tier-2):** a compact CNN image deepfake detector (EfficientNet-B0 / Xception-lite class) trained on public face-manipulation data. Output = manipulation likelihood + confidence.
- **Model-agnostic:** both sit behind the `Detector` interface; if a chosen model won't export cleanly, swap it without touching the app.

### Output contract
```
NsfwResult     { nsfw_score: float[0..1], latency_ms }
DeepfakeResult { manip_score: float[0..1], confidence: float[0..1], latency_ms }
```

---

## 7. UX & Intervention Policy

**Severity tiers (tunable at runtime for live demo):**

| Severity | Trigger (default) | Action |
|----------|-------------------|--------|
| None | nsfw < 0.50 and manip < 0.50 | Show normally |
| Low | manip ≥ 0.50 (face) | ⚠ "Possibly manipulated (NN%)" badge, no occlusion |
| Medium | 0.50 ≤ nsfw < 0.85 | **Blur + "Tap to view anyway"** (consent gate) |
| High | nsfw ≥ 0.85 | **Blocked** + safety card ("Hidden to protect you") |

**Principles**
- Blur is **reversible** (tap-to-reveal) at Medium → false positives cost a tap, not trust.
- Copy is supportive and neutral; the "protecting kids/teens/women" framing lives in the pitch and onboarding, **not** stamped on every overlay (avoids stigmatizing/labeling the content or user).
- A settings panel exposes thresholds + tier behavior so we can tune live in front of judges.

---

## 8. Demo & Evaluation Strategy (judge-safe)

**Hard rule:** never display real explicit or abusive imagery. Ever.

**Demo asset buckets**
1. **Benign control:** landscapes, food, objects → show "passes through untouched."
2. **NSFW proxies near threshold:** swimwear / classical art / suggestive-but-SFW imagery chosen to trip the classifier *without* showing harmful content → demonstrates blur/block triggering safely.
3. **Deepfake samples:** faces from **public deepfake research datasets** (e.g., FaceForensics++ / DFDC public samples) → faces only, not explicit, safe to show, demonstrates the manipulation badge.

**Eval harness (offline, small)**
- A script over a small labeled holdout (a few hundred images/bucket) reporting **accuracy / precision / recall** per model, plus **latency per backend** (CPU vs NPU) and **`.pte` size**.
- Output is the numbers slide: *"X ms/frame on Hexagon NPU, Y MB model, P/R on held-out set."*

**Live demo flow**
1. Airplane mode on (privacy proof). 2. Scroll the protected feed → benign passes, NSFW proxy blurs, tap-to-reveal. 3. A face deepfake sample → manipulation badge. 4. Point at the HUD: NPU latency + fps. 5. (P2) Switch to another app via MediaProjection overlay.
**Always record a backup demo video** — never live-demo without a fallback.

---

## 9. Success Metrics

- **M1 (must):** End-to-end on-device loop runs on the S22U with no network. ✅/❌
- **M2 (must):** Tier-1 NSFW visibly intervenes in real time (≥10 fps perceived) on-device.
- **M3 (target):** Tier-1 running on **QNN/HTP NPU** with measured latency shown on HUD.
- **M4 (target):** Deepfake manipulation badge demonstrated on public samples.
- **M5 (target):** Eval numbers (P/R + latency + size) on a slide.
- **M6 (stretch):** Cross-app blur via MediaProjection demonstrated.

---

## 10. Risks & Mitigations (cynical register)

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| ExecuTorch/QNN export & delegation eats a day | High | High | CPU/XNNPACK first (D5); NPU is an *upgrade*, not a dependency. Day-0 export smoke test. |
| Chosen model won't export cleanly | Med | High | `Detector` interface + export-ability as selection criterion (D6); keep a backup model. |
| Deepfake detector misses on stage | High | Med | Present as **confidence signal**, never a verdict (N2); demo on curated public samples. |
| MediaProjection overlay flakes live | Med | Med | It's **P2**; in-app surface (P0) is the guaranteed demo; timebox P2 hard. |
| Per-frame thermals/latency on 8 Gen 1 | Med | Med | Cascade + throttling + smoothing; tune to the canary device. |
| Solo dev runs out of time | High | High | P0/P1/P2 ladder — every checkpoint is demoable; backup video. |
| Showing harmful content | Low | Severe | Proxy/public-dataset-only policy (§8); no real explicit imagery, hard rule. |

---

## 11. Open Questions (track, don't block)

- Final exact model weights (confirmed Day 0 after export smoke test).
- Whether to add a tiny face detector to gate Tier-2 (P1) or rely on frame-change trigger (MVP).
- Pitch framing balance: technical depth vs. emotional problem narrative (currently: lead technical, open emotional).

---

## 12. Appendix — Vision (not in MVP build)

Misinformation/text analysis, video+audio deepfake, link/screenshot scanning, guardian dashboards, and Play-Store-grade system-wide protection are the post-hackathon roadmap. They appear in the *story*, never in the *build scope*.

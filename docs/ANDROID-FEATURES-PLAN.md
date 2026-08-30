# Android On-Device Features — What to Use (and What to Avoid)

Assessment of leveraging built-in Android / on-device capabilities to improve SafeScreen AI, screened
against our two non-negotiables.

## The decision filter (read first)
Anything we add must NOT break either of our strongest cards:
1. **Privacy — no `INTERNET` permission** ("private by construction", airplane-mode demo). This is judging
   **vector #1** and the cloud-*disqualifying* argument.
2. **ExecuTorch on Snapdragon** is the hackathon's core requirement; the detection models stay ExecuTorch.

➡️ **This rules out Google ML Kit for anything core:** its Play-Services dependencies pull `INTERNET`
(+ `ACCESS_NETWORK_STATE`) into the merged manifest, breaking #1. Prefer **ExecuTorch** or
**built-in-offline Android APIs** only. (ML Kit is higher quality, but the privacy cost isn't worth it.)

---

## 1. Face detection → the deepfake FACE-GATING cascade  ⭐ highest value
Realizes the PRD's 2-tier design: run the deepfake model **only on detected faces**. Two wins:
(a) kills the `manip=1.0` false-fire on non-face UI; (b) feeds the FaceForensics++ model **in-distribution
face crops** → directly addresses the ~chance out-of-distribution result (deepfake can move from badge-only
toward a real face-region signal — still "possibly manipulated NN%", never a verdict).

| Option | Quality | Privacy / ExecuTorch fit | Effort |
|---|---|---|---|
| **ExecuTorch BlazeFace** (`.pte`) | good | ✅ best — on-device, no deps, stays ExecuTorch | ~half-day (anchor decode) |
| **`android.media.FaceDetector`** (built into Android) | basic (frontal, upright) | ✅ offline, no Play Services, no `INTERNET` | ~1–2 h |
| ML Kit Face Detection | best | ❌ adds `INTERNET` / Play Services → breaks privacy claim | low |

**Recommendation:** start with **`android.media.FaceDetector`** (fast, dependency-free, offline — enough to
*gate*); upgrade to **BlazeFace `.pte`** only if it's too flaky. Avoid ML Kit.
**Wiring:** add a `CandidateRouter` step in `SafeScreenEngine.analyzeScreen` — detect face(s) on the frame;
if none → skip the deepfake stage entirely; if present → run the deepfake model on the face crop(s).

## 2. "Localization" → region-precise blur
- **Free coarse localization we already have:** the 3-crop max tells us *which region* fired — we can blur
  just that band, or move to a finer grid (e.g. 3×3) for tighter regions. Pure ExecuTorch, no new deps
  (trade-off: more crops = more latency, esp. with the heavier Marqo@384).
- **Caveat — safety vs. precision:** for a safety tool, **whole-screen blur is *safer*** (no partial
  leakage) and more dramatic in the demo. So region-blur is **polish, not a must**.
- Precise pixel masks would need **ML Kit Subject Segmentation** → same `INTERNET` caveat → avoid for now.

## 3. The pixel-capture path
Already solid and on-device: **MediaProjection → ImageReader → ExecuTorch**. No change needed.
(`RenderEffect.createBlurEffect` already powers the overlay blur.)

---

## Verdict / order of work
1. **Face-gated deepfake cascade** — the one clear win. Do it **offline-native** (`android.media.FaceDetector`
   first, BlazeFace `.pte` if needed). Serves the secondary (AI-gen) pillar + the cascade architecture story.
2. **Region-blur** — optional polish from the existing crops; keep whole-screen as the safe default.
3. **Avoid ML Kit / Play Services** entirely — it would cost us the no-`INTERNET` privacy claim, which is our
   strongest differentiator.

Status: discussed 2026-06-27; not yet implemented (device was physically blocked for on-device testing).
Related: `MODEL-RESEARCH-AND-PLAN.md`, the plan file's Phase 3 (face-gated cascade).

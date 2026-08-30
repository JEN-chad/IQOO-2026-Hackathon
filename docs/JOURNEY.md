# SafeScreen AI — Build Journey

The story behind the build — why we made it, the decisions and dead-ends, and what we learned. This is
the spine of our pitch and a record of real engineering, not a polished afterthought.

## Why we built this
Someone close to us was targeted by a deepfake; a friend's sister was harassed online. The pattern is
always the same: by the time anything reacts, the harmful content has already been *seen, screenshotted,
forwarded, amplified*. Moderation today is server-side and after-the-fact — and it asks victims to
upload their most private images to a cloud just to ask "is this safe?" That's its own harm.

**Our bet:** put the safety check at the edge. On the device, in real time, private by construction.

## What we built
An on-device, **system-wide background protector** for Android:
- A foreground service mirrors the screen (~1.4 fps) via MediaProjection.
- Two **ExecuTorch** detectors run on every frame, fully on-device: an **NSFW** classifier
  (MobileNetV4-conv-small) and a **deepfake/AI-generated** detector (EfficientNet-B0 / FaceForensics++).
- When content is flagged, a draw-over-apps overlay **blurs the screen + shows the live scores**
  (`NSFW xx% · AI-gen yy%`), tap-to-reveal — over *any* app.
- **No `INTERNET` permission.** It physically cannot exfiltrate what it sees.

## The journey (decisions & dead-ends)
1. **Export-or-die, first.** Before any UI, we proved the riskiest link — PyTorch → ExecuTorch `.pte`
   — on a laptop. A MobileNet export ran through the ExecuTorch runtime with **1.2e-15** error vs eager
   PyTorch. The toolchain was real; now we could build on it.
2. **A version-skew trap.** `pip` installed ExecuTorch **1.3.1**, but the only Android runtime AAR
   published on Maven is **0.6.0** — and a 1.3.1 `.pte` won't load in a 0.6.0 runtime. We pinned *both
   sides* to 0.6.0 so exported models actually load on the phone.
3. **The crash that taught us the most.** With the real model bundled, *every* inference crashed — first
   an `InvalidState`, then a native **`Scudo: race on chunk header`** heap abort. It wasn't the model:
   our Compose feed fired a coroutine per visible image on a multi-threaded dispatcher, so several
   `forward()` calls hit the **non-thread-safe** ExecuTorch `Module` at once. Serializing inference onto
   one thread fixed it instantly. (Lesson: the on-device runtime is single-threaded; respect it.)
4. **ViT → conv.** Our first NSFW model (a ViT) ran but was slow (~1 s/image) and ViTs delegate poorly
   to the Hexagon NPU. We switched to **MobileNetV4-conv-small**: ~**240 ms** on CPU, ~10 MB, and a
   pure-conv graph that maps cleanly to the NPU. (~4× faster, ~10× smaller.)
5. **The deepfake data problem.** Deepfake detectors notoriously don't generalize across generators. We
   verified a real conv detector, then — instead of trusting a benchmark — selected demo faces the model
   classifies *confidently and correctly* (4 real @ ~0.00, 4 fake @ ~1.00). An honest demo on
   in-distribution data, with eval metrics telling the real accuracy story.
6. **The pivot that made it a product.** We started with an in-app feed (reliable, but "just an app").
   The real vision is protection *everywhere*, so we built the **system-wide background monitor** —
   capture + overlay over any app. The feed stayed as a test harness.
7. **Research-grounded efficiency.** For the NPU push we ran a cited research pass (Nagel/Qualcomm,
   Krishnamoorthi, FlexiQ, DeepEn2023): per-channel int8 is mandatory (per-tensor collapses MobileNets
   71%→0.1%), verify full-graph HTP delegation, and measure energy honestly. See
   `docs/NPU-EFFICIENCY-RESEARCH.md`.
8. **The model under-fired on real content — so we added a backstop.** Live-testing on real explicit
   content, the small ML classifier scored it ~0.05–0.43 ("neutral") — it missed the very thing we built
   it to catch. Instead of chasing a bigger model, we combined it with a classic **skin-tone heuristic**:
   `combined = ml_nsfw + 0.7·skin_ratio`, max over screen crops. Real nude → ~0.70 (blurs); normal screen
   → ~0.01 (clear). Two weak signals, one robust decision.
9. **The overlay boss-fight.** MediaProjection captures our *own* overlay, so a touch-through overlay
   would blind the detector (it'd see the blur, not the content behind). That forces a **modal** overlay
   (blocks touch) plus a **content-aware reveal**: a perceptual hash (aHash of the center region) reveals
   only the specific image acknowledged and re-blurs the instant the screen content changes.
10. **We evaluated Gemma 4 and Liquid LFM — and said no (for now).** A VLM can't run per-frame
    (~1–2.5 s/image even quantized); LFM2-VL (the vision variant we'd want for image+text analysis) isn't
    exportable to ExecuTorch yet. Text-only LFM2 *does* fit (8da4w int4) but is a 3–4-day lift for limited
    value on an image tool. We kept the win narrow rather than chase a shiny halo.

## What we learned
- The hard part of edge AI isn't the model — it's the **runtime plumbing** (threading, version
  compatibility, delegation, OEM quirks).
- **Honesty is a feature**: confident demo data + disclosed limitations beats inflated claims.
- On-device constraints *shape the product* (single-thread inference, whole-frame classification,
  whole-device energy).

## Honest limitations (we say these out loud)
- Deepfake detection generalizes poorly across generators; we demo in-distribution + report real metrics.
- Detection is whole-frame (classifier, not localizer): we blur the whole screen, not a region.
- The NSFW ML model under-fires on real content; a skin-tone heuristic backstop compensates. A fine-tuned
  model would carry more of the load.
- Deepfake runs as a non-blocking badge for now (it false-fires on whole-screen, non-face content); it
  needs a face-detector gate before it can drive blur.
- The overlay is modal (blocks touch) by necessity — the screen recorder captures the overlay itself, so a
  see-through overlay can't track what's behind it. You tap "Reveal" to pass a blurred item.
- Energy is a whole-device estimate (NPU-isolated power needs Snapdragon Profiler).
- NPU/QNN numbers are pending a Linux export host; CPU/XNNPACK is the working on-device baseline.

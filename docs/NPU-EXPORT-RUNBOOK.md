# NPU Export Runbook — QNN / Hexagon HTP (the 40% lever)

## ✅ CURRENT STATE — GCP box `ss-npu` is pre-staged (2026-06-27)
A GCP box (`ss-npu`, e2-standard-8, Ubuntu 22.04, project `safescreenai-500717`, zone `us-central1-a`) is
provisioned and **already has everything except the QNN SDK**:
- **ExecuTorch v0.6.0 built** in `~/executorch/.et` venv (matches our app AAR + QNN 2.31; SM8750 confirmed in
  `QcomChipset`). NDK r26c at `~/android-ndk-r26c`.
- **Both models validated end-to-end** via XNNPACK on the box (`/tmp/nsfw_xnn.pte` 9.98 MB,
  `/tmp/deepfake_xnn.pte` 16 MB, numerically verified) → the model→ExecuTorch path works.
- **48 PTQ calibration images** at `/tmp/calib`. Our `export_qnn.py` + `export_xnnpack.py` are in `~` and
  `/tmp` and are **API-correct for 0.6.0** (`generate_qnn_executorch_compiler_spec`, `QnnPartitioner`,
  `QuantDtype.use_8a8w`). **Run python from `/tmp`, not `~`** (else it imports the source tree → missing
  `program.fbs`).

**REMAINING STEPS (once the QNN SDK is on the box):**
```bash
# 0. SSH:  gcloud compute ssh ss-npu --zone=us-central1-a
export QNN_SDK_ROOT=<unpacked QAIRT 2.31 dir>        # from QPM3 extract
export ANDROID_NDK_ROOT=~/android-ndk-r26c
cd ~/executorch && source .et/bin/activate
bash backends/qualcomm/scripts/build.sh              # builds QNN aot + arm64 device libs
cd /tmp
python export_qnn.py --arch nsfw_mobilenetv4 --soc SM8750 --calib /tmp/calib --out /tmp/nsfw_qnn.pte
python export_qnn.py --arch deepfake_ffpp    --soc SM8750 --calib /tmp/calib --out /tmp/deepfake_qnn.pte
# read the [qnn] delegation summary each prints → want full-graph HTP delegation
```
Then bring `*_qnn.pte` + `qnn_executor_runner` + the V79 QNN libs back to the Mac and profile on the S25
Ultra (§3 below). **SDK gate:** QPM3 (Linux) → `qpm-cli --login` (user) → `--license-activate` → `--extract
qualcomm_ai_engine_direct`.

---


**Goal:** turn the working CPU/XNNPACK models into **int8 QNN .pte** files that run on the Snapdragon
**Hexagon NPU**, then **measure on-device latency + full-graph delegation** to get the numbers that score
the "Technical implementation: NPU utilization / latency / performance / energy" 40%.

**This is LINUX-x86 ONLY.** It will NOT run on the Apple-Silicon Mac. Provision one of:
- a **cloud x86 Linux box** (AWS `c7i`/`m7i`, GCP `c3`, ~8 vCPU, 16 GB, Ubuntu 22.04), or
- the **on-site Linux machine** at the hackathon (the QNN SDK is often pre-installed there).

**The win is the numbers, not full app integration.** Path A below (export + `qnn_executor_runner`
on-device) gives the NPU latency + delegation evidence for the pitch *without* rebuilding the app's AAR.
Do Path B (in-app QNN) only if Path A is done and time remains. **CPU/XNNPACK already works and stays the
fallback** — if any step here flakes, the demo still runs on CPU. Don't gamble the working build.

---

## 0. Pre-flight (5 min)
```bash
uname -m                       # must be x86_64 (Ubuntu 22.04 / WSL2 — NOT the Mac)
nproc; free -g                 # ~8 cores / 16 GB is plenty
g++ --version                  # need g++ 13+
# Two SDKs are required (ask Qualcomm DevRel on-site — they usually have both):
#   QNN  = Qualcomm AI Engine Direct (QAIRT) SDK, v2.37 recommended -> $QNN_SDK_ROOT
#   NDK  = Android NDK r26c+                                        -> $ANDROID_NDK_ROOT
echo "$QNN_SDK_ROOT" "$ANDROID_NDK_ROOT"
```
**Event device = Samsung Galaxy S25 Ultra → target `SM8750` everywhere.** SoC ↔ Hexagon skel lib:
| Device | SoC | `soc_model` | Hexagon | Skel lib |
|---|---|---|---|---|
| **S25 Ultra (EVENT / target)** | 8 Elite | **`SM8750`** | **V79** | **`libQnnHtpV79Skel.so`** |
| S22 Ultra (older dev) | 8 Gen 1 | `SM8450` | V69 | `libQnnHtpV69Skel.so` |

> Use `SM8750` / V79 for the event. (SM8750 is confirmed present in ExecuTorch's `QcomChipset`.)

---

## 1. Build ExecuTorch with the QNN backend (~20–30 min)
The Maven `executorch-android` AAR does **not** ship the licensed QNN runtime libs, and the pip wheel
doesn't include the QNN AOT compiler — so for QNN you build from source. **Key simplification:** because we
build BOTH the AOT exporter and the on-device AAR from the *same* checkout, the `.pte` schema matches by
construction — so use a **recent stable tag (1.x; it has `SM8750`)**, not the old 0.6.0 pin. Keep the working
0.6.0 CPU APK backed up as the fallback.
```bash
git clone https://github.com/pytorch/executorch.git && cd executorch
git checkout latest         # or a 1.x release tag that includes SM8750 in QcomChipset
git submodule sync && git submodule update --init --recursive
python3 -m venv .et && source .et/bin/activate
./install_executorch.sh
# Build the QNN AOT + runtime libs (x86 compiler libs + arm64 device libs):
bash backends/qualcomm/scripts/build.sh         # needs $QNN_SDK_ROOT + $ANDROID_NDK_ROOT
export PYTHONPATH=$PWD:$PYTHONPATH
export LD_LIBRARY_PATH=$QNN_SDK_ROOT/lib/x86_64-linux-clang:$PWD/build-x86/lib:$LD_LIBRARY_PATH
python -c "from executorch.backends.qualcomm.quantizer.quantizer import QnnQuantizer; print('QNN AOT OK')"
```
The last line printing `QNN AOT OK` is the gate — if it imports, the export will work.

> **Fastest export path:** adapt the official `examples/qualcomm/scripts/mobilenet_v2.py` (it wraps the whole
> PTQ→lower→`.pte` flow via `build_executorch_binary`). Point its model loader at our NSFW MobileNetV4 and run
> with `-m SM8750 --compile_only`. This is more robust than hand-rolling the lowering in `tools/export_qnn.py`.

---

## 2. Export the int8 QNN models (~3 min)  → use `tools/export_qnn_all.sh`
Calibration matters: point `--calib` at ~64 **representative** images (any benign images resembling real
input — random calibration costs accuracy). int8 **per-channel** weights are the default in
`export_qnn.py` (per-tensor int8 destroys depthwise-separable nets — see `NPU-EFFICIENCY-RESEARCH.md`).
```bash
cd <repo>/tools
# one command, both models, one SoC:
QNN_SDK_ROOT=$QNN_SDK_ROOT ./export_qnn_all.sh SM8450 ./calib_imgs
# (equivalently, per model:)
python export_qnn.py --arch nsfw_mobilenetv4 --soc SM8450 --calib ./calib_imgs \
    --out ../app/src/main/assets/models/nsfw.pte
python export_qnn.py --arch deepfake_ffpp     --soc SM8450 --calib ./calib_imgs \
    --out ../app/src/main/assets/models/deepfake.pte
```
**Read the delegation summary the script prints.** `full-graph delegation: all nodes on HTP ✔` = ideal.
Any "NON-delegated nodes (CPU fallback)" line = an efficiency leak: those ops run on the slow CPU portable
lib. Note which ops; common offenders are unusual activations or reshapes. Getting to 0 fallback is the
single biggest NPU-efficiency win.

---

## 3. Measure on-device NPU latency (Path A — the pitch numbers) (~10 min)
Use ExecuTorch's `qnn_executor_runner` to run the `.pte` on the phone's NPU and time it — no app rebuild.
```bash
HX=v79                          # S25 Ultra / 8 Elite = Hexagon V79  (S22U would be v69)
DEV=/data/local/tmp/ssnpu; adb shell mkdir -p $DEV
# push the QNN runtime libs for THIS Hexagon arch + the runner + the model:
adb push $QNN_SDK_ROOT/lib/aarch64-android/libQnnHtp.so                $DEV/
adb push $QNN_SDK_ROOT/lib/aarch64-android/libQnnHtpV79Stub.so         $DEV/
adb push $QNN_SDK_ROOT/lib/hexagon-$HX/unsigned/libQnnHtpV79Skel.so    $DEV/
adb push $QNN_SDK_ROOT/lib/aarch64-android/libQnnSystem.so             $DEV/
adb push <executorch>/build-android/.../qnn_executor_runner            $DEV/
adb push ../app/src/main/assets/models/nsfw.pte                        $DEV/
adb shell "cd $DEV && LD_LIBRARY_PATH=$DEV ADSP_LIBRARY_PATH=$DEV \
    ./qnn_executor_runner --model_path nsfw.pte --num_executions 100"
```
Record **avg ms / inference on HTP**. Compare against the CPU/XNNPACK number already in `RESULTS-SO-FAR.md`
(~6–9 ms in-app NSFW) → that ratio is the headline. Re-run with `--soc SM8750` build on the S25U for the
target-device number. For **energy**, run the in-app `Run benchmark` (unplugged) once Path B is wired, or
cite perf/W from the ratio + `NPU-EFFICIENCY-RESEARCH.md` methodology.

---

## 4. (Path B, optional) Run QNN inside the app
Only after Path A. Requires the QNN runtime libs in the app + a QNN-enabled runtime:
1. Build the executorch **Android AAR with QNN** (`scripts/build_android_library.sh` with QNN flags) and
   replace the Maven `org.pytorch:executorch-android:0.6.0` dependency with the local `.aar`.
2. Drop the arm64 QNN libs (`libQnnHtp.so`, `libQnnHtpV69Stub.so`, `libQnnHtpV69Skel.so`, `libQnnSystem.so`,
   `libqnn_executorch_backend.so`) into `app/src/main/jniLibs/arm64-v8a/`.
3. The `.pte` is already QNN-delegated, so `ExecuTorchRuntime` loads it unchanged; the HUD will report the
   backend. Verify `backend: QNN/HTP` shows and latency drops. If it crashes or libs are missing → it falls
   back to CPU (the `degraded` path in `Detector.kt`), so the app never dies.

---

## Fallback ladder (if time runs out)
1. Path A NPU numbers for **NSFW only** (the headline model) — skip deepfake on NPU.
2. If QNN export won't delegate cleanly → report CPU/XNNPACK numbers + the delegation analysis as "NPU
   port in progress, here's the bottleneck op." Honest and still technical.
3. Never cut below the **working CPU/XNNPACK demo**. It already runs real-time on the S22U.

## Time budget
| Step | Time |
|---|---|
| Provision box + SDKs | 15–30 min |
| Build ExecuTorch + QNN | 20–30 min |
| Export both models | 3 min |
| On-device latency (Path A) | 10 min |
| **Total to NPU numbers** | **~1–1.5 h** |
| Path B in-app QNN | +1–2 h |

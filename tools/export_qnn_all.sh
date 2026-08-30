#!/usr/bin/env bash
# Export BOTH SafeScreen models to int8 QNN/HTP .pte for a given Snapdragon SoC. [LINUX-x86 ONLY]
# Prereq: ExecuTorch built with the QNN backend + $QNN_SDK_ROOT set (see docs/NPU-EXPORT-RUNBOOK.md §1).
#
# Usage:  ./export_qnn_all.sh <SOC> [CALIB_DIR]
#   SOC        SM8450 = 8 Gen 1 (S22 Ultra, dev) | SM8750 = 8 Elite (S25 Ultra, target)
#   CALIB_DIR  dir of ~64 representative images for PTQ calibration (recommended; random hurts accuracy)
#
# Example:  ./export_qnn_all.sh SM8450 ./calib_imgs
set -euo pipefail

SOC="${1:?usage: ./export_qnn_all.sh <SM8450|SM8750> [calib_dir]}"
CALIB="${2:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$HERE/../app/src/main/assets/models"
PY="${PYTHON:-python3}"

: "${QNN_SDK_ROOT:?QNN_SDK_ROOT not set — install the Qualcomm AI Engine Direct SDK (Linux x86).}"
mkdir -p "$ASSETS"

calib_arg=()
[ -n "$CALIB" ] && calib_arg=(--calib "$CALIB")

echo "== [1/2] NSFW (MobileNetV4-conv-small) -> nsfw.pte  [$SOC] =="
"$PY" "$HERE/export_qnn.py" --arch nsfw_mobilenetv4 --soc "$SOC" "${calib_arg[@]}" \
    --out "$ASSETS/nsfw.pte"

echo "== [2/2] Deepfake (EfficientNet-B0 FF++) -> deepfake.pte  [$SOC] =="
"$PY" "$HERE/export_qnn.py" --arch deepfake_ffpp --soc "$SOC" "${calib_arg[@]}" \
    --out "$ASSETS/deepfake.pte"

echo
echo "Done. Models written to $ASSETS for $SOC."
echo "Re-read each [qnn] delegation summary above: 'all nodes on HTP ✔' = ideal; any NON-delegated"
echo "node = CPU fallback (efficiency leak). Then measure on-device latency — docs/NPU-EXPORT-RUNBOOK.md §3."

#!/usr/bin/env bash
# Bootstrap a fresh Ubuntu 22.04 x86_64 cloud box to compile ExecuTorch models for the Snapdragon
# Hexagon NPU (QNN backend). Run this on the CLOUD box, not the Mac. ~30-45 min.
#
# Recommended instance: AWS c7i.2xlarge / GCP c3-standard-8 (8 vCPU, 16 GB), 40 GB disk, Ubuntu 22.04.
#
# PREREQ you must provide once (needs a Qualcomm account / or grab from on-site DevRel):
#   - Qualcomm AI Engine Direct (QAIRT/QNN) SDK v2.37, unpacked, with $QNN_SDK_ROOT exported.
#     Download: https://qpm.qualcomm.com/  (Qualcomm AI Engine Direct SDK)  OR ask Qualcomm DevRel.
#
# Usage:
#   export QNN_SDK_ROOT=/opt/qairt/2.37.x.xxxxxx        # set to your unpacked SDK
#   bash setup_qnn_cloud.sh
set -euo pipefail

[ "$(uname -m)" = "x86_64" ] || { echo "ERROR: must run on x86_64 Linux (not Mac/ARM)."; exit 1; }
: "${QNN_SDK_ROOT:?Set QNN_SDK_ROOT to your unpacked QAIRT/QNN SDK 2.37 first (see header).}"

echo "== [1/5] system deps =="
sudo apt-get update -y
sudo apt-get install -y build-essential g++-13 cmake ninja-build git git-lfs \
    python3.11 python3.11-venv python3-pip unzip wget libc++-dev

echo "== [2/5] Android NDK r26c =="
NDK=android-ndk-r26c
if [ ! -d "$HOME/$NDK" ]; then
  wget -q "https://dl.google.com/android/repository/${NDK}-linux.zip" -O /tmp/ndk.zip
  unzip -q /tmp/ndk.zip -d "$HOME"
fi
export ANDROID_NDK_ROOT="$HOME/$NDK"

echo "== [3/5] clone + build ExecuTorch (1.x, has SM8750) =="
[ -d executorch ] || git clone https://github.com/pytorch/executorch.git
cd executorch
# Default branch (main) has SM8750 + the current QNN API. To pin a stable release instead, uncomment:
#   git checkout v1.0.0    # (or the latest release tag from github.com/pytorch/executorch/releases)
git submodule sync && git submodule update --init --recursive
python3.11 -m venv .et && source .et/bin/activate
pip install --upgrade pip
./install_executorch.sh

echo "== [4/5] build the QNN backend (AOT x86 libs + arm64 device libs) =="
bash backends/qualcomm/scripts/build.sh
export PYTHONPATH="$PWD:$PYTHONPATH"
export LD_LIBRARY_PATH="$QNN_SDK_ROOT/lib/x86_64-linux-clang:$PWD/build-x86/lib:${LD_LIBRARY_PATH:-}"

echo "== [5/5] verify QNN AOT import =="
python -c "from executorch.backends.qualcomm.quantizer.quantizer import QnnQuantizer; print('QNN AOT OK')"

cat <<EOF

✅ Ready. Environment for this shell:
   export QNN_SDK_ROOT=$QNN_SDK_ROOT
   export ANDROID_NDK_ROOT=$ANDROID_NDK_ROOT
   export PYTHONPATH=$PWD:\$PYTHONPATH
   export LD_LIBRARY_PATH=$QNN_SDK_ROOT/lib/x86_64-linux-clang:$PWD/build-x86/lib:\$LD_LIBRARY_PATH

Next (fastest path — adapt the official example for our NSFW conv model):
  # copy our model loader / .pt2 here, then:
  python -m examples.qualcomm.scripts.mobilenet_v2 -b build-android -m SM8750 --compile_only
  # -> produces a QNN-delegated .pte for the Hexagon V79 (8 Elite).
Then push to the S25 Ultra and profile (see docs/NPU-EXPORT-RUNBOOK.md §3 — qnn_executor_runner).
EOF

#!/usr/bin/env python3
"""Export to ExecuTorch .pte for the Qualcomm QNN / Hexagon (HTP) NPU.  [LINUX-x86 ONLY]

Implements the research-backed efficiency recipe (see docs/NPU-EFFICIENCY-RESEARCH.md):
  - int8 PER-CHANNEL symmetric weights (QnnQuantizer default 8a8w) via PT2E PTQ + calibration.
    (Per-tensor int8 catastrophically breaks depthwise-separable nets — MobileNetV2 71.7% -> 0.1%;
     per-channel keeps within ~1-2% of FP32. Nagel 2019/2021; Krishnamoorthi 2018.)
  - SoC-specific build (soc_model fixed at AOT): SM8450 = 8 Gen 1 (S22U), SM8750 = 8 Elite (S25U).
  - FULL-GRAPH delegation verification via get_delegation_info() -> fail loud on CPU fallback,
    since every non-delegated op runs on the slow CPU portable library.

Run on a Linux x86 host with the Qualcomm AI Engine Direct (QNN) SDK installed ($QNN_SDK_ROOT) and
executorch matching the Android AAR (0.6.0). NOTE: confirm exact API symbols against the installed
executorch on the box — they shift between releases.

Examples:
  python export_qnn.py --arch nsfw_mobilenetv4 --soc SM8450 --calib ./calib_imgs \
      --out ../app/src/main/assets/models/nsfw.pte
"""
import argparse
import glob
import os

import torch

from export_xnnpack import load  # reuse the same model loaders

IMAGENET_MEAN = [0.485, 0.456, 0.406]
IMAGENET_STD = [0.229, 0.224, 0.225]


def calib_tensors(calib_dir: str, size: int, n: int = 64):
    """Representative inputs for PTQ calibration (real images >> random for accuracy)."""
    paths = sorted(glob.glob(os.path.join(calib_dir, "*"))) if calib_dir else []
    if not paths:
        print("[qnn] WARNING: no --calib images; using random calibration (accuracy WILL suffer). "
              "Point --calib at representative images for real PTQ.")
        return [(torch.randn(1, 3, size, size),) for _ in range(8)]
    from PIL import Image
    out = []
    for p in paths[:n]:
        try:
            img = Image.open(p).convert("RGB").resize((size, size))
            t = torch.tensor(list(img.getdata()), dtype=torch.float32).reshape(size, size, 3) / 255.0
            t = (t - torch.tensor(IMAGENET_MEAN)) / torch.tensor(IMAGENET_STD)
            out.append((t.permute(2, 0, 1).unsqueeze(0),))
        except Exception:  # noqa: BLE001
            pass
    print(f"[qnn] calibration set: {len(out)} images")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arch", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--soc", default="SM8450", help="SM8450=8 Gen 1 (S22U), SM8750=8 Elite (S25U)")
    ap.add_argument("--calib", default="", help="dir of representative images for PTQ calibration")
    ap.add_argument("--size", type=int, default=0)
    args = ap.parse_args()

    if not os.environ.get("QNN_SDK_ROOT"):
        raise SystemExit("QNN_SDK_ROOT not set — install the Qualcomm AI Engine Direct SDK (Linux x86).")

    model, size = load(args.arch)
    if args.size:
        size = args.size
    example = (torch.randn(1, 3, size, size),)

    from torch.export import export
    from torch.ao.quantization.quantize_pt2e import convert_pt2e, prepare_pt2e
    from executorch.backends.qualcomm.quantizer.quantizer import QnnQuantizer, QuantDtype
    from executorch.backends.qualcomm.partition.qnn_partitioner import QnnPartitioner
    from executorch.backends.qualcomm.utils.utils import (
        QcomChipset,
        generate_htp_compiler_spec,
        generate_qnn_executorch_compiler_spec,
    )
    from executorch.exir import to_edge_transform_and_lower

    # --- PT2E post-training quantization: int8 8a8w, per-channel weights (QnnQuantizer default) ---
    captured = export(model, example).module()
    quantizer = QnnQuantizer()
    quantizer.set_default_quant_config(QuantDtype.use_8a8w)  # int8; per-channel symmetric conv weights
    prepared = prepare_pt2e(captured, quantizer)
    for t in calib_tensors(args.calib, size):
        prepared(*t)
    quantized = convert_pt2e(prepared)
    print("[qnn] PTQ complete (8a8w, per-channel weights)")

    # --- Lower to QNN/HTP for the target SoC (SoC is fixed at compile time) ---
    htp = generate_htp_compiler_spec(use_fp16=False)
    spec = generate_qnn_executorch_compiler_spec(
        soc_model=getattr(QcomChipset, args.soc), backend_options=htp,
    )
    edge = to_edge_transform_and_lower(export(quantized, example), partitioner=[QnnPartitioner(spec)])

    # --- Verify FULL-GRAPH delegation: any non-delegated op = CPU fallback = efficiency leak ---
    try:
        from executorch.devtools.backend_debug import get_delegation_info
        info = get_delegation_info(edge.exported_program().graph_module)
        print("[qnn] delegation summary:\n", info.get_summary())
        non_delegated = getattr(info, "num_non_delegated_nodes", None)
        if non_delegated:
            print(f"[qnn] !!! {non_delegated} NON-delegated nodes (CPU fallback) — inspect "
                  "get_operator_delegation_dataframe() and remove/replace them for max NPU efficiency.")
        else:
            print("[qnn] full-graph delegation: all nodes on HTP ✔")
    except Exception as e:  # noqa: BLE001
        print("[qnn] delegation check skipped:", e)

    prog = edge.to_executorch()
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "wb") as f:
        try:
            prog.write_to_file(f)
        except AttributeError:
            f.write(prog.buffer)
    print(f"[qnn] wrote {args.out} ({os.path.getsize(args.out) / 1e6:.2f} MB) for {args.soc}")


if __name__ == "__main__":
    main()

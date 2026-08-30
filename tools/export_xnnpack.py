#!/usr/bin/env python3
"""Export an image classifier to an ExecuTorch .pte using the XNNPACK (CPU) backend.

Runs on macOS/Linux. Pin ExecuTorch to the same version as the Android AAR (0.6.0) so the
resulting .pte loads on-device — see tools/requirements.txt.

Examples
--------
  python export_xnnpack.py --arch nsfw_marqo \
      --out ../app/src/main/assets/models/nsfw.pte
  python export_xnnpack.py --arch deepfake_dima806 \
      --out ../app/src/main/assets/models/deepfake.pte
  python export_xnnpack.py --arch torchvision:efficientnet_b0 --size 224 --out /tmp/effb0.pte
"""
import argparse
import os

import torch
import torch.nn as nn


class LogitsWrapper(nn.Module):
    """Normalizes model output to a plain logits tensor (torch.export needs tensor outputs)."""

    def __init__(self, model: nn.Module, hf: bool = False):
        super().__init__()
        self.model = model
        self.hf = hf

    def forward(self, x):
        out = self.model(x)
        return out.logits if self.hf else out


def load(arch: str):
    """Returns (model, input_size). Models output 2 logits: index 1 = positive class."""
    if arch == "nsfw_mobilenetv4":
        # MobileNetV4 conv-small NSFW (2.4M params, ~10MB, 5 classes). Pure-conv -> HTP-friendly.
        import timm
        m = timm.create_model(
            "hf_hub:taufiqdp/mobilenetv4_conv_small.e2400_r224_in1k_nsfw_classifier",
            pretrained=True,
        ).eval()
        return LogitsWrapper(m), 224
    if arch == "nsfw_marqo":
        import timm
        m = timm.create_model("hf_hub:Marqo/nsfw-image-detection-384", pretrained=True).eval()
        return LogitsWrapper(m), 384
    if arch == "deepfake_ffpp":
        # Xicor9 EfficientNet-B0 trained on FaceForensics++ C23 (conv, ~21MB). Class 1 = fake.
        import torch.nn as nn
        from torchvision import models
        sd = torch.hub.load_state_dict_from_url(
            "https://huggingface.co/Xicor9/efficientnet-b0-ffpp-c23/resolve/main/efficientnet_b0_ffpp_c23.pth",
            map_location="cpu", progress=False,
        )
        m = models.efficientnet_b0(weights=None)
        m.classifier[1] = nn.Linear(m.classifier[1].in_features, 2)
        m.load_state_dict(sd)
        return LogitsWrapper(m.eval()), 224
    if arch == "deepfake_dima806":
        from transformers import AutoModelForImageClassification
        m = AutoModelForImageClassification.from_pretrained(
            "dima806/deepfake_vs_real_image_detection"
        ).eval()
        return LogitsWrapper(m, hf=True), 224
    if arch.startswith("torchvision:"):
        import torchvision
        name = arch.split(":", 1)[1]
        m = getattr(torchvision.models, name)(weights=None).eval()
        return LogitsWrapper(m), 224
    raise SystemExit(f"unknown arch: {arch}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arch", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--size", type=int, default=0, help="override input size")
    ap.add_argument("--backend", choices=["xnnpack", "portable"], default="xnnpack",
                    help="portable = no delegate (slower, but avoids AAR delegate mismatch)")
    args = ap.parse_args()

    model, size = load(args.arch)
    if args.size:
        size = args.size
    example = torch.randn(1, 3, size, size)

    with torch.no_grad():
        ref = model(example)
    print(f"[export] arch={args.arch} input=1x3x{size}x{size} output={tuple(ref.shape)}")

    from torch.export import export
    ep = export(model, (example,))

    if args.backend == "xnnpack":
        from executorch.exir import to_edge_transform_and_lower
        from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
        edge = to_edge_transform_and_lower(ep, partitioner=[XnnpackPartitioner()])
    else:  # portable: no delegate, runs on the AAR's built-in portable kernels
        from executorch.exir import to_edge
        edge = to_edge(ep)
    prog = edge.to_executorch()

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "wb") as f:
        try:
            prog.write_to_file(f)
        except AttributeError:
            f.write(prog.buffer)
    print(f"[export] wrote {args.out} ({os.path.getsize(args.out) / 1e6:.2f} MB)")

    _verify(args.out, model, example)


def _verify(pte_path: str, model: nn.Module, example: torch.Tensor):
    """Sanity-check that .pte output matches eager output (catches export/quant breakage)."""
    try:
        from executorch.runtime import Runtime
        rt = Runtime.get()
        method = rt.load_program(pte_path).load_method("forward")
        out = method.execute([example])[0]
        with torch.no_grad():
            ref = model(example)
        diff = (torch.as_tensor(out) - ref).abs().max().item()
        print(f"[verify] max abs diff (eager vs .pte) = {diff:.4e}  {'OK' if diff < 1e-2 else 'CHECK!'}")
    except Exception as e:  # noqa: BLE001
        print(f"[verify] skipped ({e})")


if __name__ == "__main__":
    main()

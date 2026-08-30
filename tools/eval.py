#!/usr/bin/env python3
"""Offline eval: precision/recall + host latency + model size, for the numbers slide.

Expects an ImageFolder layout with two class subdirs, e.g.:
  data/nsfw/   (positive class)
  data/safe/   (negative class)

Example:
  python eval.py --arch nsfw_marqo --data ./data --positive nsfw
"""
import argparse
import os
import time

import torch
from PIL import Image

from export_xnnpack import load

IMAGENET_MEAN = [0.485, 0.456, 0.406]
IMAGENET_STD = [0.229, 0.224, 0.225]


def preprocess(path: str, size: int) -> torch.Tensor:
    img = Image.open(path).convert("RGB").resize((size, size))
    t = torch.tensor(list(img.getdata()), dtype=torch.float32).reshape(size, size, 3) / 255.0
    t = (t - torch.tensor(IMAGENET_MEAN)) / torch.tensor(IMAGENET_STD)
    return t.permute(2, 0, 1).unsqueeze(0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arch", required=True)
    ap.add_argument("--data", required=True, help="ImageFolder root with class subdirs")
    ap.add_argument("--positive", required=True, help="subdir name of the positive class")
    ap.add_argument("--size", type=int, default=0)
    ap.add_argument("--threshold", type=float, default=0.5)
    ap.add_argument("--pte", default="", help="optional .pte to report on-disk size")
    args = ap.parse_args()

    model, size = load(args.arch)
    if args.size:
        size = args.size

    tp = fp = tn = fn = 0
    latencies = []
    classes = [d for d in sorted(os.listdir(args.data)) if os.path.isdir(os.path.join(args.data, d))]
    for cls in classes:
        is_pos = cls == args.positive
        folder = os.path.join(args.data, cls)
        for name in os.listdir(folder):
            if name.startswith("."):
                continue
            x = preprocess(os.path.join(folder, name), size)
            t0 = time.perf_counter()
            with torch.no_grad():
                logits = model(x)
            latencies.append((time.perf_counter() - t0) * 1000)
            prob = torch.softmax(logits, dim=1)[0, 1].item()
            pred_pos = prob >= args.threshold
            if pred_pos and is_pos:
                tp += 1
            elif pred_pos and not is_pos:
                fp += 1
            elif not pred_pos and is_pos:
                fn += 1
            else:
                tn += 1

    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
    acc = (tp + tn) / max(1, tp + tn + fp + fn)
    lat = sorted(latencies)
    p50 = lat[len(lat) // 2] if lat else 0.0

    print(f"\n=== {args.arch} @ thr={args.threshold} ===")
    print(f"n={tp + tn + fp + fn}  acc={acc:.3f}  precision={precision:.3f}  recall={recall:.3f}  f1={f1:.3f}")
    print(f"host latency p50={p50:.1f} ms (CPU eager; on-device NPU is the real number — see HUD)")
    if args.pte and os.path.exists(args.pte):
        print(f".pte size: {os.path.getsize(args.pte) / 1e6:.2f} MB")


if __name__ == "__main__":
    main()

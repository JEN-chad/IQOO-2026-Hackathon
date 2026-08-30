#!/usr/bin/env python3
"""Score a local image through the NSFW (and optionally deepfake) model — for isolating model
capability vs the on-device capture pipeline. Runs entirely locally; nothing is uploaded.

Usage:
  tools/.venv/bin/python tools/score_image.py /path/to/image.jpg
"""
import sys

import torch
import timm
from torchvision import transforms
from PIL import Image

if len(sys.argv) < 2:
    raise SystemExit("usage: score_image.py <image-path>")

m = timm.create_model(
    "hf_hub:taufiqdp/mobilenetv4_conv_small.e2400_r224_in1k_nsfw_classifier", pretrained=True
).eval()
tf = transforms.Compose([
    transforms.Resize((224, 224)),
    transforms.ToTensor(),
    transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
])
classes = ["drawings", "hentai", "neutral", "porn", "sexy"]

x = tf(Image.open(sys.argv[1]).convert("RGB")).unsqueeze(0)
with torch.no_grad():
    p = torch.softmax(m(x), dim=1)[0]

print(f"\n{sys.argv[1]}")
for c, pr in sorted(zip(classes, p.tolist()), key=lambda t: -t[1]):
    print(f"  {c:10s} {pr:.3f}")
print(f"NSFW (hentai+porn+sexy) = {(p[1] + p[3] + p[4]).item():.3f}")

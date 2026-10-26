"""Load the locally downloaded DFormerv2 NYUv2 checkpoint and run one GPU inference."""

from __future__ import annotations

import argparse
import importlib
import json
import sys
import time
from pathlib import Path

import torch


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--height", type=int, default=480)
    parser.add_argument("--width", type=int, default=640)
    parser.add_argument("--warmup", type=int, default=2)
    parser.add_argument("--iterations", type=int, default=5)
    args = parser.parse_args()

    source = args.source.resolve()
    weights = args.weights.resolve()
    if not source.is_dir():
        raise FileNotFoundError(f"DFormerv2 source not found: {source}")
    if not weights.is_file():
        raise FileNotFoundError(f"DFormerv2 weights not found: {weights}")

    sys.path.insert(0, str(source))
    config = importlib.import_module("local_configs.NYUDepthv2.DFormerv2_S").C
    # The NYUv2 task checkpoint already contains the complete encoder and decoder.
    # Avoid trying to load the separate ImageNet pretraining checkpoint first.
    config.pretrained_model = None

    from models.builder import EncoderDecoder

    started = time.perf_counter()
    model = EncoderDecoder(cfg=config, norm_layer=torch.nn.BatchNorm2d, syncbn=False)
    checkpoint = torch.load(weights, map_location="cpu", weights_only=False)
    state_dict = checkpoint.get("model", checkpoint.get("state_dict", checkpoint))
    incompatible = model.load_state_dict(state_dict, strict=False)
    load_seconds = time.perf_counter() - started

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.eval().to(device)
    rgb = torch.randn(1, 3, args.height, args.width, device=device)
    depth = torch.randn(1, 3, args.height, args.width, device=device)
    def infer() -> torch.Tensor:
        with torch.inference_mode(), torch.autocast(
            device_type=device.type,
            dtype=torch.float16 if device.type == "cuda" else torch.bfloat16,
            enabled=device.type == "cuda",
        ):
            return model(rgb, depth)

    for _ in range(args.warmup):
        logits = infer()
    if device.type == "cuda":
        torch.cuda.synchronize()
        torch.cuda.reset_peak_memory_stats()
    inference_times = []
    for _ in range(args.iterations):
        started = time.perf_counter()
        logits = infer()
        if device.type == "cuda":
            torch.cuda.synchronize()
        inference_times.append((time.perf_counter() - started) * 1000)

    result = {
        "python": sys.version.split()[0],
        "torch": torch.__version__,
        "device": str(device),
        "gpu": torch.cuda.get_device_name(0) if device.type == "cuda" else None,
        "weightsBytes": weights.stat().st_size,
        "loadSeconds": round(load_seconds, 3),
        "inferenceMilliseconds": {
            "minimum": round(min(inference_times), 1),
            "average": round(sum(inference_times) / len(inference_times), 1),
            "maximum": round(max(inference_times), 1),
            "iterations": len(inference_times),
        },
        "peakGpuMegabytes": round(torch.cuda.max_memory_allocated() / 1048576, 1)
        if device.type == "cuda"
        else None,
        "outputShape": list(logits.shape),
        "finite": bool(torch.isfinite(logits).all().item()),
        "missingKeys": list(incompatible.missing_keys),
        "unexpectedKeys": list(incompatible.unexpected_keys),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

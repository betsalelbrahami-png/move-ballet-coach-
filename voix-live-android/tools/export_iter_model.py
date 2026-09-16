#!/usr/bin/env python3
"""Download iter_model and export the slower, higher-context mobile variant."""

import argparse
from pathlib import Path
import sys
import time

from huggingface_hub import snapshot_download
import hydra
import numpy as np
import onnx
import onnxruntime as ort
from omegaconf import OmegaConf
import torch


FILES = [
    "ckpt/3_loss_post.pt.tar",
    "config/config_ira.yaml",
    "model/cnns.py",
    "model/norm.py",
    "model/spex_plus.py",
    "model/spex_plus_plus.py",
]
MIX_SAMPLES = 64_000
ENROLLMENT_SAMPLES = 160_000


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    source = None
    for attempt in range(4):
        try:
            source = Path(snapshot_download(
                repo_id="swc2/Target-speaker-extraction",
                repo_type="space",
                allow_patterns=FILES,
            ))
            break
        except Exception:
            if attempt == 3:
                raise
            time.sleep(10 * (attempt + 1))
    assert source is not None
    sys.path.insert(0, str(source))
    cfg = OmegaConf.load(source / "config/config_ira.yaml")
    cfg.test.checkpoint = str(source / "ckpt/3_loss_post.pt.tar")
    model = hydra.utils.instantiate(cfg.model)
    checkpoint = torch.load(cfg.test.checkpoint, map_location="cpu", weights_only=False)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()

    torch.manual_seed(41)
    mixture = torch.randn(1, MIX_SAMPLES, dtype=torch.float32) * 0.03
    enrollment = torch.randn(1, ENROLLMENT_SAMPLES, dtype=torch.float32) * 0.03
    enrollment_length = torch.tensor([ENROLLMENT_SAMPLES], dtype=torch.float32)
    with torch.inference_mode():
        expected = model(mixture, enrollment, enrollment_length).numpy()
        torch.onnx.export(
            model,
            (mixture, enrollment, enrollment_length),
            output,
            input_names=["mixture", "enrollment", "enrollment_length"],
            output_names=["target_voice"],
            opset_version=17,
            do_constant_folding=True,
        )

    graph = onnx.load(output)
    onnx.checker.check_model(graph)
    session = ort.InferenceSession(str(output), providers=["CPUExecutionProvider"])
    started = time.perf_counter()
    actual = session.run(None, {
        "mixture": mixture.numpy(),
        "enrollment": enrollment.numpy(),
        "enrollment_length": enrollment_length.numpy(),
    })[0]
    elapsed = time.perf_counter() - started
    cosine = float(np.dot(expected.ravel(), actual.ravel()) /
                   (np.linalg.norm(expected) * np.linalg.norm(actual) + 1e-12))
    relative_error = float(np.max(np.abs(expected - actual)) /
                           (np.max(np.abs(expected)) + 1e-12))
    print(f"ONNX size={output.stat().st_size / 1024 / 1024:.1f} MiB ")
    print(f"validation inference={elapsed:.3f}s cosine={cosine:.8f} relative_error={relative_error:.8f}")
    # InstanceNorm accumulates small backend-dependent rounding differences.
    # The waveform direction must stay virtually identical; a 2% peak bound
    # is intentionally stricter than an audible regression for this signal.
    if cosine < 0.999 or relative_error > 0.02:
        raise RuntimeError("The ONNX conversion did not preserve the model output")


if __name__ == "__main__":
    main()

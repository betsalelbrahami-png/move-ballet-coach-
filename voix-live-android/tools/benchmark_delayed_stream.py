#!/usr/bin/env python3
"""Check the Android delayed-stream DSP with the exact exported iter_model.

The script is intentionally independent from Android. It runs two-second ONNX
windows with a one-second hop, keeps the stable centre second, and measures how
much target/interferer energy remains in the concatenated stream.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import time

import librosa
import numpy as np
import onnxruntime as ort
import soundfile as sf


SAMPLE_RATE = 16_000
WINDOW = 32_000
HOP = 16_000
CENTRE = 8_000
ENROLLMENT = 64_000
MAX_LAG = 1_920


def load(path: Path) -> np.ndarray:
    audio, _ = librosa.load(path, sr=SAMPLE_RATE, mono=True)
    return np.asarray(audio, dtype=np.float32)


def fixed_length(audio: np.ndarray, length: int) -> np.ndarray:
    if len(audio) >= length:
        return audio[:length]
    return np.pad(audio, (0, length - len(audio)))


def best_lag(mixture: np.ndarray, target: np.ndarray) -> int:
    stride = SAMPLE_RATE // 1_000
    maximum = MAX_LAG // stride
    count = min(len(mixture) // stride, len(target) // stride)
    best = 0
    best_score = -math.inf
    for lag in range(-maximum, maximum + 1):
        start = max(0, -lag)
        end = min(count, count - lag)
        x = mixture[start * stride : end * stride : 2 * stride]
        y = target[(start + lag) * stride : (end + lag) * stride : 2 * stride]
        score = abs(float(np.dot(x, y))) / math.sqrt(
            float(np.dot(x, x) * np.dot(y, y)) + 1e-12
        )
        if score > best_score:
            best_score = score
            best = lag
    return best * stride


def subtract_aligned(mixture: np.ndarray, target: np.ndarray) -> tuple[np.ndarray, int, float]:
    lag = best_lag(mixture, target)
    if lag >= 0:
        x, y = mixture[: len(mixture) - lag], target[lag:]
    else:
        x, y = mixture[-lag:], target[: len(target) + lag]
    gain = float(np.dot(x, y) / (np.dot(y, y) + 1e-9))
    gain = float(np.clip(gain, 0.0, 3.0))
    aligned = np.zeros_like(mixture)
    if lag >= 0:
        aligned[: len(mixture) - lag] = target[lag:]
    else:
        aligned[-lag:] = target[: len(target) + lag]
    return mixture - gain * aligned, lag, gain


def coefficients(signal: np.ndarray, target: np.ndarray, other: np.ndarray) -> tuple[float, float]:
    matrix = np.column_stack([target, other]).astype(np.float64)
    values, *_ = np.linalg.lstsq(matrix, signal.astype(np.float64), rcond=None)
    return float(values[0]), float(values[1])


def db(after: float, before: float) -> float:
    return 20 * math.log10((abs(after) + 1e-9) / (abs(before) + 1e-9))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--mixture", required=True, type=Path)
    parser.add_argument("--enrollment", required=True, type=Path)
    parser.add_argument("--target", required=True, type=Path)
    parser.add_argument("--other", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--repeat", type=int, default=1,
                        help="Repeat the controlled mixture to benchmark sustained streaming")
    args = parser.parse_args()

    mixture = load(args.mixture)
    target = load(args.target)
    other = load(args.other)
    length = min(len(mixture), len(target), len(other))
    mixture = np.tile(mixture[:length], max(1, args.repeat))
    target = np.tile(target[:length], max(1, args.repeat))
    other = np.tile(other[:length], max(1, args.repeat))
    length = min(len(mixture), len(target), len(other))
    length = max(WINDOW, length - (length % HOP))
    mixture = fixed_length(mixture, length)
    target = fixed_length(target, length)
    other = fixed_length(other, length)
    enrollment = fixed_length(load(args.enrollment), ENROLLMENT)[None, :]

    session = ort.InferenceSession(str(args.model), providers=["CPUExecutionProvider"])
    chunks: list[np.ndarray] = []
    timings: list[float] = []
    lags: list[int] = []
    gains: list[float] = []
    starts = range(0, length - WINDOW + 1, HOP)
    for start in starts:
        window = mixture[start : start + WINDOW]
        begun = time.perf_counter()
        extracted = session.run(None, {
            "mixture": window[None, :],
            "enrollment": enrollment,
            "enrollment_length": np.asarray([ENROLLMENT], dtype=np.float32),
        })[0][0]
        residual, lag, gain = subtract_aligned(window, extracted)
        timings.append((time.perf_counter() - begun) * 1_000)
        lags.append(lag)
        gains.append(gain)
        chunks.append(residual[CENTRE : CENTRE + HOP])

    output = np.concatenate(chunks)
    source_start = CENTRE
    source_end = source_start + len(output)
    source_mix = mixture[source_start:source_end]
    source_target = target[source_start:source_end]
    source_other = other[source_start:source_end]
    before_target, before_other = coefficients(source_mix, source_target, source_other)
    after_target, after_other = coefficients(output, source_target, source_other)
    report = {
        "windows": len(chunks),
        "compute_median_ms": round(float(np.median(timings)), 2),
        "compute_p95_ms": round(float(np.percentile(timings, 95)), 2),
        "target_change_db": round(db(after_target, before_target), 2),
        "other_change_db": round(db(after_other, before_other), 2),
        "alignment_ms": [round(value * 1_000 / SAMPLE_RATE, 1) for value in lags],
        "gains": [round(value, 3) for value in gains],
    }
    print(json.dumps(report, indent=2))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        sf.write(args.output, np.clip(output, -1, 1), SAMPLE_RATE)


if __name__ == "__main__":
    main()

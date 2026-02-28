#!/usr/bin/env python3
"""
Validate benchmark results against lightweight CI budgets.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


THROUGHPUT_MIN_MBPS = {
    "1MB": 5.0,
    "5MB": 5.0,
    "10MB": 5.0,
    "25MB": 5.0,
}

FILEPATHS_MAX_SECONDS = {
    "5000": 2.5,
    "20000": 6.0,
    "50000": 12.0,
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check benchmark results against budgets.")
    parser.add_argument("result_file", type=Path, help="Path to benchmark_*.txt result file")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.result_file.exists():
        print(f"ERROR: Result file not found: {args.result_file}", file=sys.stderr)
        return 2

    content = args.result_file.read_text(encoding="utf-8", errors="replace")
    failures: list[str] = []

    for size, min_mbps in THROUGHPUT_MIN_MBPS.items():
        pattern = re.compile(rf"^{re.escape(size)}:\s+([0-9.]+)\s+MB/s", re.MULTILINE)
        match = pattern.search(content)
        if not match:
            failures.append(f"Missing throughput result for {size}")
            continue
        actual = float(match.group(1))
        if actual < min_mbps:
            failures.append(f"Throughput {size} below budget: {actual:.2f} < {min_mbps:.2f} MB/s")

    for lines, max_seconds in FILEPATHS_MAX_SECONDS.items():
        pattern = re.compile(rf"^{re.escape(lines)} path lines:\s+([0-9.]+)s", re.MULTILINE)
        match = pattern.search(content)
        if not match:
            failures.append(f"Missing filepath result for {lines} lines")
            continue
        actual = float(match.group(1))
        if actual > max_seconds:
            failures.append(f"Filepath benchmark {lines} lines above budget: {actual:.3f}s > {max_seconds:.3f}s")

    if failures:
        print("Benchmark budget check FAILED:")
        for failure in failures:
            print(f"- {failure}")
        return 1

    print("Benchmark budget check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Turns two cgroup cpu.stat snapshots into "mean CPU %" and "throttled %".

Reads from the environment so the shell never has to interpolate numbers into a
Python source string:

    CPU_BEFORE   "usage_usec nr_periods nr_throttled" summed over the pods
    CPU_AFTER    the same, after the measured window
    CPU_LIMIT    the per-pod CPU limit as Kubernetes spells it ("400m", "2")
    CPU_PODS     how many pods those totals cover
    CPU_WINDOW   the measured window, in seconds

Prints two numbers on one line: mean CPU as a percentage of ONE pod's limit,
and the percentage of enforcement periods in which the limit was hit.

Why these two and not `kubectl top`: usage_usec is a monotonic counter of CPU
time actually consumed, so a delta over a known window is the mean core count
exactly, with no scrape interval or decay involved. nr_throttled/nr_periods
answers "was the limit binding" directly, which is the question that decides
whether a flattening throughput curve means the pods ran out of CPU or were
waiting on something shared - and those two have opposite implications for
whether a fitted kappa says anything about the architecture.
"""

from __future__ import annotations

import os
import sys


def limit_cores(raw: str) -> float:
    raw = raw.strip()
    if not raw:
        raise ValueError("empty CPU limit")
    # Kubernetes writes milli-cores as "400m" and whole cores as "2".
    return float(raw[:-1]) / 1000 if raw.endswith("m") else float(raw)


def main() -> int:
    try:
        before = [int(v) for v in os.environ["CPU_BEFORE"].split()]
        after = [int(v) for v in os.environ["CPU_AFTER"].split()]
        cores = limit_cores(os.environ["CPU_LIMIT"])
        pods = int(os.environ["CPU_PODS"])
        window = float(os.environ["CPU_WINDOW"])
    except (KeyError, ValueError) as error:
        print(f"cgroup_delta: {error}", file=sys.stderr)
        return 1

    if len(before) != 3 or len(after) != 3:
        print("cgroup_delta: expected 3 fields in each snapshot", file=sys.stderr)
        return 1
    if pods <= 0 or window <= 0 or cores <= 0:
        print("cgroup_delta: pods, window and limit must be positive", file=sys.stderr)
        return 1

    usage_seconds = (after[0] - before[0]) / 1e6
    periods = after[1] - before[1]
    throttled = after[2] - before[2]

    # A counter that went backwards means the pod set changed under us - a
    # restart, or a scale event mid-window. Reporting 0% would read as "idle",
    # which is the one answer that cannot be distinguished from a real result.
    if usage_seconds < 0 or periods < 0 or throttled < 0:
        print("cgroup_delta: counters went backwards; pods restarted mid-window",
              file=sys.stderr)
        return 1

    mean_cores_per_pod = usage_seconds / window / pods
    cpu_pct = mean_cores_per_pod / cores * 100
    throttled_pct = (throttled / periods * 100) if periods > 0 else 0.0

    print(f"{cpu_pct:.1f} {throttled_pct:.1f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

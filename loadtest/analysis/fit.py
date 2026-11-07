#!/usr/bin/env python3
"""Fits the USL to a pod sweep and writes a report.

    python loadtest/analysis/fit.py loadtest/results/sweep.csv

Input CSV, one row per step of the sweep:

    pods,rps,p50_ms,p95_ms,p99_ms,cpu_pct,hit_ratio
    1,980.4,4.1,38.2,412.0,61.2,0.702
    2,1890.1,4.3,41.0,455.0,63.8,0.699
    ...

Only `pods` and `rps` are fitted. The rest are carried into the report because
they are what decides whether the fit is believable:

  - A rising p99 while CPU stays flat means the limit is I/O or a lock, not
    capacity - and the fit then describes whatever actually saturated, which may
    not be the gateway.
  - A hit ratio that moves between steps means the steps were not comparable.
    The cache is the dominant term in this system's latency, so a sweep whose
    hit ratio drifted is measuring the cache warming up, not scalability.
"""

from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import usl


@dataclass(frozen=True)
class Step:
    pods: float
    rps: float
    p50_ms: float | None
    p95_ms: float | None
    p99_ms: float | None
    cpu_pct: float | None
    hit_ratio: float | None


def _optional_float(row: dict[str, str], key: str) -> float | None:
    raw = (row.get(key) or "").strip()
    if raw == "":
        return None
    try:
        return float(raw)
    except ValueError:
        return None


def read_steps(path: Path) -> list[Step]:
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            raise SystemExit(f"{path}: no header row")

        missing = {"pods", "rps"} - {name.strip() for name in reader.fieldnames}
        if missing:
            raise SystemExit(f"{path}: missing required column(s): {sorted(missing)}")

        steps: list[Step] = []
        for number, row in enumerate(reader, start=2):
            try:
                pods = float(row["pods"])
                rps = float(row["rps"])
            except (TypeError, ValueError) as error:
                raise SystemExit(f"{path}:{number}: {error}") from error
            steps.append(
                Step(
                    pods=pods,
                    rps=rps,
                    p50_ms=_optional_float(row, "p50_ms"),
                    p95_ms=_optional_float(row, "p95_ms"),
                    p99_ms=_optional_float(row, "p99_ms"),
                    cpu_pct=_optional_float(row, "cpu_pct"),
                    hit_ratio=_optional_float(row, "hit_ratio"),
                )
            )

    if not steps:
        raise SystemExit(f"{path}: no data rows")
    return steps


def warnings_for(steps: list[Step], fit: usl.UslFit) -> list[str]:
    """Reasons to distrust the fit. Printed before the numbers, on purpose."""
    notes: list[str] = []

    if fit.r_squared_throughput < 0.95:
        notes.append(
            f"R^2 on throughput is {fit.r_squared_throughput:.3f}. Below ~0.95 the "
            "model does not describe this data well enough to extrapolate from. "
            "Look for a step measured under a different configuration."
        )

    ratios = [s.hit_ratio for s in steps if s.hit_ratio is not None]
    if len(ratios) >= 2:
        spread = max(ratios) - min(ratios)
        if spread > 0.05:
            notes.append(
                f"Cache hit ratio moved {spread:.1%} across the sweep "
                f"({min(ratios):.1%} to {max(ratios):.1%}). The steps are not "
                "comparable: the cache is the dominant term in this system's "
                "latency, so this sweep measured the cache warming up rather than "
                "scalability. Pre-warm and repeat."
            )

    p99s = [(s.pods, s.p99_ms) for s in steps if s.p99_ms is not None]
    cpus = [(s.pods, s.cpu_pct) for s in steps if s.cpu_pct is not None]
    if len(p99s) >= 2 and len(cpus) >= 2:
        p99_rose = p99s[-1][1] > p99s[0][1] * 1.5
        cpu_flat = abs(cpus[-1][1] - cpus[0][1]) < 10.0
        if p99_rose and cpu_flat:
            notes.append(
                f"p99 rose from {p99s[0][1]:.0f} ms to {p99s[-1][1]:.0f} ms while CPU "
                f"stayed near {cpus[0][1]:.0f}%. The limit is I/O or a lock, not "
                "capacity - so these coefficients describe whatever saturated, which "
                "may not be the gateway. Check ingress-nginx connections and the "
                "database connection pool before quoting them."
            )

    if len(steps) < 4:
        notes.append(
            f"Only {len(steps)} points. The fit will succeed and mean very little; "
            "a 1-to-6 sweep is the minimum worth reporting."
        )

    return notes


def render(path: Path, steps: list[Step], fit: usl.UslFit, notes: list[str]) -> str:
    lines: list[str] = []
    lines.append("# USL fit")
    lines.append("")
    lines.append(f"Source: `{path.as_posix()}`  ")
    lines.append(f"Steps: {len(steps)}")
    lines.append("")

    if notes:
        lines.append("## Reasons to distrust this fit")
        lines.append("")
        lines.append(
            "Listed first deliberately. A fit is easy to produce and easy to quote; "
            "these are the conditions under which it should not be."
        )
        lines.append("")
        for note in notes:
            lines.append(f"- {note}")
        lines.append("")

    lines.append("## Coefficients")
    lines.append("")
    lines.append("```")
    lines.append(fit.summary())
    lines.append("```")
    lines.append("")

    lines.append("## Measured against modelled")
    lines.append("")
    lines.append("| Pods | Measured req/s | Modelled | Error | Efficiency | p99 ms | Hit ratio |")
    lines.append("|---:|---:|---:|---:|---:|---:|---:|")
    for step in steps:
        modelled = fit.predict(step.pods)
        error = (modelled - step.rps) / step.rps if step.rps else 0.0
        lines.append(
            f"| {step.pods:.0f} | {step.rps:,.1f} | {modelled:,.1f} | "
            f"{error:+.1%} | {fit.efficiency(step.pods):.3f} | "
            f"{'-' if step.p99_ms is None else f'{step.p99_ms:,.0f}'} | "
            f"{'-' if step.hit_ratio is None else f'{step.hit_ratio:.1%}'} |"
        )
    lines.append("")

    lines.append("## What this implies")
    lines.append("")
    peak = fit.n_max
    if peak is None:
        lines.append(
            "`kappa` is effectively zero, so the model finds **no peak**: throughput "
            "keeps rising with pods over the measured range. That is the good "
            "outcome, and it is also a statement about the MEASURED RANGE and "
            "nothing beyond it. The next question is which resource binds first - "
            "see the what-breaks-first ordering in "
            "`docs/architecture/scaling-model.md`."
        )
    else:
        lines.append(
            f"Throughput peaks around **{peak:.0f} pods** at roughly "
            f"**{fit.max_throughput:,.0f} req/s**, after which adding pods makes it "
            "worse rather than better."
        )
        lines.append("")
        if peak < 20:
            lines.append(
                f"A peak at {peak:.0f} pods is LOW. Coherency dominates, and no amount "
                "of horizontal scaling fixes crosstalk - the work belongs in reducing "
                "shared state, not in adding capacity. This would falsify the design "
                "as written, which is exactly what the measurement is for."
            )
        else:
            lines.append(
                f"At {peak:.0f} pods the gateway tier is not the constraint; state is. "
                "See the what-breaks-first ordering in "
                "`docs/architecture/scaling-model.md`."
            )
    lines.append("")
    lines.append(
        "`lambda` is single-pod throughput against `MockLlmProvider`, not a real "
        "model. It measures the GATEWAY, which is the point - see ADR-0011."
    )
    lines.append("")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path, help="sweep results")
    parser.add_argument(
        "-o", "--output", type=Path, default=None,
        help="write the markdown report here (default: stdout)",
    )
    args = parser.parse_args(argv)

    steps = read_steps(args.csv)
    try:
        fit = usl.fit([s.pods for s in steps], [s.rps for s in steps])
    except ValueError as error:
        print(f"cannot fit: {error}", file=sys.stderr)
        return 1

    notes = warnings_for(steps, fit)
    report = render(args.csv, steps, fit, notes)

    if args.output is None:
        print(report)
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8", newline="\n")
        print(f"wrote {args.output}")
        print()
        print(fit.summary())

    # Non-zero when the fit is not trustworthy, so a pipeline cannot publish a
    # bad fit silently.
    return 0 if fit.r_squared_throughput >= 0.95 else 2


if __name__ == "__main__":
    sys.exit(main())

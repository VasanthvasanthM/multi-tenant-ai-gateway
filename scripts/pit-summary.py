#!/usr/bin/env python3
"""Summarise PITest reports: the score per module, and where the survivors are.

PITest already enforces ``mutationThreshold`` and fails the build below it. What it
does not do is tell you *where* to look: its own console output is a single
percentage, and the per-class detail is buried in an HTML report nobody opens from
a CI log. This turns ``mutations.xml`` into the two things worth reading - the score,
and the classes with the most surviving mutants.

A surviving mutant is a specific, falsifiable claim: this line was changed, every
test still passed. Unlike a coverage percentage it names the missing assertion
rather than the missing execution, which is why the threshold that matters for a
cache is this one and not line coverage. Line coverage proves a line ran; a killed
mutant proves an assertion would have noticed it changing.

NO_COVERAGE is reported separately from SURVIVED on purpose. They look the same in
the score and mean different things: NO_COVERAGE means no test reached the line at
all, which is a coverage gap; SURVIVED means a test ran the line and asserted
nothing that depended on it, which is a *test quality* gap and the more interesting
finding of the two.

Usage:
    python scripts/pit-summary.py [--min-score 70] [--top 15] [root]

Exits 0 always unless --min-score is given and the overall score falls below it.
Finding no reports at all is an error either way: a silently empty mutation run is
indistinguishable from a passing one, which is the failure this script exists to
make impossible.
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import xml.etree.ElementTree as ElementTree
from collections import Counter
from dataclasses import dataclass, field

# PITest marks a mutation detected when a test failed because of it. These statuses
# are the ones it counts as detected; the attribute is authoritative, but the names
# are needed to tell the two kinds of non-detection apart.
NOT_DETECTED_BENIGN = "NO_COVERAGE"


@dataclass
class ModuleReport:
    """One module's mutation results."""

    name: str
    detected: int = 0
    total: int = 0
    statuses: Counter = field(default_factory=Counter)
    survivors: Counter = field(default_factory=Counter)
    uncovered: Counter = field(default_factory=Counter)

    @property
    def score(self) -> float:
        return 100.0 * self.detected / self.total if self.total else 0.0


def parse(report: pathlib.Path, module: str) -> ModuleReport:
    result = ModuleReport(name=module)

    # A truncated or empty mutations.xml means the run died partway. Reporting that
    # as "0 mutations, all good" would be the exact silent pass this script guards
    # against, so let the parse error propagate.
    root = ElementTree.parse(report).getroot()

    for mutation in root.iter("mutation"):
        status = (mutation.get("status") or "UNKNOWN").upper()
        detected = (mutation.get("detected") or "false").lower() == "true"

        result.total += 1
        result.statuses[status] += 1
        if detected:
            result.detected += 1
            continue

        target = mutation.findtext("mutatedClass") or "unknown"
        # Nested and lambda classes land in the same source file as their outer
        # class, and splitting them makes the ranking list the same file four times.
        target = target.split("$", 1)[0]
        if status == NOT_DETECTED_BENIGN:
            result.uncovered[target] += 1
        else:
            result.survivors[target] += 1

    return result


def find_reports(root: pathlib.Path) -> list[tuple[str, pathlib.Path]]:
    found = []
    for report in sorted(root.glob("*/target/pit-reports/mutations.xml")):
        # <module>/target/pit-reports/mutations.xml - the module is three up.
        found.append((report.parents[2].name, report))
    # A single-module invocation writes to ./target, with no module directory.
    local = root / "target" / "pit-reports" / "mutations.xml"
    if local.is_file():
        found.append((root.resolve().name, local))
    return found


def render(reports: list[ModuleReport], top: int) -> list[str]:
    lines = ["## Mutation testing", ""]

    overall_detected = sum(r.detected for r in reports)
    overall_total = sum(r.total for r in reports)
    overall = 100.0 * overall_detected / overall_total if overall_total else 0.0

    lines += [
        "| Module | Mutants | Killed | Score |",
        "|---|---:|---:|---:|",
    ]
    for report in sorted(reports, key=lambda r: r.score):
        lines.append(
            f"| `{report.name}` | {report.total} | {report.detected} "
            f"| **{report.score:.1f}%** |"
        )
    lines.append(
        f"| **overall** | **{overall_total}** | **{overall_detected}** "
        f"| **{overall:.1f}%** |"
    )
    lines.append("")

    statuses: Counter = Counter()
    for report in reports:
        statuses.update(report.statuses)
    if statuses:
        breakdown = ", ".join(
            f"{status} {count}" for status, count in statuses.most_common()
        )
        lines += [f"Statuses: {breakdown}", ""]

    survivors: Counter = Counter()
    uncovered: Counter = Counter()
    for report in reports:
        survivors.update(report.survivors)
        uncovered.update(report.uncovered)

    if survivors:
        lines += [
            "### Surviving mutants - a test ran the line and asserted nothing",
            "",
            "The line was changed, the tests still passed. Each one names a missing",
            "assertion rather than a missing execution, which makes this list more",
            "actionable than any coverage report.",
            "",
            "| Class | Survivors |",
            "|---|---:|",
        ]
        for name, count in survivors.most_common(top):
            lines.append(f"| `{name.rsplit('.', 1)[-1]}` | {count} |")
        lines.append("")

    if uncovered:
        lines += [
            "### Uncovered mutants - no test reached the line",
            "",
            "A plain coverage gap, not a test-quality one.",
            "",
            "| Class | Uncovered |",
            "|---|---:|",
        ]
        for name, count in uncovered.most_common(top):
            lines.append(f"| `{name.rsplit('.', 1)[-1]}` | {count} |")
        lines.append("")

    return lines


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=".", help="repository root")
    parser.add_argument(
        "--min-score",
        type=float,
        default=None,
        help="exit non-zero below this overall score; omit to report only",
    )
    parser.add_argument("--top", type=int, default=15, help="classes to list")
    args = parser.parse_args()

    root = pathlib.Path(args.root)
    found = find_reports(root)
    if not found:
        print(
            "no PITest reports found under "
            f"{root}/*/target/pit-reports/mutations.xml\n"
            "A mutation run that produced no report is not a passing one. Check that "
            "-Pmutation was active and that the run reached the verify phase.",
            file=sys.stderr,
        )
        return 1

    reports = [parse(path, module) for module, path in found]
    lines = render(reports, args.top)
    print("\n".join(lines))

    total = sum(r.total for r in reports)
    detected = sum(r.detected for r in reports)
    overall = 100.0 * detected / total if total else 0.0

    if args.min_score is not None and overall < args.min_score:
        print(
            f"mutation score {overall:.1f}% is below the {args.min_score:.0f}% "
            "threshold",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

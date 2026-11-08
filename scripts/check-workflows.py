#!/usr/bin/env python3
"""Catch the GitHub Actions errors that are invisible until they are expensive.

Run this BEFORE pushing a workflow change:

    python scripts/check-workflows.py

## Why a local script and not a CI job

A CI job cannot check this. When GitHub rejects a workflow file, it refuses the
whole file - every job in it, including any job that would have linted it. The run
appears with **zero jobs, a 0-second duration, and `conclusion: failure`**, and is
titled after the file path rather than the workflow's own `name:`. There is no log
to read because nothing ran.

That is how `if: ${{ secrets.X != '' }}` killed this pipeline for four consecutive
runs across two days while the repository went on claiming CI was green. `secrets`
is not an allowed context in an `if:`; using it is a validation error, not a
runtime one. Locally `yaml.safe_load` accepts the file happily, because the YAML is
perfectly well-formed - it is the Actions schema that rejects it.

## What is checked

1. **Disallowed contexts in `if:`** - `secrets` anywhere, and `env` in a job-level
   `if` (job `if` is evaluated before any env exists).
2. **A step `if` reading a variable the same step defines in its own `env:`** - in
   scope nowhere, so the condition silently reads empty and the step is skipped
   every time. A guard that always takes the skip branch is indistinguishable from
   a guard that works, which is worse than a crash.
3. **Duplicate mapping keys** - PyYAML keeps the last and says nothing; GitHub
   rejects the file.
4. **Structure** - every job needs `runs-on` and `steps`, every step needs `run`
   or `uses`.

This is deliberately not a general-purpose linter. For that, use `actionlint`,
which catches far more - but note it has the same blind spot if it runs inside the
workflow it is checking.
"""

from __future__ import annotations

import io
import pathlib
import re
import sys

import yaml

WORKFLOW_DIR = pathlib.Path(".github/workflows")

# Contexts GitHub refuses in any `if:`. `secrets` is the one that bites.
FORBIDDEN_IN_ANY_IF = ("secrets.",)


class DuplicateKeyLoader(yaml.SafeLoader):
    """SafeLoader that records duplicate keys instead of silently dropping them."""


def _no_duplicates(loader, node, deep=False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            loader.duplicates.append((key, key_node.start_mark.line + 1))
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


DuplicateKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _no_duplicates
)


def load(path: pathlib.Path) -> tuple[dict, list]:
    loader = DuplicateKeyLoader(io.open(path, encoding="utf-8").read())
    loader.duplicates = []
    try:
        document = loader.get_single_data()
    finally:
        duplicates = loader.duplicates
        loader.dispose()
    return document, duplicates


def names_in(expression: str) -> set[str]:
    """Variable names an expression reads out of the env context."""
    return set(re.findall(r"\benv\.([A-Za-z_][A-Za-z0-9_]*)", str(expression)))


def check(path: pathlib.Path) -> list[str]:
    problems: list[str] = []
    document, duplicates = load(path)

    for key, line in duplicates:
        problems.append(f"line {line}: duplicate key {key!r} - GitHub rejects the file")

    if not isinstance(document, dict) or "jobs" not in document:
        problems.append("no `jobs:` mapping")
        return problems

    workflow_env = set(document.get("env") or {})

    for job_name, job in (document["jobs"] or {}).items():
        if not isinstance(job, dict):
            problems.append(f"job `{job_name}`: not a mapping")
            continue

        job_if = str(job.get("if", ""))
        for forbidden in FORBIDDEN_IN_ANY_IF:
            if forbidden in job_if:
                problems.append(
                    f"job `{job_name}`.if uses `{forbidden.rstrip('.')}` - not an "
                    "allowed context in `if`; the WHOLE workflow is refused"
                )
        if names_in(job_if):
            problems.append(
                f"job `{job_name}`.if reads the env context, which does not exist "
                "when a job `if` is evaluated"
            )

        job_env = set(job.get("env") or {})
        reusable = "uses" in job

        if not reusable:
            if "runs-on" not in job:
                problems.append(f"job `{job_name}`: no `runs-on`")
            if "steps" not in job:
                problems.append(f"job `{job_name}`: no `steps`")

        for index, step in enumerate(job.get("steps") or []):
            if not isinstance(step, dict):
                problems.append(f"job `{job_name}` step {index}: not a mapping")
                continue

            label = step.get("name") or step.get("uses") or f"step {index}"
            if "run" not in step and "uses" not in step:
                problems.append(
                    f"job `{job_name}` / {label}: neither `run` nor `uses`"
                )

            step_if = str(step.get("if", ""))
            for forbidden in FORBIDDEN_IN_ANY_IF:
                if forbidden in step_if:
                    problems.append(
                        f"job `{job_name}` / {label}: `if` uses "
                        f"`{forbidden.rstrip('.')}` - not an allowed context in "
                        "`if`; the WHOLE workflow is refused, with zero jobs and "
                        "no log. Map it into job-level `env:` and test that."
                    )

            step_env = set(step.get("env") or {})
            for name in names_in(step_if):
                if name in step_env and name not in job_env | workflow_env:
                    problems.append(
                        f"job `{job_name}` / {label}: `if` reads `env.{name}` but "
                        f"`{name}` is defined only in this step's own `env:`, which "
                        "is not in scope for its `if`. The condition reads empty and "
                        "the step is skipped every time. Move it to job-level `env:`."
                    )
                elif name not in step_env | job_env | workflow_env:
                    problems.append(
                        f"job `{job_name}` / {label}: `if` reads `env.{name}`, which "
                        "no workflow-, job- or step-level `env:` defines. It will "
                        "always be empty."
                    )

    return problems


def main() -> int:
    if not WORKFLOW_DIR.is_dir():
        print(f"{WORKFLOW_DIR} not found - run from the repository root", file=sys.stderr)
        return 1

    files = sorted(
        p for p in WORKFLOW_DIR.iterdir() if p.suffix in (".yml", ".yaml")
    )
    if not files:
        print(f"no workflow files in {WORKFLOW_DIR}", file=sys.stderr)
        return 1

    total = 0
    for path in files:
        problems = check(path)
        total += len(problems)
        if problems:
            print(f"{path}:")
            for problem in problems:
                print(f"  - {problem}")
        else:
            print(f"{path}: ok")

    if total:
        print(f"\n{total} problem(s). A workflow GitHub refuses runs ZERO jobs.")
        return 1
    print(f"\n{len(files)} workflow file(s) ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())

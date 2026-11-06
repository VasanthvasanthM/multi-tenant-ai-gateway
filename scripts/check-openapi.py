#!/usr/bin/env python3
"""Validate the OpenAPI spec, and check it still matches the Java it describes.

    python scripts/check-openapi.py

Two checks, and the second is the one worth having.

1. The spec is valid OpenAPI 3.1.

2. The spec AGREES WITH THE CODE. A spec drifts silently: someone adds a
   GatewayError variant, the spec keeps its old enum, and the document now lies
   about the API in a way no reader can detect. So this asserts that

     - the `code` enum in ErrorResponse is exactly the set of code() values
       GatewayError actually returns, and
     - every HTTP status GatewayError can produce has a documented response.

   An invented field is the specific failure this guards against: an earlier
   draft documented an `X-Gateway-Served-By` response header that the server
   never sends.

Requires: pyyaml, openapi-spec-validator.
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SPEC = REPO / "docs" / "api" / "openapi.yaml"
ERRORS = REPO / "common" / "src" / "main" / "java" / "io" / "vasanth" / "gateway" \
    / "common" / "error" / "GatewayError.java"

# Statuses the gateway returns that are not produced by a GatewayError variant.
NON_ERROR_STATUSES = {"200"}

failures = []


def fail(msg):
    failures.append(msg)
    print(f"  FAIL  {msg}")


def ok(msg):
    print(f"  ok    {msg}")


def parse_java():
    """Extract (code, httpStatus) for every GatewayError variant."""
    src = ERRORS.read_text(encoding="utf-8")
    codes, statuses = set(), set()
    pattern = r"record (\w+)\([^)]*\) implements GatewayError \{(.*?)\n    \}"
    for match in re.finditer(pattern, src, re.S):
        name, body = match.group(1), match.group(2)
        code = re.search(r'code\(\)\s*\{\s*return "([^"]+)"', body)
        status = re.search(r"httpStatus\(\) \{\s*return (\d+)", body)
        if not code:
            fail(f"GatewayError.{name} has no literal code() - cannot verify the spec")
            continue
        codes.add(code.group(1))
        if status:
            statuses.add(status.group(1))
    return codes, statuses


def main():
    for path in (SPEC, ERRORS):
        if not path.exists():
            fail(f"missing: {path.relative_to(REPO)}")
            return 1

    import yaml

    spec = yaml.safe_load(SPEC.read_text(encoding="utf-8"))

    # ---- 1. schema validity ------------------------------------------------
    try:
        from openapi_spec_validator import validate
        validate(spec)
        ok(f"valid OpenAPI {spec.get('openapi')}")
    except ImportError:
        print("  skip  openapi-spec-validator not installed "
              "(pip install openapi-spec-validator)")
    except Exception as exc:                      # noqa: BLE001 - report, do not raise
        fail(f"schema invalid: {exc}")

    # ---- 2. spec agrees with the code -------------------------------------
    java_codes, java_statuses = parse_java()

    try:
        spec_codes = set(
            spec["components"]["schemas"]["ErrorResponse"]
                ["properties"]["error"]["properties"]["code"]["enum"]
        )
    except KeyError as exc:
        fail(f"spec has no ErrorResponse error.code enum ({exc})")
        spec_codes = set()

    missing = sorted(java_codes - spec_codes)
    extra = sorted(spec_codes - java_codes)
    if missing:
        fail(f"codes the gateway emits but the spec omits: {missing}")
    if extra:
        fail(f"codes the spec documents but the gateway never emits: {extra}")
    if not missing and not extra:
        ok(f"all {len(java_codes)} error codes match GatewayError")

    try:
        documented = set(spec["paths"]["/v1/chat/completions"]["post"]["responses"])
    except KeyError as exc:
        fail(f"spec has no /v1/chat/completions responses ({exc})")
        documented = set()

    undocumented = sorted(java_statuses - documented - NON_ERROR_STATUSES)
    if undocumented:
        fail(f"HTTP statuses the gateway returns but the spec omits: {undocumented}")
    else:
        ok(f"all {len(java_statuses)} error statuses documented")

    if failures:
        print(f"\n{len(failures)} problem(s). The spec and the code disagree.")
        return 1

    print("\nOpenAPI spec is valid and consistent with GatewayError.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Verification for the USL fitter.

The central test is `test_recovers_known_coefficients`: generate throughput
from coefficients we chose, fit it, and check the fit returns those
coefficients. A fitting routine that cannot do that cannot be trusted with real
data, and no amount of plausible-looking output on real data would reveal it.

Run with:  python -m pytest loadtest/analysis/test_usl.py
or:        python loadtest/analysis/test_usl.py
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import usl  # noqa: E402

TOLERANCE = 1e-6


def _generate(lam: float, sigma: float, kappa: float, pods: list[float]) -> list[float]:
    """Exact USL throughput for known coefficients."""
    model = usl.UslFit(lam, sigma, kappa, 1.0, 1.0, len(pods))
    return [model.predict(n) for n in pods]


# ---------------------------------------------------------------------------
# The one that matters
# ---------------------------------------------------------------------------

def test_recovers_known_coefficients() -> None:
    """Noise-free data must return the coefficients it was generated from."""
    lam, sigma, kappa = 1_200.0, 0.035, 0.00012
    pods = [1, 2, 3, 4, 5, 6]

    fitted = usl.fit(pods, _generate(lam, sigma, kappa, pods))

    assert abs(fitted.lam - lam) < TOLERANCE * lam
    assert abs(fitted.sigma - sigma) < TOLERANCE
    assert abs(fitted.kappa - kappa) < TOLERANCE
    # Exact data, so both R^2 values must be 1.
    assert fitted.r_squared_linear > 1.0 - 1e-9
    assert fitted.r_squared_throughput > 1.0 - 1e-9


def test_recovers_across_a_range_of_shapes() -> None:
    """Not just one lucky parameter set."""
    cases = [
        # (lambda, sigma, kappa) - benign, contention-bound, coherency-bound
        (500.0, 0.01, 0.00001),
        (2_000.0, 0.20, 0.0005),
        (100.0, 0.001, 0.01),
    ]
    pods = [1, 2, 3, 4, 5, 6, 8, 10]

    for lam, sigma, kappa in cases:
        fitted = usl.fit(pods, _generate(lam, sigma, kappa, pods))
        assert abs(fitted.lam - lam) < 1e-4 * lam, f"lambda for {lam},{sigma},{kappa}"
        assert abs(fitted.sigma - sigma) < 1e-6, f"sigma for {lam},{sigma},{kappa}"
        assert abs(fitted.kappa - kappa) < 1e-8, f"kappa for {lam},{sigma},{kappa}"


def test_n_max_matches_the_analytic_peak() -> None:
    """N_max must be where throughput actually peaks."""
    lam, sigma, kappa = 1_000.0, 0.03, 0.0004
    pods = [1, 2, 3, 4, 5, 6]

    fitted = usl.fit(pods, _generate(lam, sigma, kappa, pods))
    peak = fitted.n_max
    assert peak is not None

    # Formula and reality agree: throughput just either side of the peak must
    # be lower. If this failed, the reported ceiling would be somewhere the
    # system is not actually fastest.
    assert fitted.predict(peak) >= fitted.predict(peak - 0.5)
    assert fitted.predict(peak) >= fitted.predict(peak + 0.5)
    assert abs(peak - math.sqrt((1 - sigma) / kappa)) < 1e-6


# ---------------------------------------------------------------------------
# Honest reporting of weak fits
# ---------------------------------------------------------------------------

def test_zero_kappa_reports_no_peak_rather_than_a_huge_number() -> None:
    """With no coherency term there is no peak, and that must be said."""
    lam, sigma = 800.0, 0.05
    pods = [1, 2, 3, 4, 5, 6]

    fitted = usl.fit(pods, _generate(lam, sigma, 0.0, pods))

    # None, not 1e9. "No peak within the model" is a materially different
    # finding from "the peak is very far away", and rendering it as a number
    # invites someone to quote a ceiling the data does not support.
    assert fitted.n_max is None
    assert fitted.max_throughput is None
    assert "none" in fitted.summary()
    assert "USL does not describe" in fitted.summary()


def test_negative_coefficients_are_clamped_to_zero() -> None:
    """Noise can fit a negative sigma; negative contention is not a discovery."""
    # Slightly super-linear data, which no physical system produces but noise
    # imitates: a negative sigma would mean the system gets MORE efficient the
    # more concurrency it has.
    pods = [1, 2, 3, 4]
    throughput = [100.0, 205.0, 315.0, 430.0]

    fitted = usl.fit(pods, throughput)

    assert fitted.sigma >= 0.0
    assert fitted.kappa >= 0.0


def test_reports_both_r_squared_values() -> None:
    """The linearised R^2 is what was minimised; the throughput one is honest."""
    pods = [1, 2, 3, 4, 5, 6]
    # Deliberately noisy, so the two diverge and the distinction is visible.
    throughput = [1_000.0, 1_900.0, 2_600.0, 3_100.0, 3_300.0, 3_350.0]

    fitted = usl.fit(pods, throughput)

    assert 0.0 <= fitted.r_squared_linear <= 1.0
    assert 0.0 <= fitted.r_squared_throughput <= 1.0
    # Both appear in the summary, so a report cannot quote the flattering one
    # without the other being on the page.
    assert "on throughput" in fitted.summary()
    assert "linearised" in fitted.summary()


def test_identical_observations_report_zero_not_one() -> None:
    """A flat series explains nothing, and R^2 of 1.0 would be a lie."""
    fitted = usl.fit([1, 2, 3], [500.0, 500.0, 500.0])
    # Flat throughput as concurrency triples is a real and alarming result -
    # a totally serialised system. It must not be dressed up as a perfect fit.
    assert fitted.r_squared_throughput <= 1.0


# ---------------------------------------------------------------------------
# Refusals
# ---------------------------------------------------------------------------

def test_refuses_fewer_than_three_points() -> None:
    """Three parameters need three points."""
    for pods, throughput in (([1], [100.0]), ([1, 2], [100.0, 190.0])):
        try:
            usl.fit(pods, throughput)
        except ValueError as error:
            assert "three points" in str(error)
        else:
            raise AssertionError("expected a refusal for < 3 points")


def test_refuses_mismatched_lengths() -> None:
    try:
        usl.fit([1, 2, 3], [100.0, 200.0])
    except ValueError as error:
        assert "length" in str(error)
    else:
        raise AssertionError("expected a refusal for mismatched lengths")


def test_refuses_zero_throughput() -> None:
    """A zero measurement means the step failed; it must be excluded, not fitted."""
    try:
        usl.fit([1, 2, 3], [100.0, 0.0, 300.0])
    except ValueError as error:
        assert "positive" in str(error)
    else:
        raise AssertionError("expected a refusal for zero throughput")


def test_refuses_non_positive_concurrency() -> None:
    try:
        usl.fit([0, 1, 2], [100.0, 200.0, 300.0])
    except ValueError as error:
        assert "positive" in str(error)
    else:
        raise AssertionError("expected a refusal for zero concurrency")


# ---------------------------------------------------------------------------
# Efficiency
# ---------------------------------------------------------------------------

def test_efficiency_is_one_for_a_perfect_system() -> None:
    pods = [1, 2, 3, 4]
    fitted = usl.fit(pods, _generate(1_000.0, 0.0, 0.0, pods))

    for n in pods:
        assert abs(fitted.efficiency(n) - 1.0) < 1e-9


def test_efficiency_falls_as_contention_bites() -> None:
    pods = [1, 2, 3, 4, 5, 6]
    fitted = usl.fit(pods, _generate(1_000.0, 0.1, 0.001, pods))

    # Monotone decreasing, which is the whole point: it is the number that says
    # "the sixth pod is worth less than the second".
    efficiencies = [fitted.efficiency(n) for n in pods]
    assert all(b <= a for a, b in zip(efficiencies, efficiencies[1:]))
    assert efficiencies[0] > efficiencies[-1]


if __name__ == "__main__":
    failures = 0
    for name, function in sorted(globals().items()):
        if not name.startswith("test_") or not callable(function):
            continue
        try:
            function()
            print(f"  PASS  {name}")
        except AssertionError as error:
            failures += 1
            print(f"  FAIL  {name}: {error}")
        except Exception as error:  # noqa: BLE001
            failures += 1
            print(f"  ERROR {name}: {type(error).__name__}: {error}")

    print()
    if failures:
        print(f"{failures} test(s) failed")
        sys.exit(1)
    print("all USL fitter tests passed")

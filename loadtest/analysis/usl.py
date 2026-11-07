"""Universal Scalability Law fitting.

    X(N) = lambda*N / (1 + sigma*(N-1) + kappa*N*(N-1))

where N is concurrency (gateway pods here), sigma is contention - work that
serialises - and kappa is coherency, the cost of keeping N copies consistent.

WHY THIS MODEL AND NOT AMDAHL'S LAW
-----------------------------------
Amdahl is monotonic: add capacity, never get slower. The kappa*N*(N-1) term is
what makes USL different - when kappa > 0 throughput PEAKS and then DECLINES,
which is what real distributed systems do. The peak,

    N_max = sqrt((1 - sigma) / kappa)

is the number actually worth knowing. A fit saying N_max = 20 means the
architecture needs sharding before it needs more pods; a fit with kappa ~ 0
means it scales linearly until some other resource binds, and names what to
measure next.

HOW THE FIT WORKS
-----------------
The model is nonlinear in its parameters but becomes LINEAR after one
rearrangement, which is the standard treatment (Gunther). Dividing through:

    N / X(N) = (1 + sigma*(N-1) + kappa*N*(N-1)) / lambda

so with y = N / X(N) and unknowns a = 1/lambda, b = sigma/lambda,
c = kappa/lambda:

    y = a*1 + b*(N-1) + c*N*(N-1)

That is ordinary least squares against three basis functions - one
`numpy.linalg.lstsq` call, deterministic, no iteration and no initial guess to
get wrong. scipy is not required, which matters because this has to run in CI.

THE CAVEAT, because it is a real one
------------------------------------
Minimising residuals in y = N/X is not the same as minimising them in X. The
transform weights low-N points more heavily, since y is largest there. With the
handful of points a 1-to-6 pod sweep produces that is acceptable and it is what
the literature does, but it means the reported R^2 is the R^2 OF THE LINEARISED
fit. `fit()` therefore also reports r_squared_throughput, computed on the
original scale, and a report should quote that one.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

# Below this, kappa is treated as zero: the data does not support a coherency
# term and reporting a huge N_max from a near-zero denominator would be
# precision the measurement does not have.
KAPPA_EPSILON = 1e-9


@dataclass(frozen=True)
class UslFit:
    """A fitted model, plus everything needed to judge whether to believe it."""

    lam: float
    """Ideal throughput of one unit - RPS of a single pod, unloaded."""

    sigma: float
    """Contention. Serialised work: Redis round trips, one PostgreSQL primary."""

    kappa: float
    """Coherency. Crosstalk between pods."""

    r_squared_linear: float
    """Fit quality on the LINEARISED scale, which is what was minimised."""

    r_squared_throughput: float
    """Fit quality on throughput itself. Quote this one."""

    points: int

    @property
    def n_max(self) -> float | None:
        """Concurrency at peak throughput, or None when kappa is ~0.

        None means "no peak within the model" - throughput keeps rising, and
        the limit is something this model does not describe. That is a
        materially different finding from a large number and should not be
        rendered as one.
        """
        if self.kappa <= KAPPA_EPSILON:
            return None
        if self.sigma >= 1.0:
            # sigma >= 1 means the serial fraction alone saturates the system;
            # the peak is at or below one unit and the formula would take the
            # square root of a negative.
            return 1.0
        return math.sqrt((1.0 - self.sigma) / self.kappa)

    @property
    def max_throughput(self) -> float | None:
        """Throughput at N_max, when there is one."""
        peak = self.n_max
        return None if peak is None else self.predict(peak)

    def predict(self, n: float) -> float:
        """Modelled throughput at concurrency n."""
        denominator = 1.0 + self.sigma * (n - 1.0) + self.kappa * n * (n - 1.0)
        return self.lam * n / denominator

    def efficiency(self, n: float) -> float:
        """Throughput per unit, relative to one unit. 1.0 is linear scaling."""
        return self.predict(n) / (self.lam * n)

    def summary(self) -> str:
        lines = [
            f"  lambda  {self.lam:>12.2f}   req/s for a single pod",
            f"  sigma   {self.sigma:>12.5f}   contention (serialised work)",
            f"  kappa   {self.kappa:>12.8f}   coherency (crosstalk)",
            f"  R^2     {self.r_squared_throughput:>12.4f}   on throughput"
            f"  ({self.r_squared_linear:.4f} linearised)",
            f"  points  {self.points:>12d}",
        ]
        peak = self.n_max
        if peak is None:
            lines.append(
                "  N_max            none   kappa is ~0: no peak within the model.\n"
                "                          Throughput keeps rising with N, so the\n"
                "                          limit is something USL does not describe -\n"
                "                          find it before quoting a ceiling."
            )
        else:
            lines.append(f"  N_max   {peak:>12.1f}   pods at peak throughput")
            lines.append(f"  X_max   {self.max_throughput:>12.2f}   req/s at that peak")
        return "\n".join(lines)


def fit(concurrency: list[float], throughput: list[float]) -> UslFit:
    """Fits the USL to measured points.

    Args:
        concurrency: units of concurrency, e.g. pod counts. Must be > 0.
        throughput: measured throughput at each. Must be > 0.

    Raises:
        ValueError: on mismatched lengths, fewer than three points, or a
            non-positive value. Three is the minimum because the model has
            three parameters - fitting two points gives a line through them and
            an R^2 of 1.0 that means nothing.
    """
    if len(concurrency) != len(throughput):
        raise ValueError(
            f"concurrency and throughput differ in length: "
            f"{len(concurrency)} vs {len(throughput)}"
        )
    if len(concurrency) < 3:
        raise ValueError(
            "the USL has three parameters, so it needs at least three points; "
            f"got {len(concurrency)}. Two points fit perfectly and tell you nothing."
        )

    n = np.asarray(concurrency, dtype=float)
    x = np.asarray(throughput, dtype=float)

    if np.any(n <= 0):
        raise ValueError("concurrency must be positive")
    if np.any(x <= 0):
        raise ValueError(
            "throughput must be positive; a zero measurement means the step "
            "failed and should be excluded rather than fitted"
        )

    # y = N/X, linear in [1, (N-1), N(N-1)].
    y = n / x
    design = np.column_stack([np.ones_like(n), n - 1.0, n * (n - 1.0)])

    coefficients, *_ = np.linalg.lstsq(design, y, rcond=None)
    a, b, c = (float(v) for v in coefficients)

    if a <= 0:
        raise ValueError(
            "the fit produced a non-positive 1/lambda, which has no physical "
            "meaning - the data is probably not monotone enough to fit, or a "
            "step was measured under a different configuration"
        )

    lam = 1.0 / a
    # Clamped at zero. A slightly negative sigma or kappa is a fitting artefact
    # of noisy data, and NEGATIVE contention would mean the system gets more
    # efficient the more of it there is - which would be a measurement error,
    # not a discovery.
    sigma = max(0.0, b / a)
    kappa = max(0.0, c / a)

    predicted_y = design @ coefficients
    r2_linear = _r_squared(y, predicted_y)

    candidate = UslFit(lam, sigma, kappa, r2_linear, 0.0, len(n))
    predicted_x = np.array([candidate.predict(value) for value in n])
    r2_throughput = _r_squared(x, predicted_x)

    return UslFit(lam, sigma, kappa, r2_linear, r2_throughput, len(n))


def _r_squared(observed: np.ndarray, predicted: np.ndarray) -> float:
    """Coefficient of determination, clamped to [0,1] at the bottom.

    A negative R^2 is possible and means the fit is worse than predicting the
    mean. Reported as 0.0, because a negative number here invites someone to
    read it as a small positive one.
    """
    residual = float(np.sum((observed - predicted) ** 2))
    total = float(np.sum((observed - np.mean(observed)) ** 2))
    if total == 0.0:
        # Every observation identical. The fit explains nothing because there
        # is nothing to explain; 1.0 would be a lie.
        return 0.0
    return max(0.0, 1.0 - residual / total)

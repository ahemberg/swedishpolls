"""Run: python3 docs/research/model-audit-check.py. Audit algebra, not a poll model."""
from math import isclose, log, sqrt


def check():
    d, n = 9, 1000
    # Columns of a Helmert contrast basis.
    b = [[(1 / sqrt((j + 1) * (j + 2)) if i <= j else
           -(j + 1) / sqrt((j + 1) * (j + 2)) if i == j + 1 else 0)
          for j in range(d - 1)] for i in range(d)]
    for j in range(d - 1):
        assert abs(sum(row[j] for row in b)) < 1e-12
        for k in range(d - 1):
            assert isclose(sum(row[j] * row[k] for row in b),
                           float(j == k), abs_tol=1e-12)
    for p in ([1 / d] * d, [.20, .04, .06, .04, .30, .08, .05, .20, .03]):
        cov = [[((p[i] if i == k else 0) - p[i] * p[k]) / n
                for k in range(d)] for i in range(d)]
        for j in range(d - 1):
            for k in range(d - 1):
                delta = sum(b[i][j] / p[i] * cov[i][l] * b[l][k] / p[l]
                            for i in range(d) for l in range(d))
                formula = sum(b[i][j] * b[i][k] / p[i] for i in range(d)) / n
                assert isclose(delta, formula, abs_tol=1e-12)
                if p == [1 / d] * d:
                    assert isclose(formula, d / n if j == k else 0, abs_tol=1e-12)
    y, path, v = 1., [0., 2., 3.], .2
    avg = sum(path) / len(path)
    repeated = sum((y - x) ** 2 for x in path) / (2 * len(path) * v)
    aggregate = (y - avg) ** 2 / (2 * v)
    artificial = sum((x - avg) ** 2 for x in path) / (2 * len(path) * v)
    assert isclose(repeated, aggregate + artificial)
    assert artificial > 0
    ll = lambda q, e: -.5 * (log(1 + q) + e * e / (1 + q))
    assert ll(3, 2) > ll(0, 2) and ll(3, 2) > ll(8, 2)
    assert ll(0, 0) > ll(1, 0)
    print('PASS: Helmert basis, delta covariance, repetition penalty, likelihood counterexamples')


if __name__ == '__main__':
    check()

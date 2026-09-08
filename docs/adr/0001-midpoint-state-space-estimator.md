# Midpoint state-space estimator with cycle-specific house effects

The v1 poll-of-polls estimate uses a compositional state-space model: one observation
per poll at its fieldwork midpoint with full delta-method multinomial ilr covariance,
a daily ilr random walk, house effects reset per election cycle and shrunk toward zero
for sparse institutes, centered with equal weights over institutes active in the
current cycle, and a single pooled overdispersion factor on observation noise.
Hyperparameters are plug-in marginal ML tuned inside publication-time folds; published
uncertainty is documented as conditional on them, with a grid mixture as the named
fallback if coverage gates fail. The published history is the smoothed curve, dated at
last fieldwork date with no forward projection; election results are display references,
not model observations.

## Considered Options

- Fieldwork-window-average likelihood: statistically indistinguishable on every
  publication-aware metric at ~7x wall time and 1.5 GB peak memory; kept as a
  validated reference, not a candidate.
- Transparent recency-weighted average: rejected for publication (predictive 95%
  coverage 0.86, ~3 pp trend lag).
- Daily-repeated aggregate observations (original proposal): introduces an artificial
  within-window flatness penalty; rejected by the statistical audit.
- Static 2006–2026 house effects with poll-count centering: assumes unchanged institute
  behavior through method breaks and lets prolific trackers dominate the reference.

## Consequences

Release is gated on pre-registered comparative checks (log score vs the recency
baseline and window-average reference, coverage bands, split misfit, sensitivity of
headline probabilities to centering and institute exclusion), with 2022 as the
untouched final audit and 2026 registered prospectively. Publication fails closed:
a failed publication-time check keeps the last validated estimate live. Full detail
in [issue #6](https://github.com/ahemberg/swedishpolls/issues/6).

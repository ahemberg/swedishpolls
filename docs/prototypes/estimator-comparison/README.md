# PROTOTYPE: estimator comparison reference experiment

**Disposable code.** Answers wayfinder ticket
[#10](https://github.com/ahemberg/swedishpolls/issues/10): which minimal
candidate estimator has adequate temporal behavior, predictive calibration
and resource use to justify choosing it for v1? This is comparative
evidence for the model decision
([#6](https://github.com/ahemberg/swedishpolls/issues/6)), not production
code and not a claim of election-forecast accuracy.

## Run

```
python3 experiment.py
```

Requires numpy and scipy only. Outputs land in `results/` as JSON, with a
full log in `results-run.log`. Everything is seeded (`CONFIG["seed"]`);
data, seeds and configuration are recorded in `results/meta.json`.

## Data

`polls-pinned.csv` is `Data/Polls.csv` from MansMeg/SwedishPolls at the
audit-pinned commit `f0390c05854d87bbf21db9d31c6431ffa0f07f7e` (CC0).
Eligibility follows the approved data contract
([#5](https://github.com/ahemberg/swedishpolls/issues/5)): exclude exit
polls (SVT VALU), rows missing any of the eight party shares, sample size
or usable collection dates, rows published before collection end, and
rows with non-positive derived OTHER. Rows without a publication date are
excluded entirely here because every evaluation is publication-aware.
Model start 2010-01-01 (all eight parliamentary parties present; SD era).
Composition is the eight parliamentary parties plus derived
OTHER = 100 − sum(8); exact-zero components are floored at 0.05% and
renormalized (counted in `meta.json`).

## Candidates

All work in ilr coordinates (scipy Helmert basis) with the audit-corrected
per-poll delta-method covariance `H diag(1/p) H' / n`
([model audit](https://github.com/ahemberg/swedishpolls/blob/91900d5af7de0f0565edcf75a2f9ae778f09f891/docs/research/model-audit.md)).

- **recency**: weighted average of polls from the trailing 120 days,
  weight = sample size × exponential decay in midpoint age (half-life
  tuned). Latent interval from a pooled effective sample size; predictive
  spread from weighted between-poll dispersion. The transparent baseline.
- **midpoint-ss**: daily random walk in ilr space (increment `q·I`, `q`
  tuned), Kalman filter, one observation per poll at its fieldwork
  midpoint with full per-poll covariance. This is the audit's
  "one midpoint observation" alternative to repeating aggregates daily.
- **window-avg**: same prior, but each poll observes the *average* of the
  latent state over its fieldwork window (a genuine period-average
  likelihood), solved exactly as a sparse Gaussian Markov random field
  over all days × 8 coordinates. This is the audit's fieldwork-aggregate
  reference.

## Protocol

- **Hyperparameters** are tuned by mean predictive log score on folds with
  cutoffs before 2021-01-01; evaluation uses cutoffs 2021-01-01 to
  2026-07-15 (every 60 days). Cutoffs are frozen publication times: fit on
  polls published on or before the cutoff, score polls published in the
  next 35 days (multivariate log score in ilr, 95% share-interval
  coverage, MAE), with poll noise included, per the audit's guidance.
- **Synthetic known-state**: simulated ilr random walk with fieldwork
  windows, sample sizes drawn from the real polls; multinomial noise;
  scenarios without and with static house offsets plus a 1.5 design
  effect. Measures 50%/95% *latent-state* interval coverage and RMSE
  against the known truth. Five seeded replicates per scenario.
- **Sensitivity**: design-effect multiplier kappa ∈ {1.0, 1.5} on every
  poll covariance; hyperparameter grids recorded in `meta.json`.
- **Thresholds/coalitions**: at 2026-09-07, P(share ≥ 4%) per party and
  one illustrative bloc probability from 4,000 joint draws (Monte Carlo
  SE ≈ 0.8pp near 50%). Unrounded shares compared with the threshold.
- **Resources**: wall-clock and tracemalloc peak for a full-history fit.

## Deliberate limitations (unmeasured here)

- No house-effect model is fitted; house behavior enters only as a
  synthetic-scenario stressor and a descriptive per-house residual table
  since 2022 (`meta.json`). Formal house/era treatment is a model-decision
  item for #6.
- Incomplete compositions are excluded, per the v1 baseline contract, not
  modeled.
- Same-house overlapping fieldwork windows are kept as independent
  observations; the overlap count is reported but no correlation is
  modeled.
- The reserved election holdout is untouched: no estimate is compared to
  any election result, and nothing is tuned against the old site's curve.
- Future-poll prediction for window-avg approximates the target poll as a
  midpoint observation (its own likelihood is only used for fitting).
- Hyperparameter uncertainty is ignored (plug-in), as flagged by the
  audit.

# Development validation entry point

Issue #156 adds `diagnose` to `DevelopmentValidation`. It runs the registered
training-only searches for both fitted methods, then scores the common held-out
rows with their selected parameters and the unchanged recency baseline. The output
contains the searches, manifests, per-poll evidence, fold and subgroup summaries,
statistical failures and available comparisons with the v1 archive in one JSON
file. `tune` remains available for search evidence alone.

```text
diagnose <registration.json> <source.csv> <new-output-directory>/diagnostics.json
```

Use the Maven invocation in `registration-plan.json#/commands`. The output file's
parent must match the registered output location and must not exist. Each run
needs a separate registered location. Existing output is never replaced.

The registration must match the source, implementation, environment and inherited
evidence before fitting. Preparation reads inputs and builds manifests without
fitting. Issue #160 owns the final registration and real-data execution after all
implementation slices are integrated. The tests for #156 use synthetic polls only.

Exit 0 means the executed stage passed its gates, exit 1 retains a blocked result,
and exit 2 rejects the invocation or registration. A numerically available fit can
still have a failed tuning gate. Its diagnostics remain available and the tuning
failure remains blocking. `diagnosticEvidence: incomplete` names missing scoring
prerequisites; summary checks use `unevaluated` when observations or paired folds
are insufficient. Neither state counts as a pass.

Each poll retains its physical row and SHA-256, roster, cutoff, institute, method
era, inclusive fieldwork span, sample size, zero count and original and transformed
compositions. Each method retains one joint log score, the registered stream,
4,000-draw marginal intervals, standardized composition residuals and whitened ilr
residuals. Fold means and subgroup counts come from those same rows. Party
breakdowns have coverage and residuals, with no party-specific joint score.

The paired gate weights folds equally within a roster and uses the registered
lag-three HAC error. Lag-one and lag-six errors are reported beside it. Pooled and
eligible midpoint subgroup coverage and residual checks remain independent;
subgroups below 100 cases are unevaluated. Other methods and cross-classified
party summaries provide descriptive evidence. Missing method-era metadata remains
null in the row evidence.

Historical comparisons require identical active row sets. The archive has no
reference subgroup or reference training-likelihood results, so those comparisons
remain unavailable. Changed row sets cannot be reconstructed from old aggregate
summaries. Corrected-snapshot timing and causal-attribution limitations remain in
the report. Failures never authorize a different grid, estimator or fallback.

Publication remains blocked. This command neither changes the shipped freeze nor
reruns the once-only election audit. The old audit is development evidence for the
revised method, and the method missed the 2026-09-12 prospective cutoff.

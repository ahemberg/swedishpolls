# One bounded development experiment for the statistical release blockers

On 2026-09-13, the owner approved both decisions presented in the #150 review,
replying "q1 agree. q2 agree". This accepts the development experiment described by
[`v2-development-1-proposal-1` at commit d5699e8](https://github.com/ahemberg/swedishpolls/blob/d5699e82fd537ddc6bad817c4884c36a62752401/docs/validation/remediation-protocol-v2-proposal.md),
with the existing release requirements intact. It authorizes the subsequent
implementation and validation work, not publication or a waiver.

The accepted rationale is to test whether lower search bounds constrain the fits,
and to distinguish unavailable FI observations from failed fits. Neither change is
assumed to repair the eight-party reference loss or subgroup misfit. The
[diagnosis](../validation/remediation-diagnosis.md) and
[inventory](../validation/remediation-inventory.json) remain the evidence.

## Approved decisions

Use one fixed 180-point Cartesian grid for both the midpoint candidate and the
independently tuned ilr-window reference:

| Parameter | Values |
| --- | --- |
| Daily ilr walk variance | 0.000003, 0.00001, 0.00003, 0.0001, 0.0003, 0.001 |
| House scale | 0.01, 0.02, 0.05, 0.1, 0.2 |
| Pooled observation covariance multiplier | 0.5, 0.75, 1, 1.5, 2, 3 |

Multipliers below one permit smaller observation covariance than the nominal
multinomial calculation. This is an explicit development-only exception to
[ADR 0001](0001-midpoint-state-space-estimator.md)'s overdispersion assumption.
Overdispersion still means inflated noise; a multiplier below one must not be
called overdispersion. The risk is narrower intervals where subgroup intervals
already under-cover. Better training likelihood does not establish adequacy.
Keep training-only tuning, the frozen tie break, all statistical thresholds and
reproducibility requirements. Any remaining failure or selected grid endpoint
returns to owner review; do not expand the grid again automatically.

Retain all 96 roster/cutoff entries. Mark the two FI folds without training
observations and the 19 after its candidate coverage segment without scoring
observations inactive, each with its original reason. Evaluate all 48 eight-party
folds and the remaining 27 FI folds, including the early FI fold with only two
training observations. Unexpected empty active folds and failed fits still block.
Individual FI estimates remain unavailable until their requirements pass.

## Implementation handoff

The approved proposal specifies the frozen inputs, exact folds, seeds, gates,
reproduction procedures and complete list of invalidated development checks.
Implement that scope in subsequent work:

1. Add explicit versioned protocol inputs and separate output paths. Preserve every
   original v1 report and the production model freeze. Register `v2-development-1`
   with the approved grids, all fold dispositions, row manifests, inherited hashes,
   implementation/environment identities and commands before any revised fit.
2. Retain per-point likelihoods/failures and per-poll scores, residuals and intervals
   for both fitted methods and the baseline. Reconcile the row evidence to fold and
   subgroup summaries, without using held-out scores to choose parameters.
3. Run every affected development check listed in the proposal, including coverage,
   history, uncertainty, remainder, sensitivity, seats, probability precision, drift,
   cross-architecture reproduction and resources. Preserve failed results as well as
   successes. Return unresolved failures to owner review.

Pooled coverage currently passes; this approval does not invoke the grid-mixture
fallback or replace the midpoint estimator. A future release freeze and acceptance
remain separate, even if the revised development checks pass.

The old 2022 audit is development evidence for the revised method after this
post-exposure decision. Preserve its original files and blocked verdict. No reused
2022 result is an untouched holdout, and the once-only audit must not be rerun as a
new audit. Preserve the prospective 2026 registration; this proposed method missed
its 2026-09-12 cutoff and cannot retrospectively qualify. Publication stays blocked.
[#113](https://github.com/ahemberg/swedishpolls/issues/113) retains its separate
publication-constant decisions. No new fit, outcome retrieval or production change
is part of recording this decision.

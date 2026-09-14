# v2-development-1 run 1 report

## Verdict

This development run is blocked and incomplete. It does not authorize publication.
The candidate and reference searches both selected the lower walk-variance and
house-scale boundaries for `fi_candidate_2014_2018` at the 2014-05-15 cutoff.
The registered diagnostic command was rejected because the registered evidence
directory already existed after tuning. The arm64 runtime could not start on this
amd64 host, so cross-architecture reproduction remains unevaluated. These results
require owner review. The grid, seeds, lag, estimator, limits, and output files were
not changed after the results were observed.

## Frozen identities and inputs

- Registration: `docs/validation/v2-development-1/registration.json`, SHA-256
  `47a34a57188e522923b0aa49d0fa7e71874812c38e90218a207ad5a1081e14b7`
- Registered implementation commit:
  `8368ff9b30e62fa0f0a7707b8c5794f00a7b2e42`
- Source: `src/test/resources/polls/audit.csv`, SHA-256
  `27012c05d1e948133a4a2558ec841df62c518b9122117a461ca1f8f6aa9d1608`
- Environment: Linux amd64, Java 25.0.4, Maven 3.9.16, Node 24.13.1,
  npm 11.8.0, and EJML 0.46.1. The registration contains the executable,
  package-manager, dependency, source, container, and inherited-evidence hashes.
- Data boundary: the registered source contains only the pinned pre-2022
  development observations. No outcomes were fetched.

Startup preflight passed before fitting. The registration contains the exact nine
commands and separate output names. It freezes the 180-point grid, 96 folds, row
manifests, parameter-selection convention, seeds, gates, identities, and protected
locations.

## Execution record

| Stage | Exit | Maven time | Result |
| --- | ---: | ---: | --- |
| Model-validation profile | 0 | 20:39 | 244 unit tests and 142 expanded model-validation tests passed, with one skip |
| Preflight | 0 | 12.7 s | Registration, source, implementation, environment, inherited evidence, and output location accepted |
| Tune | 1 | 59:24 | Complete fit evidence, blocked by two boundary selections |
| Estimate | 1 | 19:05 | All 9 executed checks passed; upstream tuning block retained |
| Diagnose | 2 | 10.541 s | Rejected before fitting because `run-1` already existed |
| Measure | 1 | 32:26 | All 10 executed checks passed; upstream tuning block retained |
| Reproduce amd64 | 0 | 03:02 | 90,000 values retained; release authorization stayed false |
| Reproduce arm64 | 1 | under 1 s | Pinned image failed with `exec format error`; no arm64 artifact was written |
| Compare | 1 | 12.598 s | Cross-architecture check unevaluated because arm64 evidence is missing |

The available stage logs and the final model-validation profile log are in `logs/`.
The original preflight console log was not captured, so that log is missing
evidence. `preflight.json` retains its ready status, hashes, and fold counts. The
generated JSON files retain their status, failure reasons, summaries, numerical
evidence, and environment identities.

## Search and measurement results

The tuning artifact contains all 27,000 attempts from 150 method-fold searches.
All attempts resolved. Of the 96 registered folds, 75 were active. The 21 reviewed
FI entries remained inactive: 2 had no eligible training observation and 19 had no
held-out composition. Their row manifests and exclusions remain in `tuning.json`.

Two selections failed the interior-optimum gate. Both were for
`fi_candidate_2014_2018` at 2014-05-15, one for `midpoint_candidate` and one for
`ilr_window_reference`. Both selected walk variance `0.0000030`, house scale
`0.01`, and covariance multiplier `1.5`. The walk variance and house scale are at
their lower grid boundaries.

The estimate and measurement stages passed coverage support, seeded reproduction,
interval precision, comparable remainder, seat totals, joint-probability coherence,
probability Monte Carlo error, threshold-probability precision, and
majority-probability precision. Measurement also passed snapshot drift at
`0.43730250968707196` points against the unchanged `0.44` limit. The measured path
took 1,133,198 ms and peaked at 518,356,672 bytes of used heap. The ten-second target
was missed but is nonblocking. The 30-minute deployment-host requirement is
unevaluated because this run accepts no deployment host.

The amd64 draw file has SHA-256
`a6cbbc0dd5be5c63f74e5f0e94c5eb67b93745a90a2d946056f274414d1af1a8`.
The missing arm64 artifact was not replaced with an amd64 run. Consequently, exact
amd64 to arm64 comparison is unevaluated.

## Original reason aggregate

The immutable `docs/validation/remediation-inventory.json` supplies each original
release-reason identity, evidence group, kind, and propagation relationships.
`development-aggregate.json` copies all 50 identities one by one and records each
revised disposition, evidence pointer, detail, and propagated dependent check. The
table below summarizes that machine-readable aggregate. Ranges are inclusive.
Inactive and unevaluated entries do not count as passes.

| Release reason indices | Count | Evidence group | Revised disposition | New evidence |
| --- | ---: | --- | --- | --- |
| 0 | 1 | aggregate wrapper | unevaluated prerequisite | Diagnostic aggregate was not produced |
| 1, 3, 5 | 3 | `candidate-grid` | evaluated fail | Candidate boundary selection remains for one active fold |
| 2, 4, 10, 11 | 4 | `fi-no-training` | inactive | Two registered folds remain inactive with no eligible training observation |
| 6 | 1 | `fi-no-training` | inactive | Same registered inactive prerequisite and propagation |
| 7 | 1 | `reference-loss` | unevaluated prerequisite | Paired diagnostic scoring did not run |
| 8 | 1 | `reference-grid:eight_party_2010` | evaluated pass | All active reference selections for this period were interior |
| 9 | 1 | `reference-grid:fi_candidate_2014_2018` | evaluated fail | Reference boundary selection remains for one active fold |
| 12 through 30 | 19 | `fi-no-heldout` | inactive | Nineteen registered folds remain inactive with no held-out composition |
| 31 through 49 | 19 | registered subgroup groups | unevaluated prerequisite | Per-poll and subgroup diagnostics did not run |

Totals are 50 original reasons: 24 inactive, 4 evaluated failures, 1 evaluated pass,
and 21 unevaluated prerequisites. The two boundary selections are the new concrete
failures. The rejected diagnostic stage is a new execution failure and leaves the
paired, subgroup, dependence, coverage-diagnostic, and per-poll requirements
incomplete. The missing arm64 run independently leaves cross-architecture
reproduction unevaluated. No omitted requirement is recorded as a pass.

## Preserved release evidence

All 15 registered protected locations were byte-identical after the run.
`protected-before.sha256` and `protected-after.sha256` retain both hash sets. This
includes the original v1 evidence, the source, the registration inputs, and the
shipped model freeze. The original 2022 audit remains at
`docs/validation/release-audit.json` with its blocked verdict and SHA-256
`cb1d97d9b65526fa3501f17778ade2ac6741a52c5966194fabd615267bee3ad7`.
It is development evidence for the revised method, not a rerun or an untouched
holdout. The shipped model freeze remains unchanged with SHA-256
`65f1e9e4e8ae3dd3ddbf20004f211cabf0fac56eb3689484c2635d869cda38fb`.
The 2026-09-12 prospective cutoff remains missed.

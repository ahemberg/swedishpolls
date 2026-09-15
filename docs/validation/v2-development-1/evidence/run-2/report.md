# v2-development-1 run 2 report

## Verdict

The follow-up execution completed. The development result remains blocked and does
not authorize publication. Diagnostics now ran, and native amd64 and arm64 reproduction
passed. The remaining failures are statistical, with four small institute subgroups
still unevaluated because they have fewer than 100 cases.

The owner authorized this follow-up on 2026-09-14 after reviewing run 1 and supplied
SSH access to `pinas`. The base includes merged #170. No grid, seed, fold, estimator,
selection rule or numerical limit changed. The old 2022 audit remains development
evidence with its original blocked verdict; it is not an untouched holdout. The
2026-09-12 prospective cutoff remains missed. No election outcomes were fetched.

## Registration and execution

- Implementation commit: `539b1f3`, based on #170 at `a411609`.
- Registration committed before fitting at `6417f36`. Native image resolution was
  recorded before fitting at `f750427`.
- Registration: `../../registration-run-2.json`, SHA-256
  `fccbc0eec6579ecc2fe7b61a4db3cfc3fea074493f2b9c7cc3910d53a5a4401e`.
- Source SHA-256: `27012c05d1e948133a4a2558ec841df62c518b9122117a461ca1f8f6aa9d1608`.
- Exact commands and runtime preparation: [execution registration](../../run-2-execution.md)
  and [registered plan](../../registration-plan-run-2.json).

The directory collision was fixed at the operator entry point. Tuning and diagnostics
can write separate files in the same registered directory; existing evidence files
and predictive draws cannot be overwritten. A synthetic test runs that sequence and
checks that tuning bytes remain unchanged. Historical registration tests were adjusted
to distinguish the archived run from the active registration. The initial failing test
logs are retained alongside the passing focused and full verification logs.

| Stage | Exit | Wall time | Result |
| --- | ---: | ---: | --- |
| Full verify with model-validation profile | 0 | 22m 53s | 251 unit tests; 92 integration and 145 model-profile test executions, each with one skip; static analysis passed |
| Prepare and preflight | 0 | See logs | Frozen registration accepted, 96 folds, 75 active |
| Tune | 1 | 61m 23s | Complete evidence, two boundary selections remain |
| Estimate | 1 | 19m 29s | All nine executed checks pass; tuning block retained |
| Diagnose | 1 | 62m 49s | Complete predictive evidence; paired and subgroup failures remain |
| Measure | 1 | 33m 07s | All ten executed checks pass; tuning block retained |
| Native amd64 reproduction | 0 | 97s | 90,000 values retained |
| Native arm64 reproduction | 0 | 104s | 90,000 values retained on `pinas` |
| Cross-architecture comparison | 0 | 15s | Maximum difference below registered limit |

The four fitting stages ran between 2026-09-14 23:40 and 2026-09-15 02:36, Europe/Stockholm.
Both reproduction jobs used the registered Temurin 25.0.4 image and identical copied
classes and dependency JARs. The arm64 job used an isolated directory and did not touch
the running service or its database. `runtime-files.sha256` records the copied bytes;
checks passed locally and on the remote host. Launcher scripts are retained as executed
orchestration records, not as commands for overwriting this completed run.

## Statistical results

All 27,000 tuning attempts resolved across 150 method-fold searches. The full tuning
fold evidence is identical to run 1. Both methods still select walk variance `0.000003`
and house scale `0.01`, the lower endpoints, for the FI fold at 2014-05-15. Its covariance
multiplier is `1.5`. The 21 reviewed FI inactive folds remain inactive with their reasons.

Diagnostics scored all 48 eight-party folds and 27 active FI folds. It retains 1,710
predictive draw files and their hashes, per-poll scores, residuals, coverage indicators,
fold summaries, dependence measures and historical comparisons.

| Roster | Mean score gain over recency | Mean score difference from window reference | Lag-3 standard error | Paired gate |
| --- | ---: | ---: | ---: | --- |
| Eight-party | 1.159959 | -0.023579 | 0.011921 | Fail |
| FI candidate | 0.759872 | 0.017259 | 0.013659 | Pass |

Eight-party subgroup failures remain for S, KD, OTHER, Sifo and Skop. FI subgroup
failures remain for S, KD, FI, RESIDUAL, Inizio, Sifo, Skop and fieldwork of 1-7 days.
The exact failed coverage and residual metrics are retained in `diagnostics.json` and
in the one-to-one original-reason accounting. All six required residual-autocorrelation
checks pass. SCB and United Minds remain unevaluated in both rosters due to insufficient
subgroup cases; these are not passes.

The estimator passes coverage support, seeded reproduction, interval precision,
comparable remainder, seat totals, joint-probability coherence, Monte Carlo error,
threshold precision and majority precision. Snapshot drift is `0.43730250968707196`
points against the unchanged `0.44` limit.

The timed estimator path took `1,153,732` ms and peaked at `560,280,928` bytes of used
Java heap. It misses the nonblocking ten-second target. The separate 30-minute
accepted-deployment-host requirement remains unevaluated under #28; this development
measurement does not complete launch acceptance.

## Native reproduction

The comparison checked 90,000 values. Of these, 43,857 differ between architectures.
The maximum absolute difference is `1.7763568394002505e-14` percentage points, below the
registered `2e-14` limit. This is a tolerance pass, not byte identity.

- amd64 draw SHA-256: `a6cbbc0dd5be5c63f74e5f0e94c5eb67b93745a90a2d946056f274414d1af1a8`.
- arm64 draw SHA-256: `91c45a37adf858861935d7e4fcfe04b8e79e2af2b8147a32eacdec03c596d7d2`.

The amd64 draw bytes also reproduce the retained run-1 amd64 file. Both native manifests,
the image index, draw files and comparison result are retained here.

## Original reasons and preservation

`development-aggregate.json` accounts for all 50 original release reasons by identity
and preserves their propagation relationships. It records 25 evaluated failures,
24 inactive reasons and one evaluated pass. The pass is the eight-party reference-grid
requirement. The 24 inactive reasons are repeated consequences of the 21 approved
inactive FI folds, not 24 separate folds. None of the original reasons remains
unevaluated because of the run-1 execution failures. The four small-subgroup limitations
and the separate host acceptance requirement remain explicit.

The aggregate retains all 27 diagnostic reasons and the propagated tuning reasons in
later stages. `aggregate.py` derives the accounting from retained evidence and asserts
that all 50 original identities occur exactly once. It performs no fit.

All 38 protected files are byte-identical before and after execution, including run 1,
the original v1 evidence, the source, the prior registrations and the shipped model
freeze. `protected-before.sha256` and `protected-after.sha256` are identical. All 1,710
unique predictive artifacts were checked against their recorded hashes. `CHECKSUMS.sha256`
covers the final retained evidence.

The next step is owner review of the statistical failures under #160 and #151. This run
supports no automatic grid expansion, estimator substitution, tolerance change or waiver.
Publication remains blocked.

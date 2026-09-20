# synthetic-recovery-v2: execution report

The registered execution of the accepted
[`synthetic-recovery-v2` protocol](../synthetic-recovery-protocol.md).

**Outcome: `complete_recovery_demonstrated`. All four stages completed, all 72
primary cells demonstrated recovery, all completed predictive output reproduced,
and the protected evidence stayed unchanged.**

This result establishes recovery for the registered synthetic scenario. It does not
authorize a model change, a new real-data experiment, a release-gate waiver, a
publication or a deployment.

## Identities

| | |
| --- | --- |
| Protocol | `docs/validation/synthetic-recovery-protocol.md`, sha256 `bf58a717fb1fd417c95634d658a5623f255e3b00a7c896391bb813221dbe60e6` |
| Registration | `registration.json`, sha256 `31caea039dc55558f80a07fb23801d8c8e98635dbda8295b3a0f0dd05c746846` |
| Preflight | External record, sha256 `8b0d2f9aebf9ca544347544897c66609cffde0446cd99fc0c90a5b7b5749c188` |
| Execution identity | `execution.json`, sha256 `4a4247ac3b487bed5a3ef7959404803f4521ef6f94b2f38a6f88f5487eaf8998` |
| Implementation commit | `eaf52c4fb7a6aad8916f2b7f23cc9911376a86fb` |
| Host | `alex-lenovo`, amd64, 4 physical cores, 4 workers |
| Toolchain | OpenJDK `25.0.4+7-1-24.04-Ubuntu` |
| Run size | 10,000 datasets per convention and stage, 4,000 predictive draws, master seed 20260916 |
| Deadline | 2026-09-19 22:55:05Z through 2026-09-20 22:55:05Z |

The registration pins 68 runtime dependencies by digest.

## Repository checks

The registered check ran at the implementation commit before registration:

| | |
| --- | --- |
| Command | `./mvnw --batch-mode --no-transfer-progress -Pmodel-validation verify` |
| Result | Exit 0 in 18m 12s |
| Tests | 314 unit, 104 integration and 194 model-profile executions; no failures |
| Static analysis | PMD, SpotBugs and Spotless passed; SpotBugs reported 0 instances |
| Retained log | `checks/logs/model-validation.log`, sha256 `98a1f53ce39d57a7778f2c58603265417e0d38fb8183426faa4780311ef49b67` |

The approved regression refits ran unchanged. No `*.full` evidence-rebuilding flag
was set, and the once-only audit was not rerun. These checks are software evidence,
not recovery evidence.

## Preflight

Preflight started the 24-hour clock at 2026-09-19 22:55:05Z. Four workers reached
thermal steady state, then the sustained window measured each convention and stage.
Coverage from preflight was not inspected.

| | Projected | Registered limit | Within |
| --- | --- | --- | --- |
| Duration | 15h 00m formal plus 5m 41s preflight | 24h | yes |
| Storage | 12.34 GB retained, 25.75 GB required | 3.11 TB usable | yes |

The first preflight launch was killed by the operator's foreground timeout before it
wrote a preflight record. The partial destination was catalogued and removed before
the registered launch. The successful launch used a fresh destination and is the only
preflight record read by the execution identity.

## Formal execution

Formal execution ran from 2026-09-19 23:01:48Z through 2026-09-20 10:11:22Z,
11h 09m 34s of wall time. Every stage fit its budget. No stage was deferred, no
watchdog interruption fired, no numerical failure occurred, and coverage never
affected scheduling.

| Convention | Stage | Completed | Stage time | Verdict |
| --- | --- | ---: | ---: | --- |
| midpoint | known | 10,000 / 10,000 | 12m 13s | demonstrated |
| ilr_window | known | 10,000 / 10,000 | 14m 48s | demonstrated |
| midpoint | estimated | 10,000 / 10,000 | 2h 39m 06s | demonstrated |
| ilr_window | estimated | 10,000 / 10,000 | 7h 47m 27s | demonstrated |

Both controls demonstrated recovery before either estimated stage started. The
known-stage verdict and estimated-stage verdict are both `demonstrated`; the combined
recovery verdict is `demonstrated`.

## Cell accounting

`results.json` lists all 72 primary cells. Every cell is measured and every verdict is
`demonstrated`. The table gives the range across the nine components within each
convention, stage and interval level.

| Convention | Stage | Level | Coverage range | Simultaneous interval extent |
| --- | --- | ---: | ---: | ---: |
| midpoint | known | 50 | 0.497472 to 0.501164 | 0.493687 to 0.504652 |
| midpoint | known | 95 | 0.948532 to 0.949884 | 0.946889 to 0.951481 |
| ilr_window | known | 50 | 0.498800 to 0.501764 | 0.495017 to 0.505309 |
| ilr_window | known | 95 | 0.949056 to 0.950036 | 0.947311 to 0.951625 |
| midpoint | estimated | 50 | 0.496148 to 0.500348 | 0.492342 to 0.503842 |
| midpoint | estimated | 95 | 0.947808 to 0.949368 | 0.946107 to 0.950982 |
| ilr_window | estimated | 50 | 0.497604 to 0.500960 | 0.493799 to 0.504516 |
| ilr_window | estimated | 95 | 0.948120 to 0.949644 | 0.946339 to 0.951240 |

The accepted recovery bands are 0.47 to 0.53 for the 50% intervals and 0.93 to 0.97
for the 95% intervals. Every simultaneous interval lies inside its band.

The midpoint estimated stage selected a grid endpoint in 2 of 10,000 datasets, both
at the lower walk-variance endpoint. The ilr-window estimated stage selected an
endpoint in 5 datasets: 2 at the lower walk-variance endpoint and 3 at the lower
house-scale endpoint. The protocol retains these valid selections in the assessment.

## Reproduction

The successful reproduction ran from 2026-09-20 15:31:00Z through
2026-09-20 17:40:17Z. It regenerated and compared:

- 40,000 completed datasets;
- 1,000,000 predictive draw arrays;
- all interval summaries, coverage indicators and aggregate cells.

The outcome is `reproduced`, with no findings. Each stage reproduced 10,000 datasets
and 250,000 arrays, and recomputed the same `demonstrated` verdict.

An earlier reproduction attempt started at 10:38:19Z and was terminated by a host
reboot before it wrote any record. After takeover, an additional process was started
because the surviving 15:31 process had been mistaken for dead. The duplicate was
detected and stopped after four minutes. It wrote no record and did not modify retained
evidence. The 15:31 process remained the sole writer and produced `reproduction.json`
more than five hours before the deadline.

## Committed rollups

Each digest covers a 10,000-line manifest stored beside the external evidence. The
manifest records every retained file's relative path, size and sha256.

| Convention | Stage | Files | Manifest sha256 |
| --- | --- | ---: | --- |
| midpoint | known | 10,000 | `3c2f01944348bae7c60c52bbbf3be8fa8ef18be6ec5b6d6c9fdc2d714c4efc49` |
| ilr_window | known | 10,000 | `d004b91d4653b0c78315c9ee878290e635120ec22d0f3fff8e5491c00d94a927` |
| midpoint | estimated | 10,000 | `8f067c276f3433cd482aa17db3ff681b61a6dcb370ce6e12059935bba0b7b941` |
| ilr_window | estimated | 10,000 | `b607d846cb4ec6e7aa0af190764c83abdf1d4f77ec1b7931720b162e46979442` |

Each manifest's line count and sha256 were checked independently after `summarize`.

## Report command correction

The registered `report` command named `formal/report.json` as both the experiment report
it reads and the final report it writes. The command correctly refused to overwrite the
existing experiment report, which made the registered command impossible to execute as
written.

The operator reran the same command with only its output changed to
`formal/final-report.json`. It read the registered execution, experiment and reproduction
records without changing them. The corrected command returned 0 and reported
`complete_recovery_demonstrated`. The invalid registered command and its refusal are
retained in the issue record.

## Protected evidence

The run recomputed each protected digest before and after formal execution. All three
matched the registration:

| Location | Sha256 | State |
| --- | --- | --- |
| `src/main/resources/publication/model-freeze.json` | `66b854018f921c34141fc455bd3130743e8796e03c59f95b23ecd7eeb51d27dc` | unchanged |
| `docs/validation/v2-development-1/evidence/run-1` | `8c55ed95793d084a4412286589ada97e069fabf4a669993d42c0a55bb6b69468` | unchanged |
| `docs/validation/v2-development-1/evidence/run-2` | `3f2c62344bae05daf2912941a7b3cdc4a930ae57d2fc33e3afb0478f0eef5bf4` | unchanged |

## What this establishes

Both known-parameter controls and both fitted-parameter stages recovered their nominal
coverage on data generated from the registered model assumptions. The implementation
therefore does not reproduce the real-data coverage failure when its own assumptions
hold, including after fitting the frozen parameter grid.

This result points away from the synthetic-recovery implementation as the cause of the
real-data failure. It does not prove that any particular model misspecification caused
that failure, validate the model against real opinion, or change the standing release
gates. Those questions require separately registered decisions and evidence.

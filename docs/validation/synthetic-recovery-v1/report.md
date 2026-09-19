# synthetic-recovery-v1: execution report

The registered execution of the accepted
[`synthetic-recovery-v1` protocol](../synthetic-recovery-protocol.md).

**Outcome: `stopped_at_preflight_infeasible`. No formal dataset was generated, and no
statement about recovery is made.** The registered host cannot carry the run inside
either registered resource limit. This is a valid stopping result of the protocol,
not a failure of the software and not a statement about the estimator.

## Identities

| | |
| --- | --- |
| Protocol | `docs/validation/synthetic-recovery-protocol.md`, sha256 `897063ff08c05f15ac2b598125424ec18163396bf66f3c6d08399e2cd142b065` |
| Registration | `registration.json`, sha256 `5944ba9db005cb0f6d58c2471b1e1c00013e81f40dc32db554f090fe8bad8ed4` |
| Plan | `plan.json`, frozen into the registration and unmodified since |
| Implementation commit | `7794b6ce2adb4e228f1ef2c6b821dfd75c6fd351` |
| Execution identity | not committed; `commit` requires a feasible preflight |
| Host | `alex-lenovo`, x86_64, 8 cores, one registered worker, 15.5 GiB RAM |
| Toolchain | OpenJDK 25.0.4, Linux 6.17.0-1020-oracle |
| Container image | `eclipse-temurin@sha256:b4c93a50…` as pinned in `pom.xml` |
| Run size | 10,000 datasets per convention, 4,000 predictive draws, master seed 20260916 |

Sixty-eight runtime dependency jars are pinned by digest in the registration.

## Repository checks

Run before preflight and retained separately under `checks/`. The working tree stood at
the registered implementation commit with no local modification; `checks.json` records
the commit the operator read from `git rev-parse HEAD`, since the Maven log carries no
commit identity of its own.

| | |
| --- | --- |
| Command | `./mvnw --batch-mode --no-transfer-progress -Pmodel-validation verify` |
| Exit code | 0 |
| Elapsed | 17m 31s |
| Tests | 304 unit, 104 integration and 184 model-profile executions; no failures, one skip in each of the latter two |
| Static analysis | PMD, SpotBugs (0 bug instances) and Spotless passed |

The approved archived-data regression refits in `DevelopmentTuningIT` and
`DevelopmentDiagnosticsIT` ran unchanged. No `*.full` evidence-rebuilding flag was set,
the once-only audit was not rerun, and none of these test fits is recovery evidence.

## Commands

The registration freezes each command; `preflight` and the infeasibility `report` are
the two that ran.

```
./mvnw -DskipTests spring-boot:run \
  -Dspring-boot.run.main-class=se.swedishpolls.estimation.SyntheticRecovery \
  -Dspring-boot.run.arguments='register docs/validation/synthetic-recovery-v1/plan.json docs/validation/synthetic-recovery-v1/registration.json'

./mvnw -DskipTests spring-boot:run \
  -Dspring-boot.run.main-class=se.swedishpolls.estimation.SyntheticRecovery \
  -Dspring-boot.run.arguments='preflight docs/validation/synthetic-recovery-v1/registration.json /home/alex/evidence/synthetic-recovery-v1/preflight'

./mvnw -DskipTests spring-boot:run \
  -Dspring-boot.run.main-class=se.swedishpolls.estimation.SyntheticRecovery \
  -Dspring-boot.run.arguments='report /home/alex/evidence/synthetic-recovery-v1/preflight/preflight.json /home/alex/evidence/synthetic-recovery-v1/preflight/report.json'
```

`register` and `report` returned 0. `preflight` measured the run and then left the
process exit code 5, the registered code for a preflight that does not fit the host.
`commit`, `experiment`, `reproduce` and the five-argument `report` did not run.

## Evidence location

The registered preflight destination is
`/home/alex/evidence/synthetic-recovery-v1/preflight`, and the registered formal
destination `/home/alex/evidence/synthetic-recovery-v1/formal`, which was never
created. The whole preflight tree is copied verbatim into `preflight/` here, 19 files
and 4.7 MiB, with `preflight/CHECKSUMS.sha256` added beside them over the 19 originals;
`sha256sum -c` passes against the committed copy.

## Preflight measurements

One warm-up and three timed datasets per convention, indices 0 to 3, on
`preflight`-phase streams, timing generation, the known-parameter prediction path, all
180 training-grid evaluations, estimated-parameter prediction, evidence writing and
hashing, and full reproduction, in the intended evidence format. Preflight coverage was
not inspected and enters no summary.

| Convention | Stage | Slowest measured | Largest retained |
| --- | --- | --- | --- |
| midpoint | known | 0.392 s | 285,948 B |
| midpoint | estimated | 3.016 s | 326,197 B |
| ilr_window | known | 0.415 s | 286,129 B |
| ilr_window | estimated | 8.211 s | 326,360 B |

Generation is charged to the known stage; each stage carries its own evidence work and
reproduction. The estimated stages are dominated by the 180-point training grid, which
costs about 2.6 s per midpoint dataset and about 7.8 s per ilr-window dataset.

## The resource verdict

| | Projected | Registered limit | Within |
| --- | --- | --- | --- |
| Duration | 41.79 h formal plus 53.0 s preflight, 41.80 h total | 24 h | no |
| Storage | 11.41 GiB retained, 23.81 GiB required | 10.98 GiB usable | no |

The usable figure is what the preflight itself measured on the evidence filesystem at
the moment it ran. `host-capacity.json` records 10.99 GiB free a minute earlier; the two
are separate measurements of a filesystem in use, and the verdict rests on the first.

`T`, the sum of the four slowest stage times, is 12.035 s per repetition. The
projection is `10000 × T × 1.25` plus elapsed preflight. The storage requirement is
twice the projected retained output plus the 1 GiB log reserve.

Both registered limits are exceeded, the wall clock by a factor of 1.74 and the free
disk by a factor of 2.17. The run stopped. Repetitions were not lowered, windows not
shortened, grid points not omitted, precision not changed, the host not swapped and no
protected evidence deleted.

## Cell accounting

The protocol plans 72 primary cells: four method-and-stage combinations, each covering
nine components — S, M, SD, V, C, KD, L, MP and OTHER — at the 95% and 50% interval
levels.

| Method | Stage | Cells | Status |
| --- | --- | --- | --- |
| midpoint | known | 18 | not run — the registered host cannot carry the run within its resource limit |
| ilr_window | known | 18 | not run — same |
| midpoint | estimated | 18 | not run — same |
| ilr_window | estimated | 18 | not run — same |

All 72 are explicitly not run, so there is no coverage fraction, no Monte Carlo
standard error and no confidence-interval width to report, and no zero-variance cell to
verify. Endpoint selections and endpoint frequencies apply only to an executed
estimated stage and are likewise not reported. `recovery` is `not_run`.

Two things the retained evidence does not carry, both properties of the workflow rather
than of this run. The registration pins the fixed covariance and its noise factor by
digest alone, so the matrix in the dataset evidence cannot be checked against the
registration without recomputing it from the implementation; and `registration.json`
still reads `"preflight": "not_run"`, because nothing writes that field back. The frozen
document is left exactly as it froze.

Both were fixed afterwards, in #214. `register` now writes the covariance and its noise
factor into the registration as numbers and rejects a registration whose retained matrix
has moved, and the two lifecycle-status fields are gone, since a document pinned by digest
cannot carry a status that changes. Everything above is evidence of the run that stopped,
and stays byte-identical: `registration.json` keeps the format and the sha256 it froze
under, and a registration in that format is no longer accepted. The owner decision below
registers afresh in either case.

## Numerical and resource outcomes

No numerical failure occurred. Every preflight fit, search and reproduction completed
and returned finite values. The only failures are the two resource projections above.

## Reproduction

Not run. Reproduction regenerates completed formal predictive arrays, and there are
none. Preflight did exercise and time the full reproduction path on its own eight
datasets; that is a cost measurement, not reproduction evidence.

## Protected evidence

Digests recomputed after the run and compared against the registration:

| Location | State |
| --- | --- |
| `src/main/resources/publication/model-freeze.json` | unchanged |
| `docs/validation/v2-development-1/evidence/run-1` | unchanged |
| `docs/validation/v2-development-1/evidence/run-2` | unchanged |

The full digests are in `protected-after.txt`.

## What this does and does not establish

The software executed the registered protocol correctly and stopped where the protocol
says to stop. That is a software outcome. It is not statistical recovery, and it is not
a failure of recovery: nothing was measured about the midpoint estimator or the
fieldwork-window reference. The conclusions of #160 and its follow-up stand exactly as
they were.

No outcome here authorizes a model change, a new real-data experiment, a release-gate
waiver, a publication or a deployment.

## The next owner decision

The owner has to choose one, and the choice needs recording before any further
execution:

1. **Register a larger host.** The run needs roughly 1.8× the throughput and 2.2× the
   free disk of `alex-lenovo`, with one worker still. That is a new registration, a new
   preflight and a new 24-hour clock.
2. **Revise the accepted protocol.** Lowering N, the draw count, the grid or the
   retained evidence would fit this host, but each is a scientific setting the protocol
   freezes. Changing one is an owner decision recorded against #184, not an
   implementation choice, and it would change what the experiment can conclude.
3. **Raise the 24-hour cap and the storage limit.** Same standing: a registered limit,
   revised by decision, not in passing.

Until one is recorded, `synthetic-recovery-v1` has no result.

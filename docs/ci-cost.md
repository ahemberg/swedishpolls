# CI cost measurements

## Baseline

[PR run 34626702804](https://github.com/ahemberg/swedishpolls/actions/runs/34626702804),
2026-09-11, head `64480b8b7b2acdd1735fd1ef98deb03ee037fbc5`, used `ubuntu-24.04`.
There were no configured Maven or npm dependency caches. The build job took 13m36s,
Fallow took 30s, and the combined runner time was 14.1 minutes. That is a consumption
proxy, not billed usage or a monetary cost. The timing API returned zero billable
milliseconds for every job, so it supplies no usable billed-cost measurement.
The jobs overlapped, so elapsed check time
was 13m36s, excluding queue time. Images were skipped for this PR.

The Actions jobs API supplies step timestamps. The archived build log supplies Maven
plugin boundaries and test-suite timings. Adjacent plugin timestamps give the following
approximate phase durations, including any resolution or startup work between them.

| Build work | Seconds |
| --- | ---: |
| Job start through first Maven plugin | 25.4 |
| Clean | 0.1 |
| Enforcer and initial Spotless | 8.5 |
| Node/npm installation | 4.9 |
| npm ci | 4.3 |
| Frontend check and build | 1.6 |
| Resources and Java main/test compilation, including Error Prone | 25.8 |
| Unit tests, including fork startup | 25.2 |
| JAR packaging and repackaging | 2.5 |
| Integration tests, including fork/container startup and shutdown | 675.5 |
| PMD and Failsafe verification | 10.6 |
| SpotBugs | 28.7 |
| Final Spotless, report upload and job completion | 3.0 |

Dependency download time is interleaved with plugin work and cannot be isolated from
these boundaries. The Maven command step took 13m25s; Maven reported 13m22s internally.
The body's 12m53s has not been tied to this run and is not used as the baseline.

| Slowest suite | Seconds |
| --- | ---: |
| PublicationIT | 170.60 |
| DevelopmentDiagnosticsIT | 155.40 |
| DevelopmentTuningIT | 71.83 |
| EstimateHistoryIT | 46.18 |
| JointUncertaintyIT | 46.17 |
| ApiV1IT | 45.63 |
| PageIT | 42.64 |
| ComparableRemainderIT | 33.29 |

`ReleaseAuditIT` took 0.211s. Its default execution checks archived evidence; full
refitting requires `audit.full=true`. Direct database tests already share a container.
The integration-test phase accounts for about 83% of build job time.

Fallow's checkout took 2s, Node setup 6s, npm installation 6s and audit action 6s.
The remaining 10s covered runner setup, report upload and teardown.

## Changes and limits

Maven dependency caching and npm download caching target repeated resolution and
installation. Both npm installs still run. No application classes, test results or
`node_modules` are cached. Build and Fallow retain separate jobs, and `clean verify`
still rebuilds and checks the application on every run. Full-history checkouts remain
because their measured cost is only 2s and Fallow needs its comparison base.

The cache inputs follow the pinned actions' contracts:
[setup-java](https://github.com/actions/setup-java/blob/dd06d9cba3e5552c54d9f8ea23572deb30010f7c/action.yml),
[setup-node](https://github.com/actions/setup-node/blob/820762786026740c76f36085b0efc47a31fe5020/action.yml),
and [cache](https://github.com/actions/cache/blob/caa296126883cff596d87d8935842f9db880ef25/action.yml).
The build caches npm downloads directly because Maven installs its pinned Node and npm.

[Workflow concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
groups PR runs by workflow name and PR number. A later run for that PR cancels obsolete
work. Push and manual runs use their unique run ID and do not cancel each other.
This avoids repeated full builds during rapid PR updates without cancelling main's
image publication.

## Execution inventory

Every PR revision that is not superseded, and every main push, runs all Maven formatting,
frontend checking/building, Java compilation, unit and integration tests, PMD and SpotBugs
checks, plus the complete existing Fallow gate with its existing baselines and comparison
base. No suite moves to a schedule or becomes conditional on changed paths.

Only main pushes build, smoke-test and publish images. That job still needs both quality
jobs and downloads the application classes verified in the same run.

## Validation still required on GitHub

The billing failure in [run 34635739176](https://github.com/ahemberg/swedishpolls/actions/runs/34635739176)
prevented either check from starting. Local checks cannot validate GitHub's cache service
or cancellation behavior. Until hosted runs execute, no speedup is claimed.

For the implementation PR, record a cold-cache run and a warm-cache run with run URL,
commit, cache hit/miss messages, job durations and summed runner minutes. Compare with
the baseline while accounting for intervening application changes and runner variability.
Confirm both runs execute every gate. Then supersede an active PR run and verify the old
run is cancelled, the new run executes all checks, and unrelated runs remain independent.
Leave these acceptance criteria incomplete while billing blocks execution.

# Build verification

Verified locally on 2026-09-08 for #16. These checks exercise the build foundation;
they do not certify the production estimator, final election audit or deployment.

| Check | Result |
| --- | --- |
| Java 25.0.4, Maven wrapper 3.9.12, Node 24.13.1, npm 11.8.0 | Clean `./mvnw -B -ntp clean verify` passed |
| TypeScript 5.9.3 and Biome 2.5.12 | Passed within the Maven build |
| `PredictiveComparisonTest` | Failed before implementation, passed after; known paired lag-3 SE, strict baseline improvement, reference failure and invalid inputs checked |
| `ApplicationIT` | Packaged Spring JAR returned HTML and bundled JavaScript; PostgreSQL 18.4 reported successful Flyway V1 migration |
| Full test suite | Two tests passed, none skipped |
| Fallow 3.23.0 with committed baselines | Clean audit exited 0 |
| Temporary unused TypeScript file | Same audit exited 1; probe removed |
| Temporary invalid Fallow configuration | Same audit exited 2; configuration restored |
| Standards review | No findings |
| Spec review | No remaining findings after institute weighting, centered covariance and outcome-fixture corrections |

For the local database check, Compose used project `swedishpolls-16` and port
55432 because 5432 was occupied. Maven received
`DATABASE_URL=jdbc:postgresql://localhost:55432/swedishpolls`. Credentials were
local integration credentials. The test starts and stops its own application.

GitHub-hosted workflow execution and required-merge-check enforcement are not
claimed by these local results. The private repository's plan returned HTTP 403
for branch protection. The owner accepted this limitation on 2026-09-08, keeping
the repository private on the free plan. Enforced merge checks are waived, not
a remaining blocker. No image was published during #16. Fallow reports and Maven test results are
configured as CI artifacts; local generated reports are ignored by Git.

## Production image verification

Verified locally on 2026-09-09 for #27. Jib built separate amd64 and arm64 images
from the pinned Temurin 25.0.4+7 Noble index. The exact images ran the synthetic
headless Java2D check natively and under QEMU respectively. Both produced a 640 by
120 PNG using DejaVu Sans and displayed `åäö ÅÄÖ`. The amd64 Compose setup waited
for PostgreSQL, served the bundled application, wrote publication storage as UID
10001 and retained that file across an application restart.

The `images` job repeats both image checks and the full Compose readiness, non-root
write and restart-persistence check for both architectures on `main`. It uploads PNGs
and Compose logs before publishing either image. Publishing has
`needs: [build, fallow]`; the representative unused-export regression recorded above
therefore blocks image publication as well as the merge check, without regenerating
or absorbing a baseline. Hosted image publication and arm64 Compose evidence require
the first successful `main` run of this workflow.

Hosted verification on 2026-09-10 closes the loop: main run
34462525493 for commit `c97794c` passed `Build and integration`, `Fallow` and
`Build, verify and publish images`. The image job rebuilt both commit-tagged
images from that run's `verified-classes` artifact, passed the Java2D and
full Compose checks on both architectures, and pushed
`ghcr.io/ahemberg/swedishpolls:c97794c…-amd64`
(digest `sha256:4ed6f4c5b9e12536e5c79077a7b95d78abe934eb87aed2a89e96041c92cb23a6`)
and `…-arm64` (digest
`sha256:d65388cb3e0c4cbe4e0a35d3a33822194ac0dcd9ef79a69adec50d26867ba6d7`).
The job log records Docker Compose database readiness, UID 10001 publication
writes and restart persistence on both architectures; the `image-smoke` artifact
holds the PNGs and Compose logs. The run followed the 2026-09-09 failure of the
main image job in run 34406149606, fixed by #90's 120-second readiness loop.

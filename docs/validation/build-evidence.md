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
for branch protection. No image was published. Both production architectures and
font rendering remain #27's checks. Fallow reports and Maven test results are
configured as CI artifacts; local generated reports are ignored by Git.

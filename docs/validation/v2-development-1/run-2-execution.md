# Run 2 execution registration

The owner authorized this follow-up on 2026-09-14 after reviewing run 1 and supplied
SSH host `pinas` for native arm64 reproduction. The base includes merged #170.
The grid, seeds, folds, selection rule and numerical limits remain unchanged.
Publication remains blocked. Preserve the original run and v1 evidence.

Run the nine commands in `registration-plan-run-2.json` in their registered order.
Capture each command, exit status, wall time and console output. A blocked statistical
result does not prevent subsequent evidence stages. A rejected stage requires diagnosis
before dependent stages proceed. Diagnostics may now share the registered directory
with tuning, but existing files and predictive draws cannot be replaced.

For native reproduction on both architectures, use the same compiled classes and
runtime dependency JARs, copied without modification to `target/validation-runtime/lib`.
Copy the registered Maven core JAR beneath
`target/validation-runtime/home/.m2/wrapper/dists/apache-maven-3.9.16/` and retain
the registered npm CLI file at its original location. These files let the existing
preflight verify the registered environment without rebuilding on the remote host.
Record hashes of transferred classes and JARs. The runtime container is the registered
multi-platform image; its arm64 executable has been checked on `pinas`.

The reproduction command below runs from the checkout root on each host. Substitute
`amd64` or `arm64` for `ARCHITECTURE`. The arm64 checkout is isolated at
`/tmp/swedishpolls-validation-run-2`; it does not use the running service or its database.

```sh
docker run --rm --platform linux/ARCHITECTURE \
  --user "$(id -u):$(id -g)" -v "$PWD:/work" -w /work \
  eclipse-temurin@sha256:b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e \
  java -Duser.home=/work/target/validation-runtime/home \
  -cp 'target/classes:target/validation-runtime/lib/*' \
  se.swedishpolls.estimation.DevelopmentValidation reproduce \
  docs/validation/v2-development-1/registration-run-2.json \
  src/test/resources/polls/audit.csv \
  docs/validation/v2-development-1/evidence/run-2/tuning.json \
  docs/validation/v2-development-1/evidence/run-2/reproduction-ARCHITECTURE.json
```

This container invocation replaces the Maven launcher for the two reproduction stages
only. Both invoke the same registered Java entry point and arguments. Transfer the
arm64 manifest and draw file back before the registered comparison command. Retain
all original blocking reasons, new failures and unavailable checks in the final report.

The local Docker cache already associates the index digest with arm64 and refuses to
replace that association. On amd64, resolve the same registered index to its native
child manifest and use
`eclipse-temurin@sha256:8c6736fa623090b057a5bbd36d42f90c9de4c7d2d4b6c285921a4f85ce65a445`
in the command above. The retained `image-index.json` proves its membership in the
registered index. Its arm64 child is
`sha256:6c3e50fade4ae5257eed863323b1efe19162a913446cd3dd166747eac8f76298`.
This resolution was recorded before any real-data fit.

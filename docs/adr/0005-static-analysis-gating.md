# Static analysis findings fail the build

SpotBugs analyses compiled main classes during Maven `verify` with maximum effort and a low
reporting threshold. Find Security Bugs runs as a SpotBugs plugin and supplies the security
checks that motivated this gate; SpotBugs' bug checks come with it. Error Prone will analyse
main and test sources during compilation in the follow-up gate. It catches compiler-visible
Java mistakes before bytecode exists. These tools cover different stages, so both belong in the
single existing build job.

Every finding fails the build. The repository is small and clean when this decision is made, so
a baseline would preserve no useful history. Adding one would instead make new and existing code
follow different rules in a codebase small enough to fix outright.

Suppressions are exceptions, not routine fixes. An agent must stop and ask for permission when
a finding looks false. A permitted SpotBugs suppression uses `@SuppressFBWarnings` at the
narrowest possible scope and states why the finding does not apply. Disabling a rule requires
its own pull request and an update to this ADR.

## Considered options

- PMD: rejected because its source rules overlap Error Prone without adding compiler type
  information or Find Security Bugs' security checks.
- Checkstyle: rejected because Spotless already gates Java formatting, while Error Prone covers
  compiler-visible correctness. Another style rule set would duplicate the existing gate.
- OWASP dependency-check: rejected because version 13 needs an NVD API key and a cached database
  to avoid multi-minute CI runs, and CPE matching produces false positives. Dependabot can cover
  vulnerable dependencies without build time or another repository secret.
- A findings baseline: rejected because the current tree is small and clean. There is no debt to
  preserve.

## Consequences

`./mvnw verify` becomes slower by about 13 seconds and fails on any SpotBugs or Find Security
Bugs finding. SpotBugs analyses main classes only; Error Prone will cover main and test sources.
The provided `spotbugs-annotations` dependency makes the permitted suppression mechanism
available without packaging it at runtime. Static analysis stays in the existing Maven build
instead of gaining a separate CI job.

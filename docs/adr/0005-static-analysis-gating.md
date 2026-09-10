# Static analysis findings fail the build

SpotBugs analyses compiled main classes during Maven `verify` with maximum effort and a low
reporting threshold. Find Security Bugs runs as a SpotBugs plugin and supplies the security
checks that motivated this gate; SpotBugs' bug checks come with it. Error Prone analyses main
and test sources during compilation with the default check set. It catches compiler-visible
Java mistakes before bytecode exists. These tools cover different stages, so both belong in the
single existing build job.

Every finding fails the build. The repository is small and clean when this decision is made, so
a baseline would preserve no useful history. Adding one would instead make new and existing code
follow different rules in a codebase small enough to fix outright.

Suppressions are exceptions, not routine fixes. An agent must stop and ask for permission when
a finding looks false. A permitted SpotBugs suppression uses `@SuppressFBWarnings` at the
narrowest possible scope and states why the finding does not apply. Disabling a rule requires
its own pull request and an update to this ADR.

Error Prone runs on the compiler's annotation processor path with the default check set. The
compiler arguments are `-XDcompilePolicy=simple`, `--should-stop=ifError=FLOW`,
`-Xplugin:ErrorProne` and `-Werror`, so any Error Prone finding or javac warning fails
compilation of both main and test sources.

Error Prone needs access to javac internals that the JDK module system denies by default. The
committed `.mvn/jvm.config` supplies them with eight `--add-exports` and two `--add-opens`
flags into `jdk.compiler`. That file applies to every Maven invocation in this repository, not
only compilation, and it is the part of this setup most likely to break on a future JDK: a JDK
that encapsulates the internals differently surfaces as `IllegalAccessError` at compiler
start-up, and the flags need updating in step.

## Error Prone opt-in review

Error Prone 2.50.0 was run over main and test sources with each check below forced to
`WARNING`. The current tree has no findings. Checks already enabled as warnings still fail the
build because javac runs with `-Werror`; repeating them as explicit `ERROR` configuration would
not tighten the gate.

| Area | Check | Default | Findings | Decision |
| --- | --- | --- | ---: | --- |
| Optional | `OptionalEquality` | Error | 0 | Keep the default error. |
| Optional | `OptionalMapUnusedValue` | Error | 0 | Keep the default error. |
| Optional | `OptionalOfRedundantMethod` | Error | 0 | Keep the default error. |
| Optional | `NullOptional` | Warning | 0 | Keep the build-failing default warning. |
| Optional | `NullableOptional` | Warning | 0 | Keep the build-failing default warning. |
| Optional | `OptionalMapToOptional` | Warning | 0 | Keep the build-failing default warning. |
| Optional | `OptionalNotPresent` | Warning | 0 | Keep the build-failing default warning. |
| Optional | `UnnecessaryOptionalGet` | Off | 0 | Leave off; it requests a stylistic simplification. |
| Immutability | `Immutable` | Error | 0 | Keep the annotation-driven default error. |
| Immutability | `ImmutableRefactoring` | Off | 0 | Leave off; it only migrates JSR 305 annotations, which this project does not use. |
| Unused code | `UnusedVariable` | Warning | 0 | Keep the build-failing default warning. |
| Floating point | `BigDecimalLiteralDouble` | Warning | 0 | Keep the build-failing default warning. |
| Floating point | `FloatCast` | Warning | 0 | Keep the build-failing default warning. |
| Floating point | `FloatingPointAssertionWithinEpsilon` | Warning | 0 | Keep the build-failing default warning. |
| Floating point | `FloatingPointLiteralPrecision` | Warning | 0 | Keep the build-failing default warning. |
| Floating point | `IntFloatConversion` | Warning | 0 | Keep the build-failing default warning. |
| Floating point | `LongDoubleConversion` | Warning | 0 | Keep the build-failing default warning. |
| Floating point | `LongFloatConversion` | Warning | 0 | Keep the build-failing default warning. |
| Floating point | `LossyPrimitiveCompare` | Error | 0 | Keep the default error. |

No off-by-default check is promoted. The relevant correctness checks already belong to Error
Prone's defaults, while the two disabled checks are migration or style checks that add no safety
here.

## Picnic Error Prone Support evaluation

Error Prone Support 0.30.0 (compatible with the pinned Error Prone 2.50.0) was run over main and
test sources by adding `error-prone-contrib` and `refaster-runner` to the annotation processor
path. The ruleset reported 702 findings: 606 Refaster rewriting opportunities, 8 bug-checker
warnings and 88 lower-severity style findings.

| Category | Findings | Observation |
| --- | ---: | --- |
| `JUnitToAssertJRules` (test sources) | 321 | Rewrites every JUnit assertion to AssertJ. A wholesale test-idiom rewrite, not a correctness gain. |
| `ImmutableListRules` / `ImmutableMapRules` | 258 | Rewrite `List.of`/`Map.of` to Guava's `ImmutableList`/`ImmutableMap`. The project has no Guava dependency. |
| `StaticImport`, annotation ordering, misc. style | ~90 | Pure style, currently a human judgement call. |
| Guava `Preconditions` templates | 8 | Rewrite JDK `assert`/`Objects` checks to Guava `Preconditions`. |
| `CollectorMutability` | 8 | Steers towards Guava's immutable collectors. |
| `OptionalOrElseGet`, `RedundantStringConversion`, `ComparatorRules`, small Refaster rules | 17 | The only findings that are uncontroversial improvements, none of them a bug. |

The decision is to decline. Three reasons:

- The bulk of the findings are Refaster templates that rewrite the tree towards libraries this
  project did not choose: Guava for collections and preconditions, AssertJ for test assertions.
  Adopting them mechanically means adopting those dependencies, which contradicts the minimal
  dependency posture this ADR's other rejections follow.
- Adopting a subset is impractical: the contributed checks activate with their default severities
  once the artifact is on the processor path, so a subset would mean listing roughly a hundred
  checks as disabled by name in `pom.xml`, configuration without a payoff.
- The ruleset is version-coupled: Error Prone Support releases support a narrow range of Error
  Prone versions, so every future Error Prone upgrade here would wait on a matching upstream
  release. The default check set plus Spotless already gate formatting and compiler-visible
  correctness; the remaining Picnic checks encode idiom preference, which ADR 0002 leaves to
  the team, not the build.

## Considered options

- Promoting default warnings to explicit errors: rejected because `-Werror` already makes them
  build failures. Duplicating Error Prone's defaults in `pom.xml` would add configuration without
  changing enforcement.
- PMD: rejected because its source rules overlap Error Prone without adding compiler type
  information or Find Security Bugs' security checks.
- Checkstyle: rejected because Spotless already gates Java formatting, while Error Prone covers
  compiler-visible correctness. Another style rule set would duplicate the existing gate.
- OWASP dependency-check: rejected because version 13 needs an NVD API key and a cached database
  to avoid multi-minute CI runs, and CPE matching produces false positives. Dependabot can cover
  vulnerable dependencies without build time or another repository secret.
- A findings baseline: rejected because the current tree is small and clean. There is no debt to
  preserve.
- Picnic Error Prone Support 0.30.0: rejected after running the ruleset against the tree; see the
  evaluation above. Its findings are dominated by Refaster rewrites towards Guava and AssertJ.

## Consequences

`./mvnw verify` becomes slower by about 13 seconds and fails on any SpotBugs, Find Security
Bugs, Error Prone or javac-warning finding. Error Prone adds about a second to compilation.
SpotBugs analyses main classes only; Error Prone covers main and test sources.
The provided `spotbugs-annotations` dependency makes the permitted suppression mechanism
available without packaging it at runtime. Static analysis stays in the existing Maven build
instead of gaining a separate CI job.

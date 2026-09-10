# swedishpolls

## Agent skills

### Issue tracker

Before making any repository change or performing ticket, branch, or pull request
operations, read [issue-tracker.md](docs/agents/issue-tracker.md). Issues and specs
live in GitHub Issues.

### Triage labels

Use the five default triage labels. Before triaging, read
[triage-labels.md](docs/agents/triage-labels.md).

### Domain docs

Use a single-context layout. Before exploring domain concepts or decisions,
read [domain.md](docs/agents/domain.md).

### Formatting

`./mvnw verify` fails on any unformatted Java, `pom.xml`, YAML or Markdown file, and
`spotless:check` also runs at `validate` so the failure arrives in seconds. Run
`./mvnw spotless:apply` to fix the whole tree before committing.

The frontend is gated by `npm run check` (`tsc --noEmit` plus `biome check .`) inside the
Maven `verify` lifecycle. TypeScript runs every strictness flag beyond `strict`, and Biome
runs its `style` and `complexity` groups at error severity with no carve-outs, so rules such
as `noJsxLiterals` and `noDefaultExport` apply. Run `npx biome check --write .` from
`frontend` to format and apply safe lint fixes before committing. Disabling a rule is a
recorded decision: give it its own pull request and note it in [ADR 0002](docs/adr/0002-codestyle-gating.md).

Run `git config blame.ignoreRevsFile .git-blame-ignore-revs` once per checkout so
`git blame` skips the whole-tree reformat and attributes lines to the commit that
wrote them. GitHub applies the file automatically.

### Java local variables

Use explicit types for Java local variables; never use `var`. Declare every local that is
not reassigned as `final`. The rule applies to main and test sources, but not to parameters,
catch variables, lambda parameters or enhanced-`for` variables. Fix violations in the Java
source, then run `./mvnw pmd:check`. See
[ADR 0006](docs/adr/0006-explicit-types-and-final-locals.md).

### Static analysis

`./mvnw verify` fails on any SpotBugs finding. Never suppress a finding. If a finding
looks like a false positive, stop and ask for permission before adding a suppression.
A permitted single suppression uses `@SuppressFBWarnings` at the narrowest possible
scope and includes a written justification. Disabling a rule outright is a recorded
decision: give it its own pull request and note it in
[ADR 0005](docs/adr/0005-static-analysis-gating.md).

Error Prone runs at compile time on main and test sources with `-Werror`, so any Error Prone
finding or javac warning fails the build. It needs the committed `.mvn/jvm.config`: eight
`--add-exports` and two `--add-opens` flags into `jdk.compiler` that apply to every Maven
invocation in this repository. If a JDK upgrade breaks compilation with `IllegalAccessError`,
that file is the first place to look. See [ADR 0005](docs/adr/0005-static-analysis-gating.md).

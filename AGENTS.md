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

### Static analysis

`./mvnw verify` fails on any SpotBugs finding. Never suppress a finding. If a finding
looks like a false positive, stop and ask for permission before adding a suppression.
A permitted single suppression uses `@SuppressFBWarnings` at the narrowest possible
scope and includes a written justification. Disabling a rule outright is a recorded
decision: give it its own pull request and note it in
[ADR 0005](docs/adr/0005-static-analysis-gating.md).

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

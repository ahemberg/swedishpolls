# Formatting and typing are gated by CI, not by convention

Java is formatted by Spotless with google-java-format in GOOGLE style, plus unused-import
removal, import ordering, sortPom over `pom.xml`, and whitespace-only rules over YAML and
Markdown. `spotless:check` binds to both `validate` (fails in seconds) and `verify` (the
phase CI names), so an unformatted file cannot reach `main`. The frontend keeps Biome
rather than adding Prettier: Biome already formats and lints in one pinned binary wired
through `npm run check` into the Maven `verify` lifecycle, so Prettier would have meant
either two formatters disagreeing on the same files or also taking on ESLint. TypeScript
runs every strictness flag beyond `strict`, and Biome runs its `style` and `complexity`
groups with no carve-outs. This is an agent-driven codebase: style has to be machine-
decided and machine-enforced, or every agent session relitigates it.

## Considered Options

- Prettier for the frontend, as originally specified: rejected once it turned out Biome
  was already gating format end to end, and that `frontend/src` was a single 11-line file,
  so the "industry standard" choice cost nothing either way and Biome does more.
- `ratchetFrom origin/main` instead of a whole-tree reformat: rejected at 21 Java files and
  zero open PRs. A ratchet permanently forfeits the guarantee that any given file is
  formatted, to avoid a blame cost this repo is too small to pay.
- Jackson for YAML and flexmark for Markdown: rejected because Jackson does not preserve
  comments, which would delete the SHA-pin annotations in `.github/workflows/verify.yml`,
  and flexmark reflows prose files whose value is the words.
- Deferring the strict TypeScript flags and the wider Biome rule groups until there is real
  frontend code: rejected. Issues #22-#33 write that code, and `noUncheckedIndexedAccess`
  costs nothing today and a slog afterwards.
- A pre-commit hook: rejected. `AGENTS.md` documents `./mvnw spotless:apply` and
  `npx biome check --write .`; agents read it, and CI is the enforcement humans cannot skip.

## Consequences

The reformat lands as one whole-tree commit whose hash goes into `.git-blame-ignore-revs`;
local `git blame` needs `git config blame.ignoreRevsFile .git-blame-ignore-revs`, documented
in `AGENTS.md`. `sortPom` expands the deliberately hand-compacted `pom.xml` from 39 lines to
roughly 150. Turning on Biome's `style` group with no carve-outs means rules like
`useNamingConvention` and `noDefaultExport` will collide with React idiom during #22-#33;
each collision is then disabled as a recorded decision rather than pre-emptively hedged.
google-java-format is pinned explicitly rather than left to the plugin default, and Java 25
support must be verified before the reformat; falling back to Palantir or the Eclipse
formatter is a different style, not a workaround.

# Issue tracker: GitHub

Issues and specs live in GitHub Issues for ahemberg/swedishpolls.
Use the gh CLI from this repository.

## Branches and pull requests

- `main` is the integration and default branch. Start ordinary work from current `origin/main` and target PRs at `main`.
- Keep wayfinder research and prototype artifacts on their dedicated `research/*` and `prototype/*` branches when linked from decision tickets. These artifact branches are not integration branches or PR bases.
- After a PR merges, remove its topic branch and clean worktree when no unique work remains. Preserve branches holding linked research or prototype evidence.
- Keep the primary working directory on `main` between tasks. Check for local changes before switching or removing a worktree.

## Operations

- Create: gh issue create --title "..." --body-file <file>
- Read ticket and discussion: gh issue view <number> --comments
- Read labels: gh issue view <number> --json labels
- List: gh issue list --state open --json number,title,body,labels
- Comment: gh issue comment <number> --body-file <file>
- Apply labels: gh issue edit <number> --add-label "<label>"
- Remove labels: gh issue edit <number> --remove-label "<label>"
- Close: gh issue close <number>

For multiline bodies, write the exact Markdown to a temporary file and
pass it with --body-file.

When a skill says "publish to the issue tracker", create a GitHub issue.
When it says "fetch the relevant ticket", read the issue and its comments.

## Pull requests as a triage surface

**PRs as a request surface: no.**

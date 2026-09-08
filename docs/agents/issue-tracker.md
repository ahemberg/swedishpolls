# Issue tracker: GitHub

Issues and specs live in GitHub Issues for ahemberg/swedishpolls.
Use the gh CLI from this repository.

## Branches and pull requests

- All repository changes, including code, documentation, configuration and follow-up fixes, go through a topic branch and a pull request targeting `main`. Never commit or push changes directly to `main`.
- Create or switch to the task branch before editing, starting new work from current `origin/main`. When a skill says to commit to the current branch, use the task branch. Push that branch and open a PR when the changes are ready for review.
- Keep wayfinder research and prototype artifacts on their dedicated `research/*` and `prototype/*` branches when linked from decision tickets. These artifact branches are not integration branches or PR bases.
- After a PR merges, remove its topic branch and clean worktree when no unique work remains. Preserve branches holding linked research or prototype evidence.
- Keep the primary working directory on `main` between tasks. Check for local changes before switching or removing a worktree.

## Checkpoints

A ticket may carry a `## Checkpoints` section grouping its acceptance criteria
into ordered stops. Implement one checkpoint per session: build only the
criteria that checkpoint names, push the task branch, comment the progress on
the issue, and stop. Do not start the next checkpoint in the same session.

Tick an acceptance criterion only when it is fully met. Leave the ticket open
until its last checkpoint is done. A ticket with no `## Checkpoints` section is
implemented in one pass.

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

# Domain docs

## Layout and reading rules

This repository uses a single-context layout:

- CONTEXT.md at the root defines domain concepts and vocabulary.
- docs/adr/ holds architectural decision records.

Before exploring the codebase, read CONTEXT.md and ADRs relevant to
the area being explored.

If these documents do not exist, proceed silently. The domain-modeling
skill creates them when terms or decisions are resolved.

## Vocabulary

Use the glossary's terms when naming domain concepts in issues,
proposals, hypotheses, and tests.

If a needed concept is missing, reconsider the term or note the gap
for domain-modeling.

## Decision conflicts

Explicitly flag proposals that contradict an existing ADR, identifying
the ADR and explaining why the decision should be reconsidered.

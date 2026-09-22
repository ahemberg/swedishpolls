# Report institute and fieldwork-length subgroup gates instead of blocking on them

In [issue #240](https://github.com/ahemberg/swedishpolls/issues/240), the institute-scope and
fieldwork-length-scope subgroup misfit gates move to reported and not blocking. They keep being
measured, their metrics are unchanged, and their failures stay in the audit; only their effect on
the release verdict changes. Party-scope failures keep blocking.

This decision was made after the results were read. The development evidence already showed which
subgroups fail and which do not, and the reclassification removes eight of the fifty reasons that
were blocking the release. Recording it as anything other than post-hoc would be false.

It is defensible because the reclassification follows from what the site publishes rather than from
which tests were inconvenient. An institute-scope or fieldwork-length-scope coverage failure is a
statement about a house-calibrated or fieldwork-conditional interval. The site publishes neither: it
has no per-institute surface and no fieldwork-length surface, and no published number is conditioned
on either. A gate that protects a quantity nothing can read cannot protect a reader. A party-scope
failure is different, because a calibrated publication would show a per-party interval, so those
gates stay blocking.

It would have been indefensible had any of these held: the reclassified scopes bore on a published
quantity; the reclassification had been chosen by which subgroups happened to fail, rather than by
which quantity each subgroup gates; the subgroups had stopped being measured or reported; or the
party-scope gates had moved with them. If the site later publishes a house-effect surface or a
fieldwork-conditional interval, this decision is void and those gates return to blocking.

The registration carries the change: the four gate names are listed in `reported_not_blocking` in
`docs/validation/release-protocol.json`, the same list that already reports the runtime target. A
scope named there keeps its measurements and its own gate, and its findings stop reaching the
verdict.
Nothing in the frozen development protocol, its digests, its evidence files or the estimator moved.
The amendment to the frozen release registration is recorded in its own `amendments` entry.

## The paired predictive comparison is deferred

The paired comparison against the ilr-window reference is recorded as deferred, not waived and not
resolved. Both compared methods carry the same observation-model misfit, so the paired difference
measures a mis-specification the candidate and the reference share rather than a difference between
them, and deciding it now would decide it on evidence the observation-model fix is expected to
invalidate.

A deferral is a registered entry in `deferred_decisions` that names a gate and what it waits on. It
changes no enforcement and clears no failure: the comparison still stands as failed and still blocks
through `development_gates`. That is the whole difference between a decision not yet taken and a
waiver.

## Alternatives

- Waive the institute and fieldwork gates. A waiver says the owner accepted a known loss on a
  quantity the release claims. Nothing here is claimed, so a waiver would overstate what happened.
- Delete the subgroups. This would remove the evidence that motivates the observation-model fix, and
  a future model could then fail the same way unobserved.
- Reclassify the party gates too. This would unblock the release for the quantity a calibrated
  publication actually shows, which is the one thing the evidence says is miscalibrated.
- Resolve the paired comparison now. Both compared methods are mis-specified in the same way, so the
  current difference is not a quantity worth deciding on.

## Consequences

The recomputed verdict blocks on 42 reasons rather than 50. It is still blocked, and publication of
a calibrated estimate stays blocked. The eight removed reasons are the institute and fieldwork-length
findings, which remain in `docs/validation/development-gates.json` and in the audit's `misfit`
section with their metrics unchanged, and are still reported by their own named gates.

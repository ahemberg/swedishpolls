# Require a minimum number of training observations for fold activation

In [issue #241](https://github.com/ahemberg/swedishpolls/issues/241), fold activation gains a
stated rule. A fold whose training window prepares fewer than four observations for its coverage
period is inactive, with the reason `below_minimum_training_observations` and its training
observation count recorded on the fold. Before this rule a fold was excluded only when it had no
training observation or no held-out composition, so a fold could reach the tuning search with too
little information to identify anything.

## Derivation

Each fold's search identifies three parameters: the daily ilr walk variance, the house scale and the
pooled observation covariance multiplier. A fold needs more training observations than parameters
it identifies, so the minimum is three plus one. An observation here is one poll. Each poll carries
several party shares, but those shares describe one sample at one time from one institute. The
walk variance is identified by change between polls over time, the house scale by differences
between polls, and the covariance multiplier by how far polls scatter around the state. The shares
inside a single poll inform none of these three on their own. The threshold is `IDENTIFIED_PARAMETERS + 1` in
`DevelopmentTuning`, and a plan that registers any other value is refused. It is not read off the
gap between the fold that failed and the next one: the FI fold after 2014-05-15 has fifteen training
observations, and fifteen was never a candidate.

The rule covers every fold inside its roster's active window, in both rosters. A fold outside the
window is already inactive for its reviewed reason and keeps it. On the registered source it excludes exactly one: the
FI candidate fold at 2014-05-15, with two training observations. Every eight-party fold and every
other active FI fold has at least fifteen.

## The grid is not expanded for that fold

Both fitted methods selected the lower corner of the walk-variance and house-scale axes for the
2014-05-15 fold in both v2 runs. The grid is not extended downward to chase that optimum. With two
observations the likelihood keeps improving as the variances shrink, whatever the grid's extent, so
a wider grid would move the boundary without resolving it. The fold carries too little information,
and a longer axis doesn't change that.

## Scope

The rule applies to registrations that declare `foldActivation` in their plan. The archived
`v2-development-1` registration, its run-2 evidence and its recorded verdict are frozen and stay as
they are, so the two grid-boundary reasons for the 2014-05-15 fold stay in that verdict. They leave
the verdict when the next registered development run executes under the rule. A boundary selection
on any fold that stays active still blocks, as before.

An in-window fold with no training observation, or no held-out composition, still stops preparation
as an unexpected empty active fold. The rule removes folds that cannot identify their parameters. It
does not hide folds that turn out empty.

## Alternatives

- Name the 2014-05-15 fold as inactive. This removes the failure without a principle, and the next
  sparse fold would need its own exception.
- Expand the grid downward for that fold. The optimum there follows from missing information, not
  from a narrow grid, so the search would keep reaching the new corner.
- Choose the threshold from the observed counts. Any value from three to fifteen removes the same
  fold on this source. Picking one because it removes that fold would be a threshold chosen by the
  result.

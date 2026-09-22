# Archive the captured 2026 prospective holdout

In [issue #239](https://github.com/ahemberg/swedishpolls/issues/239), the owner chose to accept the captured 2026 prospective source snapshot as the holdout while recording its deviation from the registered source rule. It was captured at `2026-09-12 22:54:26.081261Z`, on the registered cutoff date in UTC rather than strictly after it. The capture was before voting and before the election result existed. Its latest publication and collection end dates do not exceed the cutoff.

Archive the exact captured bytes under `docs/validation/2026-prospective-holdout`, with their capture timestamp and SHA-256. Accept them as the prospective holdout while recording the timing deviation. The holdout is immutable and is never reconstructed by filtering a later source snapshot.

The later snapshot is not equivalent evidence. It replaces four rows that existed in the holdout: Sifo and Novus rows gain previously missing uncertain-share values, and two Infostat rows gain previously missing publication dates. It also contains other source additions made after the cutoff.

## Alternatives

- Reject the capture because its UTC date is not strictly after the cutoff. This follows the registered wording but discards the only source state frozen before the outcome, even though the timing deviation could not expose the result.
- Restrict the later snapshot to the cutoff. This creates a cleaner timestamp but admits post-cutoff source corrections and no longer represents what was captured before the outcome.
- Keep the bytes only in the database volume. This preserves them on one machine but leaves the prospective evidence vulnerable to volume loss and unavailable to repository checks and review.

## Consequences

The repository now owns the only qualifying pre-result source bytes for the 2026 scoring. Any scoring that claims prospective status must use this file and the rule registered before the result was fetched. The accepted timing deviation and the four replaced rows remain visible beside the evidence instead of being silently normalized away.

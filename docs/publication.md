# Publication

[Issue #21](https://github.com/ahemberg/swedishpolls/issues/21) turns a validated estimate into a
public publication: a set of immutable documents, a pinned source snapshot and a set of versioned
share images, served through the [frozen v1 API contract](api-contract.md).

## What a publication is

| Part | Where it lives |
| --- | --- |
| Header: identity, run, snapshot, three times, headline period, approximated election | `publication` |
| The model run: protocol, seed, draws, parameters, executable versions | `model_run` |
| One rendered document per surface and language | `publication_document` |
| One immutable image version per card and language | `publication_asset` plus `PUBLICATION_ROOT` |
| Which publication is current, and whether it is stale | `current_publication` |
| What every attempt did, and why it did not publish | `publication_attempt` |

The three times are distinct and all three are published: `lastFieldworkDate` is the day the
estimate is for, `sourceCheckedAt` is when the source was last read successfully, and
`publishedAt` is when these bytes became current.

## The worker

`Publisher` runs on `publication.interval` (30 minutes by default) and holds advisory lock
`1717002` for the whole attempt, so two workers never publish the same snapshot. One attempt:

1. Check the source. `SnapshotIngest` archives a changed body as a new snapshot.
2. An unchanged snapshot records an `unchanged` attempt and stops. Unchanged input is not a
   failure and does not make the current publication stale.
3. A blocked release verdict records a `blocked` attempt and stops. Nothing is published.
4. Run the frozen estimator once over the snapshot's polls, then check it: every day's
   composition sums to 100, every allocation assigns 349 seats, and the retained draws reproduce
   exactly at the registered seed. Movement against the previous publication is not a check:
   weeks of new fieldwork can move a share as far as the polls do, and
   [issue #113](https://github.com/ahemberg/swedishpolls/issues/113) removed the unregistered
   bound that blocked it.
5. Write the candidate's `model_run` and `publication` rows, its documents, and its cards into a
   private staging directory, reading every card back against its digest.
6. Move the staging directory into place with one atomic rename, verify the moved bytes, then
   commit the asset rows, the `published` state and the current pointer together.

Any failure abandons the candidate, deletes its documents, records a `failed` attempt with the
cause chain, and marks the current pointer stale. The previous publication's own rows and bytes
are untouched.

## The frozen estimator

`src/main/resources/publication/model-freeze.json` is what the running application reads:
protocol versions, seed, draw count, interval levels, publication resolution, coverage rules,
per-period fit parameters and the release verdict. `ModelFreezeTest` compares it with
`docs/validation/protocol.json`, `coverage.json` and `release-audit.json`, so the shipped copy
cannot drift from the evidence.

Development evidence is cut at the registered development end date. A publication reads corrected
history through its own last fieldwork date, at the same frozen parameters.

**The audited verdict blocks release today.** `docs/validation/release-audit.json` reports a
failed `development_gates` gate, so a deployment publishes nothing and every estimate surface
answers `503 estimates_unavailable` with the last source-check time.

## Serving

A request without `coveragePeriod` or `election` reads the headline period and the approximated
election recorded on the publication row, so a default never depends on how surface keys happen to
sort. An explicit value that the publication has no document for is an `invalid_filter`, not an
unknown publication.

Every surface except polls is served from a stored document. `estimates/history` stores the whole
daily series; a request selects dates from it, which is why a display step never refits anything
and why the last requested supported date is always retained. `polls` and `polls.csv` are read at
request time from the publication's pinned snapshot, so both agree on rows and on archived
precision, and neither can see a newer snapshot.

The poll table carries translated component labels, so a Swedish and an English table are two
representations with two ETags. `polls.csv` is a data file: its header and its values are the
archived source, identical in both languages by design.

Current responses carry `max-age=300`; permanent publication links and asset links carry
`max-age=31536000, immutable`. Every ETag is the digest of the response body, so a query, a schema
and a translation validate separately.

## Share images

`ShareImages` renders four 1200 by 630 Java2D cards - overview, parties, seats and coalitions - in
both languages. Polls, pollster and method pages reuse the overview card. An asset version is one past the highest already stored for that card, so a re-render adds a
version beside the published one and `promote` refuses to overwrite a file that exists. Fonts are
checked before anything is staged: a runtime that cannot draw `åäö` fails the attempt rather than publishing
broken cards. A card of a closed coverage period carries that period's own date and says it is
historical, so a historical FI estimate never reads as a current one.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `PUBLICATION_ROOT` | `target/publications` | The durable volume holding image bytes |
| `publication.enabled` | `true` | Runs the scheduled worker |
| `publication.interval` | `30m` | How often an attempt runs |

## Verification

```sh
./mvnw -Dtest='ModelFreezeTest,PollQueryTest,EstimateQueryTest,ShareImagesTest,TranslationsTest' test
# With Docker available for Testcontainers:
./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test='PublicationIT,ApiV1IT'
```

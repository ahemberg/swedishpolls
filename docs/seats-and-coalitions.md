# National seats and preset coalitions

[Issue #19](https://github.com/ahemberg/swedishpolls/issues/19) turns the estimator's
final-day joint draws into a national seat approximation and the ten approved preset
coalitions. `NationalSeats` allocates, `Coalitions` groups, and `SeatOutcomes` records
the evidence behind every published probability.

## What the approximation is

The approximation distributes 349 seats over the parties whose national share reaches
the threshold, using the modified Sainte-Laguë rule of the election being approximated.
It reads national shares and nothing else.

- Shares are compared unrounded with 4%, and exactly 4% qualifies. A display rounding
  never decides eligibility: 3.95 shows as 4.0 and is still below the threshold.
- OTHER is never allocated seats. It is not a party, and no component outside the
  recorded tie order enters the distribution.
- The first divisor is the era's, read from `national_allocation_rule`. Every later
  divisor is `2 * seats_already_allocated + 1`.
- A caller names the election it is approximating. An unlisted year has no configured
  rule and the latest row is never reused for it.

| Election | First divisor |
| --- | ---: |
| 2010, 2014 | 1.4 |
| 2018, 2022, 2026 | 1.2 |

The [allocation manual](https://www.val.se/download/18.162047b519a91d05331183a9/1761747515752/manual-mandatfordelning-val-v785-05.pdf)
documents the reduction from 1.4 to 1.2 first applied in 2018.

### What it omits

The official process splits the Riksdag into 310 fixed seats and 39 adjustment seats,
admits a party to a constituency's fixed seats at 12% there, and since 2018 returns
excess fixed seats. The national approximation has none of that machinery. It is not an
official allocation, and a threshold probability from it is the chance of reaching 4%
nationally, not the full legal chance of entering the Riksdag.

The tie rule is the same kind of approximation. An exact quotient tie goes to the earlier
party of the fixed order S, M, SD, V, C, KD, L, MP, FI, restricted to the represented
parties, which makes a run reproducible. The official rule draws lots. The stored rule
records `official_tie_rule = 'lottery'` so the two are never confused.

### The era divisor is a constituency-sized effect

At 349 seats the two first divisors agree on all four stored official results, and on
20,000 random eight-party compositions drawn between 3.5% and 30% they never disagree.
The first divisor only ranks a party's *first* seat, and every party above 4% nationally
wins far more than one. On a six-seat board, 40/30/20/10 splits 3-2-1-0 under 1.4 and
2-2-1-1 under 1.2. Each election still allocates under its own era rule, because that is
the approved rule and because the effect the reduction was made for lives exactly where
this approximation does not go.

## Point seats and posterior seats

Two seat numbers come out of the same day, and they answer different questions.

- **Point seats** allocate the posterior mean support once. Support is the arithmetic
  mean of the transformed joint draws, which is the published point estimate. Point seats
  are integers, total exactly 349, and are what a 349-dot hemicycle draws.
- **Posterior mean seats** allocate every joint draw separately and average the results.
  They are generally fractional and are never rounded into a hemicycle.

A seat interval is an order statistic of the drawn allocations, rounded outward at both
ends, because a seat count is a whole number and interpolating between two of them would
state an interval narrower than the draws support.

A party's threshold probability counts the draws in which its share reaches 4%, over the
same draws the allocation reads. Clearing the threshold and holding seats in the single
point allocation are different events, so a party can hold point seats while its
threshold probability sits well below one.

## The ten preset coalitions

| Key | Parties |
| --- | --- |
| `left` | S, V, MP |
| `right` | M, L, C, KD |
| `tido` | M, L, KD, SD |
| `opposition` | S, C, V, MP |
| `s_c_mp` | S, C, MP |
| `s_m` | S, M |
| `c_l_mp_s` | C, L, MP, S |
| `m_kd_sd` | M, KD, SD |
| `s_sd` | S, SD |
| `m_sd` | M, SD |

`opposition` and `c_l_mp_s` are distinct presets: the first counts V, the second counts L.
The overview shows `tido`, `opposition`, `left` and `s_m` before a visitor chooses
anything. A label states which parties are counted together and implies no agreement
between them to govern.

A coalition's seats are summed inside each draw before anything is averaged or cut into
quantiles, so the total carries how the parties covary rather than the sum of separate
marginal summaries. The majority line is 175 of 349, more than half, and it includes
exactly 175.

The full comparison view holds every pair of the catalogue, 45 rows. Each row counts, over
the same draws, how often the first coalition leads, how often the second does and how
often they draw level. An exact tie counts for neither side; the three probabilities sum
to one.

## Publishing a probability

Probabilities are published as whole percent. A finite number of draws cannot establish
that an event is impossible or certain, so anything rounding to 0% reads `<1%` and
anything rounding to 100% reads `>99%`. Neither 0% nor 100% is ever published.

Two studies sit behind those numbers, both recorded in
[validation/seats.json](validation/seats.json).

- **Precision.** The whole final day is redrawn at the stored seed and its seven
  successors, and every threshold and majority probability records its spread across
  those eight runs. The frozen bounds are 0.03 for a threshold probability and 0.03 for
  the 175-seat line, from [protocol.json](validation/protocol.json).
- **Sensitivity.** The published equal-institute run is compared with the poll-count
  centering rerun and with one rerun per institute left out of the fit. Differences are in
  percentage points of probability. A movement over 10 percentage points is disclosed
  beside the number; it does not block the release.

Where each rerun runs differs. The publication refits only the poll-count centering
alternative, against the numbers it is about to publish, and writes the disclosure it
earns into the `sensitivity` field of the seats and coalitions documents. Both pages read
the same headline probabilities, so both carry the same sentence, and a publication that
discloses nothing carries no field at all. The leave-one-institute-out reruns stay in the
recorded evidence above, where the whole registered set runs once rather than on every
publication.

## Historical FI

FI has no validated coverage period, so it has no seat or threshold estimate. It appears
in the seat summary as unavailable with the reason `no_validated_coverage_period`, never
as zero. The candidate 2014-2018 segment recorded in [party rosters](party-rosters.md)
becomes an FI estimate only when the estimator validates its fits; until then FI is inside
OTHER and OTHER receives no seats.

## The official allocation fixture

Each stored official result is allocated under its own era rule and compared with the
official seats. This reads a published election outcome and no poll, no fit and no model
output, so it is an allocation fixture and not the reserved once-only 2022 statistical
audit. It neither consumes nor relabels that audit.

| Election | First divisor | Largest difference | Where the seats moved |
| --- | ---: | ---: | --- |
| 2010-09-19 | 1.4 | 3 | S -3, M -1, V +1, KD +1, L +1, MP +1 |
| 2014-09-14 | 1.4 | 2 | S -1, SD -2, M +1, KD +1, L +1 |
| 2018-09-09 | 1.2 | 0 | reproduces the official allocation |
| 2022-09-11 | 1.2 | 0 | reproduces the official allocation |

Both post-reform elections reproduce the official allocation exactly. The two earlier
ones do not, and the differences always cancel, because the approximation still hands out
exactly 349 seats: what it gets wrong is which party holds a seat, never how many exist.

Those differences are geographic. The official process fills 310 seats inside 29
constituencies and then uses 39 adjustment seats to bring the national totals back toward
proportionality. A party whose vote is concentrated in a few constituencies converts it
into fixed seats more efficiently than a party with the same national share spread thinly,
and before 2018 the adjustment seats could not always undo that: excess fixed seats were
not returned. In 2010 the national approximation gives S three seats fewer than the
official result, and in 2014 it gives SD two fewer, for that reason. From 2018 the return
of excess fixed seats removed most of the residue, which is why the two later elections
land exactly. This is a property of these four results, not a guarantee about a future
one.

## Verification

`NationalSeatsTest` covers the threshold, the era divisors, the seat totals, the tie order
and the two seat quantities on synthetic compositions. `CoalitionsTest` covers the ten
memberships, the overview defaults and the pairwise view including an exact tie.
`SeatOutcomesTest` covers precision, sensitivity disclosure and the official comparison.
`NationalSeatsIT` reads the stored rules and outcomes and checks the recorded evidence.

```sh
./mvnw -Dtest=NationalSeatsTest,CoalitionsTest,SeatOutcomesTest test
# With DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD for PostgreSQL 18:
./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=NationalSeatsIT
# Recompute the evidence, including both sensitivity reruns:
./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=NationalSeatsIT -Dseats.full=true
```

[Election references](election-references.md) stores the official outcomes and the
allocation rules, [party rosters](party-rosters.md) the coverage periods, and the
[frozen v1 API contract](api-contract.md) the seat and coalition response shapes.

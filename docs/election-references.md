# Election references

Checkpoint 2 of [issue #17](https://github.com/ahemberg/swedishpolls/issues/17)
stores official Riksdag outcomes for 2010, 2014, 2018 and 2022 through Flyway V3.
The supported history starts in 2010. Earlier elections are not needed for
initialization because the approved estimator uses an independent diffuse prior.
There is no 2026 outcome or model fit in this migration.

## Outcomes and provenance

`election_reference` holds the election date, valid-vote denominator, source URL,
SHA-256 of the downloaded response bytes, retrieval date and official seat source.
`election_party_reference` holds integer votes and official seats. These tables
have no membership in polling snapshots and must never supply model observations.
Future corrections require a new migration rather than editing V3.

Valmyndigheten is the source for every outcome. The national final-result tables
provide votes for [2010](https://historik.val.se/val/val2010/slutresultat/R/rike/index.html),
[2014](https://historik.val.se/val/val2014/slutresultat/R/rike/index.html) and
[2018](https://historik.val.se/val/val2018/slutresultat/R/rike/index.html).
The [historical summary](https://www.val.se/valresultat-och-statistik/riksdags--region--och-kommunval/tidigare-valresultat)
provides official seats. The 2014 table's comparison column gives the 2010 FI
votes and the remainder excluding FI; those two rows retain a source override.

For 2022, the [final national JSON](https://resultat.val.se/data/resultat/val2022/RD_S.json)
provides integer votes, including FI. Its `rosterPaverkaMandat.antalRoster` is the
valid-vote denominator. The
[official decision, appendix 1, page 1](https://www.val.se/download/18.162047b519a91d0533112bab/1666619695452/riksdag-val-2022-beslut-med-tva-rattelser-samt-bilagor.pdf)
independently lists the eight parliamentary parties' votes and seats. All source
responses were retrieved on 2026-09-08. The migration preserves the transcribed
facts and source hashes, not a full archive of the source websites.

| Election | Valid votes | FI votes | Residual excluding FI | Official seats |
| --- | ---: | ---: | ---: | ---: |
| 2010-09-19 | 5,960,408 | 24,139 | 60,884 | 349 |
| 2014-09-14 | 6,231,573 | 194,719 | 60,326 | 349 |
| 2018-09-09 | 6,476,725 | 29,665 | 69,472 | 349 |
| 2022-09-11 | 6,477,970 | 3,157 | 97,095 | 349 |

Historical Folkpartiet maps to L, retaining the source label. The nine named
parties and `RESIDUAL` partition valid votes. Blank and other invalid ballots
are excluded from the denominator. `RESIDUAL` always excludes FI, unlike the
displayed OTHER outside an FI coverage period. It is a storage aggregate, not
an individual party or a decision about modeled coverage.

Derive a reference share as `100 * votes / valid_votes` without rounding the
stored counts. For the eight-party display, OTHER combines FI and RESIDUAL.
For an FI display, OTHER contains only RESIDUAL. Roster validation and supported
FI dates remain checkpoint 3 work.

The [frozen 2022 validation fixture](validation/protocol.json) is separate. It
uses the already-exposed two-decimal percentages and a 1.54% remainder. The exact
vote-count remainder is 100,252 / 6,477,970, approximately 1.5476%. Do not replace
the frozen audit fixture with these count-derived shares or treat seed checks
as the reserved model audit.

## National allocation rules

`national_allocation_rule` records the approved approximation for each target
election year, including 2026 without seeding an outcome. A caller must select
the election being approximated. For example, a pre-election 2018 estimate uses
the 2018 row, not the 2014 row. An unlisted election year has no configured rule;
do not silently reuse the latest row.

The [allocation manual](https://www.val.se/download/18.162047b519a91d05331183a9/1761747515752/manual-mandatfordelning-val-v785-05.pdf)
documents the change first applied in 2018 on page 3 and the divisor sequence
on page 8. The first divisor is 1.4 for 2010 and 2014, then 1.2 for 2018, 2022
and 2026. Subsequent divisors are `2 * seats_already_allocated + 1`.

Each approximation allocates 349 seats. National eligibility uses unrounded
shares and includes exactly 4%. OTHER receives no seats. Exact ties use the
fixed order S, M, SD, V, C, KD, L, MP, FI, restricted to represented parties.
This deterministic order implements the owner-approved reproducibility policy;
the official tie rule is a lottery.

The official process has 310 fixed seats, 39 adjustment seats and a 12%
constituency exception. The national approximation omits constituency rules,
including the return of excess fixed seats introduced in 2018. Stored official
seats are actual outcomes, not outputs of this approximation. Allocation logic
and public endpoints belong to later implementation tickets.

## Verification

`ElectionReferenceIT` upgrades an isolated PostgreSQL schema from checkpoint 1,
checks reference counts, denominators, vote precision, FI separation, official
seats and election-era rules, and verifies migration restart and empty poll tables.
It uses fixed expectations from the official sources without network requests.

```sh
# With DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD for PostgreSQL 18:
./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=ElectionReferenceIT
./mvnw --batch-mode --no-transfer-progress clean verify
```

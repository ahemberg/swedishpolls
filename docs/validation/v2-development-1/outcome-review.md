# Owner review of the completed development experiment

## Accepted outcome and scope

On 2026-09-15, the owner accepted run 2 as a completed, failed experiment for
[#160](https://github.com/ahemberg/swedishpolls/issues/160). Statistical acceptance
is not a condition for completing that experiment. The development verdict remains
blocked, and publication remains unauthorized.

The owner prioritized a defensible eight-party current-opinion estimate. The
midpoint estimator remains open to review; individual FI estimates retain their
separate coverage requirements. The owner approved a bounded review of retained
results to locate the midpoint's losses, distinguish shared subgroup failures,
and recommend whether a specific hypothesis warrants another experiment.

This document records that review. It does not authorize new fits, grid changes,
estimator substitution, relaxed gates or a release waiver. The parent #153,
release-decision scope #151 and launch acceptance #28 remain separate.

## Evidence and interpretation

The evidence is [run 2's report](evidence/run-2/report.md),
[diagnostics](evidence/run-2/diagnostics.json) and
[original-reason accounting](evidence/run-2/development-aggregate.json).
The registration hash is
`fccbc0eec6579ecc2fe7b61a4db3cfc3fea074493f2b9c7cc3910d53a5a4401e`.
All calculations below read retained results; none refits a model.

Both methods selected unchanged parameters in all 48 eight-party folds. Only one
of 27 active FI folds changed for each method, and both still selected lower walk
variance and house-scale endpoints there. The expanded grid did not resolve the
eight-party comparison or subgroup failures.

The eight-party midpoint gains 1.159959 joint log-score units over recency but loses
0.023579 to the window reference. Its registered lag-three standard error is
0.011921. The registered requirement allows a loss of at most one such standard
error, so the comparison fails. These numbers are log scores, not percentage-point
errors or evidence that the window reference is ready for publication.

Run 2 resolved the execution defects from run 1. Four institute subgroups remain
unevaluated for insufficient cases: SCB and United Minds in each roster. Native
reproduction passed within tolerance. The aggregate's 25 evaluated failures
include propagated reasons, rather than 25 independent statistical defects.

## Where the midpoint loses

For each of the 370 scored polls, take midpoint minus window joint log score and
divide by that fold's poll count and by 48. Summing these contributions reproduces
the registered equal-fold difference, -0.023579124341236. Each partition below sums
to that same total. These are contributions to the overall comparison, not group
means or independent effects. Do not add contributions across different partitions.

| Fieldwork duration | Contribution |
| --- | ---: |
| 1-7 days | -0.003766125453 |
| 8-14 days | +0.001358587473 |
| 15+ days | -0.021171586361 |

Novus contributes -0.015316944342 and Inizio -0.010508849119. Demoskop offsets
part of the loss with +0.005643204542. Institute and duration overlap: 49 of 56
Novus polls have 15+ fieldwork days and contribute -0.014438277470. Inizio's losses
also occur in shorter polls: its 14 polls lasting 1-7 days contribute
-0.006414065600. Duration alone does not explain the pattern.

| Fold cutoff year | Folds | Contribution |
| --- | ---: | ---: |
| 2014 | 6 | +0.002320191362 |
| 2015 | 6 | +0.002309375461 |
| 2016 | 7 | -0.002876529841 |
| 2017 | 6 | -0.007488797377 |
| 2018 | 6 | +0.001190166259 |
| 2019 | 6 | -0.003934042252 |
| 2020 | 6 | -0.010403856803 |
| 2021 | 5 | -0.004695631151 |

The five largest negative poll contributions are below. Row numbers identify the
pinned source rows, and dates identify the scoring fold's cutoff.

| Source row | Cutoff | Institute | Fieldwork days | Poll score difference | Contribution |
| --- | --- | --- | ---: | ---: | ---: |
| 726 | 2017-10-26 | Inizio | 6 | -2.092027 | -0.004842655019 |
| 926 | 2015-07-09 | Sentio | 7 | -0.225444 | -0.004696749105 |
| 754 | 2017-06-28 | Skop | 30 | -1.071870 | -0.004466124316 |
| 969 | 2015-01-10 | Novus | 28 | -1.035700 | -0.002697135804 |
| 742 | 2017-08-27 | Inizio | 6 | -0.880345 | -0.002620074100 |

The Sentio row is its fold's only poll. Its weight follows the frozen equal-fold
rule; its presence is not a reason to change weighting after seeing the loss.

## Shared failures

Both methods fail the same five primary eight-party subgroups. The reference
diagnostics are descriptive, not a separate release qualification. Neither method
has a unique primary subgroup failure under the retained diagnostic thresholds.

| Subgroup | Cases | Midpoint 95% coverage | Window 95% coverage | Midpoint 50% coverage | Window 50% coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| S | 370 | 98.38% | 98.11% | 64.05% | 65.41% |
| KD | 370 | 86.49% | 86.49% | 41.89% | 42.43% |
| OTHER | 370 | 84.86% | 84.32% | 39.19% | 39.73% |
| Sifo | 567 | 89.77% | 89.77% | 51.15% | 50.97% |
| Skop | 261 | 82.76% | 82.76% | 42.53% | 43.30% |

The limits are 90-98% for 95% coverage and 40-60% for 50% coverage.
KD and Skop also exceed the RMS standardized-residual ceiling of 1.5 in both
methods. Their midpoint/window RMS values are 1.842/1.846 and 1.693/1.695.
Institute case counts include party-poll components; they are not independent
poll counts. Finer party-by-institute and party-by-duration cuts are descriptive
and must not be mistaken for additional primary gates. Three metric failures in
those descriptive cuts distinguish the methods:

- Midpoint only: KD 95% coverage for 8-14 day polls, 89.880952% over 168 cases.
- Midpoint only: S 95% coverage in the missing-method-era group, 98.220641% over
  281 cases. The retained `"null"` label is missing metadata, not a named method era.
- Window only: S 50% coverage for 8-14 day polls, 61.904762% over 168 cases.

S overcovers while KD and OTHER under-cover. Increasing a common noise multiplier
is therefore not an evidenced remedy for all failures. The largest contributors
to the paired score loss, Novus and Inizio, also differ from the institutes failing
primary coverage checks, Sifo and Skop. Score comparison and uncertainty adequacy
need separate explanations.

## Investigation priority accepted by the owner

Neither retained method is established as adequate for publication. The evidence
supports two questions for a future protocol, not an approved model change:

1. Does representing a multi-day poll at its midpoint account for some of the
   relative score loss? Long-fieldwork losses support investigating this, but
   institute overlap and Inizio's short-fieldwork losses prevent a causal claim.
   A controlled synthetic comparison could isolate duration from institute and
   opinion movement before another real-data experiment is proposed.
2. What produces the shared party and institute uncertainty failures? Both methods
   share the primary failures, so changing the observation timing convention alone
   has no demonstrated remedy for them. A proposed investigation should distinguish
   shared modeling assumptions from source or implementation defects before adding
   party-specific or institute-specific parameters.

The owner accepted prioritizing shared uncertainty failures on 2026-09-15.
They remain an obstacle even if the relative score loss is repaired. Investigating
midpoint versus fieldwork-window timing is secondary. Do not expand
the grid again or substitute the window reference on the strength of this review.
Any new experiment needs a specific hypothesis, fixed inputs and checks, and an
owner-reviewed protocol before fitting. The priority is settled; the next
investigation's scope and stopping rule remain to be agreed.

## Reproduce the descriptive calculations

Run from the repository root with Python 3. The command reads the retained JSON,
checks every fold mean against its archived counterpart and checks that every
partition reconciles to the registered comparison. It prints the full institute
partition, the other partitions, the largest negative poll contributions and the
primary subgroup failures. It writes no evidence and runs no fits.

```bash
python3 - <<'PY'
import json
import math
from collections import defaultdict
from pathlib import Path

path = Path('docs/validation/v2-development-1/evidence/run-2/diagnostics.json')
data = json.loads(path.read_text())
diagnostic = next(d for d in data['diagnostics'] if d['periodId'] == 'eight_party_2010')
folds = [f for f in data['folds'] if f['periodId'] == 'eight_party_2010' and f['active']]
assert len(folds) == 48
assert sum(len(f['polls']) for f in folds) == 370
partitions = {k: defaultdict(list) for k in ('year', 'institute', 'duration', 'institute_duration')}
rows = []
for fold in folds:
    polls = fold['polls']
    differences = []
    for poll in polls:
        scores = {m['method']: m['jointLogScore'] for m in poll['methods']}
        difference = scores['midpoint'] - scores['ilr_window']
        differences.append(difference)
        contribution = difference / len(polls) / len(folds)
        keys = (fold['cutoff'][:4], poll['institute'], poll['fieldworkBand'],
                (poll['institute'], poll['fieldworkBand']))
        for partition, key in zip(partitions.values(), keys):
            partition[key].append(contribution)
        rows.append((contribution, poll['rowNumber'], fold['cutoff'],
                     poll['institute'], poll['fieldworkDays'], difference))
    archived = fold['meanLogScore']['midpoint'] - fold['meanLogScore']['ilr_window']
    assert math.isclose(math.fsum(differences) / len(polls), archived, abs_tol=1e-12)
target = diagnostic['paired']['referenceDifference']
for name, groups in partitions.items():
    assert math.isclose(math.fsum(map(math.fsum, groups.values())), target, abs_tol=1e-12)
    print(name)
    for key, values in sorted(groups.items()):
        print(key, 'polls:', len(values), 'contribution:', f'{math.fsum(values):+.12f}')
print('Largest negative poll contributions:', *sorted(rows)[:5], sep='\n')
for group in diagnostic['misfit']:
    if (group['candidate'] in ('midpoint', 'ilr_window')
            and group['scope'] in ('party', 'institute', 'fieldwork_days')
            and group.get('group', 'all') == 'all' and group['status'] == 'fail'):
        print(group['candidate'], group['scope'], group['name'], group['cases'],
              group['coverage95'], group['coverage50'], group['rootMeanSquareStandardizedResidual'])
failures = {}
for method in ('midpoint', 'ilr_window'):
    failures[method] = {
        (m['scope'], m.get('group', ''), m['name'], reason)
        for m in diagnostic['misfit']
        if m['candidate'] == method and m['status'] == 'fail'
        for reason in m['reasons']
    }
print('Midpoint only:', sorted(failures['midpoint'] - failures['ilr_window']))
print('Window only:', sorted(failures['ilr_window'] - failures['midpoint']))
PY
```

## Limits and release boundary

This is a descriptive review of already-exposed development results. Grouping and
ranking losses after seeing them can suggest hypotheses but cannot establish their
cause or validate a revised method. No row, fold or subgroup is removed from the
registered comparison. Party diagnostics describe marginal coverage and residuals;
the joint poll score must not be assigned independently to parties.

The corrected snapshot does not reconstruct historical source availability.
Method-era attribution is limited by the retained metadata. Insufficient subgroup
cases remain insufficient evidence, even when a displayed metric falls inside a
limit.

The old 2022 audit remains development evidence with its original blocked verdict.
The revised method missed the 2026-09-12 prospective cutoff. A future method change
still needs a separately reviewed evaluation and release decision under
[ADR 0009](../../adr/0009-development-remediation-protocol.md).

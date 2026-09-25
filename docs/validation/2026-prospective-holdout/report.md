# 2026 prospective holdout result

The registered qualifying execution failed and produced no score. A remediated run of the shipped
method had a mean absolute error of 1.012 percentage points across the nine modeled components, a
largest absolute error of 2.666 points, and only two of nine official shares inside its 95%
marginal intervals. This measurement is non-qualifying because the scorer changed after the result
was opened and the shipped bytecode did not match its registered digest. The revised method
produced the same numbers. It is also a non-qualifying diagnostic because it was registered after
the holdout cutoff.

## Official result

| Field | Value |
| --- | --- |
| Source | `https://resultat.val.se/data/resultat/val2026/RD_S.json` |
| Retrieved | `2026-09-25T10:15:39Z` |
| Bytes | `789535` |
| SHA-256 | `1998afdc723a83b325c729faf68063c8731e33496fc274bf9fbb80499e0afb08` |
| Status | Final, 6,626 of 6,626 districts counted |
| Valid votes | `6767429` |

The archived source is [`official-result.json`](official-result.json), and its checksum is
[`official-result.json.sha256`](official-result.json.sha256). Migration
`V7__2026_election_reference.sql` stores the source URL, hash, retrieval date, vote counts and
official allocation in the same reference tables as earlier elections.

## Scores

Errors are the drawn mean minus the official two-decimal percentage, in percentage points.

| Component | Official | Shipped mean, non-qualifying | Shipped error, non-qualifying | 50%, non-qualifying | 95%, non-qualifying | Revised mean, non-qualifying | Revised error, non-qualifying | 50%, non-qualifying | 95%, non-qualifying |
| --- | ---: | ---: | ---: | :---: | :---: | ---: | ---: | :---: | :---: |
| M | 19.85 | 17.184 | -2.666 | no | no | 17.184 | -2.666 | no | no |
| L | 5.34 | 4.033 | -1.307 | no | no | 4.033 | -1.307 | no | no |
| C | 7.03 | 7.946 | +0.916 | no | no | 7.946 | +0.916 | no | no |
| KD | 6.17 | 6.389 | +0.219 | no | yes | 6.389 | +0.219 | no | yes |
| S | 28.02 | 28.010 | -0.010 | yes | yes | 28.010 | -0.010 | yes | yes |
| V | 8.40 | 7.829 | -0.571 | no | no | 7.829 | -0.571 | no | no |
| MP | 6.12 | 7.481 | +1.361 | no | no | 7.481 | +1.361 | no | no |
| SD | 17.48 | 19.049 | +1.569 | no | no | 19.049 | +1.569 | no | no |
| OTHER | 1.59 | 2.079 | +0.489 | no | no | 2.079 | +0.489 | no | no |

The complete remediated shipped output is [`shipped-result.json`](shipped-result.json). Its
`resultStatus` repeats the preregistered intended status, but this report rejects that status because
the registered execution failed. The complete revised non-qualifying diagnostic is
[`revised-result.json`](revised-result.json). Both use seed `20260908`, 10,000 draws and the
registered parameters. Their exact reruns had zero maximum absolute difference across 90,000
retained values.

## Seats

The approximation applies the national modified Sainte-Lague rule to the drawn mean. The official
allocation includes constituency rules. These are separate quantities and are not reconciled or
merged.

| Party | Shipped national approximation, non-qualifying | Revised national approximation, non-qualifying | Official allocation |
| --- | ---: | ---: | ---: |
| S | 100 | 100 | 99 |
| M | 61 | 61 | 70 |
| SD | 68 | 68 | 62 |
| V | 28 | 28 | 30 |
| C | 28 | 28 | 25 |
| KD | 23 | 23 | 22 |
| L | 14 | 14 | 19 |
| MP | 27 | 27 | 22 |
| Total | 349 | 349 | 349 |

## Execution record

Commands 1 through 10 first ran in the registered order. Command 10 failed while reading an
unabbreviated minor-party row in the official result. No score file existed at that point. The
scorer then needed three more compatibility corrections: it pointed at `coverage.json` instead of
`protocol.json`, passed a precision-repeat count rejected by the shipped class, and compared the
compiled shipped bytecode with a digest that the registered commit does not reproduce. The
registered shipped commit compiled to `c46a651a5b3af94b578ae3e72eae325d91d39f41c8214b2a8adb541c866c8045`,
not the registered `3139c010a07a0caf68a94e8a45ff9c358c0cc7ff834d93090f4c33d445f02a05`.
The successful scorer source has SHA-256
`815caac87adc280868ca51ec86dc88a49436f2ef32883e929d96ab9844f02ae1`.

These fixes did not change the pinned commits, holdout, official result, candidate selection,
parameters, seed, draw count, intervals, error calculation or seat calculation. The bytecode
identity mismatch means the qualifying score identifies the preregistered shipped source commit
and reports the bytecode it ran, but it did not reproduce the preregistered bytecode digest.

The exact commands are in [`scoring-registration.json`](scoring-registration.json) and copied into
[`execution`](execution). Each attempt's complete combined console output, UTC start and finish,
wall time and exit status are stored there.

| Attempt | Command | Exit | Wall time | Outcome |
| --- | ---: | ---: | ---: | --- |
| Registered | 1 | 0 | 0.003 s | Registration checksum passed |
| Registered | 2 | 0 | 0.004 s | Implementation manifest checksum passed |
| Registered | 3 | 0 | 0.003 s | Listed implementation files passed |
| Registered | 4 | 0 | 0.012 s | Frozen administrative artifacts passed |
| Registered | 5 | 0 | 0.004 s | Holdout checksum passed |
| Registered | 6 | 0 | 0.002 s | Output and worktree absence passed |
| Registered | 7 | 0 | 0.329 s | Official result retrieved |
| Registered | 8 | 0 | 0.005 s | Official result checksum written |
| Registered | 9 | 0 | 0.023 s | Shipped worktree created |
| Registered | 10 | 1 | 23.345 s | Missing `partiforkortning` |
| Cleanup retry | 10 | 1 | 0.007 s | Worktree creation was omitted, so scoring did not start |
| Parser remediation | 9 | 0 | 0.026 s | Shipped worktree created |
| Parser remediation | 10 | 1 | 27.905 s | Wrong coverage-rules path |
| Rules-path remediation | 9 | 0 | 0.025 s | Shipped worktree created |
| Rules-path remediation | 10 | 1 | 26.835 s | Invalid precision-repeat count |
| Constructor remediation | 9 | 0 | 0.034 s | Shipped worktree created |
| Constructor remediation | 10 | 1 | 90.902 s | Registered bytecode digest mismatch |
| Identity-check remediation | 9 | 0 | 0.059 s | Shipped worktree created |
| Identity-check remediation | 10 | 0 | 74.438 s | Non-qualifying shipped diagnostic written |
| Identity-check remediation | 11 | 0 | 0.076 s | Revised worktree created |
| Identity-check remediation | 12 | 0 | 77.673 s | Non-qualifying revised diagnostic written |
| Identity-check remediation | 13 | 0 | 0.183 s | Detached worktrees removed |
| Identity-check remediation | 14 | 0 | 0.004 s | Final holdout checksum passed |

Without a recorded owner decision, the failed registered identity check cannot be waived and issue
#247's qualifying-result criterion remains unmet.

The holdout SHA-256 was
`2577ffae3686392f0b63411438e939565e694e9907e5eda6e1473ec9155736ad` before and after execution.
The archived bytes are unchanged.

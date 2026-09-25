# 2026 prospective holdout

[`source.csv`](source.csv) is the exact source body archived before the 2026 election result existed.

| Field | Value |
| --- | --- |
| Source | `https://raw.githubusercontent.com/MansMeg/SwedishPolls/master/Data/Polls.csv` |
| Captured at | `2026-09-12 22:54:26.081261Z` |
| SHA-256 | `2577ffae3686392f0b63411438e939565e694e9907e5eda6e1473ec9155736ad` |
| Bytes | `270333` |

The registered rule in [`protocol.json`](../protocol.json) called for the first complete snapshot captured strictly after the `2026-09-12` cutoff, restricted to rows whose publication and collection end dates were no later than the cutoff. The stored UTC capture date is the cutoff date itself, not a later date. In Stockholm the capture was at `2026-09-13 00:54:26.081261+02:00`, before voting and before any election result existed. The archive is accepted as the prospective holdout because it froze the source without access to the outcome and contains no publication or collection end date after the registered cutoff. This is an accepted timing deviation, not a claim that the original rule was followed exactly.

## Difference from the later snapshot

The next archived snapshot was captured at `2026-09-14 20:36:38.802954Z` with SHA-256 `1c48b1ac21d0147ecafa02eb7047ad8410fb945bf38b9a883152fe8ff2fd7194`. Four source rows in the holdout are absent byte-for-byte from that snapshot because the source later filled previously missing values:

| Institute | Collection period | Field | Holdout | Later snapshot |
| --- | --- | --- | --- | --- |
| Sifo | 2026-09-04 to 2026-09-10 | `Uncertain` | `NA` | `9` |
| Novus | 2026-09-07 to 2026-09-10 | `Uncertain` | `NA` | `0.8` |
| Infostat | 2026-07-27 to 2026-08-05 | `PublDate` | `NA` | `2026-09-02` |
| Infostat | 2026-04-28 to 2026-05-05 | `PublDate` | `NA` | `2026-09-02` |

The holdout is always [`source.csv`](source.csv) as captured. It must never be reconstructed by restricting a later snapshot: the later source includes corrections made after the cutoff, including these four replacements, so such a reconstruction would use information that was not in the frozen input.

## Result

The [execution report](report.md) records the qualifying shipped score, the revised method's
non-qualifying diagnostic, the official result and the complete command history.

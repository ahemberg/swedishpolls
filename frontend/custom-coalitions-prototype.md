# Two-block comparison prototype

Review artifact for [Explore two disjoint blocks with compared support histories](https://github.com/ahemberg/swedishpolls/issues/15). Fictional estimates and joint draws only. Not production code.

```sh
python3 -m http.server 4173 --directory frontend
node frontend/custom-coalitions-prototype.check.cjs
```

Open http://localhost:4173/custom-coalitions-prototype.html?variant=C . Compact C is the preferred direction. The switcher retains stacked A. Side-panel B is discarded. The original reviewed layouts remain in commit 10d38d6.

Assign each party to A, B, or neither. A single ownership field prevents simultaneous membership. Empty blocks prompt for assignment, while a nonempty block still renders. Both histories and uncertainty bands share a date axis, publication and scrubber. No normalization to 100%, majority line, seats or FI/OTHER selection.

The URL carries both blocks and the date range. Reloading restores them. Legacy single-selection links populate A only. Duplicate ownership in malformed links retains A; unsupported party codes are ignored in this prototype. Production input behavior still needs specification.

## Color experiment

The provisional palette follows the owner's directions. Each block averages its constituent sRGB channels, weighted by fictional party support at the latest date. The resulting color stays fixed during date scrubbing. Very light mixtures are darkened for contrast; when mixtures are close, B is darkened further. Solid A and dashed B supplement colors. These rules and exact hex values require owner visual review. The blue/yellow block can look olive; the experiment deliberately exposes that consequence of blending.

## Status

Owner-approved pivot: two disjoint blocks, parties may be unassigned, moving a party removes its previous assignment, and try support-based color weights. Post-v1 scope and earlier sharing/uncertainty requirements remain.

The runnable check covers ownership, moves, unassignment, URL restoration, date validation, empty states, joint sums, missing values, stable colors and similar-color handling. A headless Chrome screenshot was inspected for desktop rendering. Full browser interaction and accessibility testing have not been completed.

Pending: owner review of the two-block controls and colors, initial/reset behavior, and the production data/API contract. Do not merge the prototype into main.

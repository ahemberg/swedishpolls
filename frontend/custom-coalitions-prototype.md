# Two-block comparison prototype

Review artifact for [Explore two disjoint blocks with compared support histories](https://github.com/ahemberg/swedishpolls/issues/15). Fictional estimates and joint draws only. Not production code.

```sh
python3 -m http.server 4173 --directory frontend
node frontend/custom-coalitions-prototype.check.cjs
```

Open http://localhost:4173/custom-coalitions-prototype.html?variant=C . Compact C is the preferred direction. The switcher retains stacked A. Side-panel B is discarded. The original reviewed layouts remain in commit 10d38d6.

Drag party tiles between Coalition A, Coalition B and the unassigned tray. Alternatively select a tile with a click, tap or Enter, then activate a destination’s Move here button. Escape cancels selection/dragging; invalid drops retain the assignment. Touch dragging uses pointer events, with normal page scrolling outside the tiles. A single ownership field prevents simultaneous membership. Empty blocks prompt for assignment, while a nonempty block still renders. Both histories and uncertainty bands share a date axis, publication and scrubber. No normalization to 100%, majority line, seats or FI/OTHER selection.

The URL carries both blocks and the date range. Reloading restores them. Legacy single-selection links populate A only. Duplicate ownership in malformed links retains A; unsupported party codes are ignored in this prototype. Production input behavior still needs specification.

## Color experiment

The provisional tile palette follows the owner's directions. Each block averages constituent sRGB channels, weighted by fictional party support at the latest date. For this experiment, SD contributes blue #427cae to a block containing M, KD or L, while its tile stays yellow. SD alone retains its yellow-derived chart color. This is an explicit palette rule for review, not an inferred ideological score. The resulting color stays fixed during date scrubbing. Very light mixtures are darkened for contrast; when mixtures are close, B is darkened further. Solid A and dashed B supplement colors. These rules and exact hex values require owner visual review. The sample M + KD + L + SD curve is now blue. Cross-bloc combinations still need owner visual review.

## Status

Owner-approved pivot: two disjoint blocks, parties may be unassigned, moving a party removes its previous assignment, and try support-based color weights. Post-v1 scope and earlier sharing/uncertainty requirements remain.

The runnable check covers ownership, moves, unassignment, URL restoration, date validation, empty states, joint sums, missing values, stable colors and similar-color handling. Chrome checks exercised mouse dragging among all three destinations, invalid-drop cancellation, click and Enter-based assignment, mobile touch dragging, stable colors and URL reload. Desktop and 390px screenshots were inspected. This does not constitute a full accessibility audit.

Pending: owner review of the drag controls and blue-biased colors, initial/reset behavior, and the production data/API contract. Do not merge the prototype into main.

## Live support semicircle

A continuous semicircle sits beside the coalition editor on desktop and above it on mobile. It updates after completed assignments, showing latest-date combined voting intention for A and B. Gray accounts for unassigned parties plus OTHER, with separate numeric values below. It is not a seat hemicycle and does not renormalize A and B to 100%. The summary remains sticky within the editor; the historical chart stays below. Review this placement and mobile behavior with the owner.

Existing checks and Chrome drag/click/keyboard/touch/reload checks pass after the addition.

## Owner approval

The owner approved compact C at commit `c55698e`: "this is great. Lock it in."

That commit is the frozen UI reference. It includes the three-destination drag editor, blue-biased support-weighted colors, sticky latest-support semicircle and historical chart below. Earlier pending UI-review notes above are superseded by this approval. See the [canonical UI decision](https://github.com/ahemberg/swedishpolls/issues/15#issuecomment-5588828746).

Production data/API specification remains open. The prototype stays on this artifact branch and is not merged into the application.

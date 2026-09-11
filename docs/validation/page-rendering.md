# Page rendering checks

Verified locally on 2026-09-12 for [#24](https://github.com/ahemberg/swedishpolls/issues/24).
These checks cover how the seats and coalition pages lay out and colour themselves. They
certify nothing about the estimator, and they are not a gated build step: no headless
browser runs in `./mvnw verify`.

## How the check was run

A local application served one real publication from the pinned source, on PostgreSQL 18.4
in `compose.dev.yaml`. Headless Chrome 125.0.6422.141 drove each page over the DevTools protocol, with
`Emulation.setDeviceMetricsOverride` fixing the viewport and `Emulation.setEmulatedMedia`
fixing `prefers-color-scheme`. Every page was measured after the React bundle mounted, so
these are the mounted pages rather than the basic HTML the server writes first.

Sixteen combinations were measured: `/mandat`, `/regeringsunderlag`, `/en/seats` and
`/en/coalitions`, each at 390 and 1200 CSS pixels, each in light and dark.

Three things were recorded per combination: the resolved colour scheme and the computed
`body` background, the horizontal overflow of the document
(`documentElement.scrollWidth - clientWidth`), and every element whose box reaches past the
viewport.

## Result

All sixteen combinations resolve the scheme the media feature asked for, and paint the
`--ground` token for it: `rgb(247 246 242)` in light, `rgb(20 23 28)` in dark. None of the
sixteen scrolls the document sideways.

The seat table, the coalition catalogue and the pairwise table each reach past 390 pixels
and scroll inside their own container, which is the intended behaviour and is what
`PageIT` already asserts structurally. No other element does.

## Two defects the check found, both fixed in the same change

- **The mounted heading lost the space before its date.** The basic HTML writes
  `<h1>Title <span class="asof">…</span></h1>`, but the JSX put the two children on
  separate lines, so JSX dropped the whitespace and the page read
  "National seat approximationas of 9 September 2026". The two renderings are required to
  word the same thing identically. `ChamberHeading` and `Overview` now carry the space.
- **The source URL pushed the page sideways at phone width.** The method footer prints the
  source URL and the snapshot digest as one unbreakable run. At 390 pixels it forced the
  document 145 pixels wider than the viewport, so the whole page scrolled sideways rather
  than the line wrapping. `footer.about p.meta` now sets `overflow-wrap: anywhere`.

Both were measured again after the fix, and neither reproduces.

## What this does not cover

Real devices, real assistive technology and browsers other than Chromium. Keyboard and
focus behaviour is asserted structurally in `PageIT` rather than driven here.

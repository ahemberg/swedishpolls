# Public experience and delivery audit

Research resolution for [Investigate the existing site, sharing, accessibility, and deployment constraints](https://github.com/ahemberg/swedishpolls/issues/4). Investigated 2026-09-07. Recommendations below inform later decisions; they are not owner-approved architecture or a finished implementation plan.

## Proposed scope preserved

The supplied frontend spec targets interested Swedish voters, with journalist-friendly CSV and share cards. It proposes Swedish root routes and English `/en` equivalents for overview, eight parties, seats, coalition support, pollsters, polls, and method. The overview orders latest estimates with 95% intervals and 30-day change, history since the last election, a 349-dot parliament, four default coalitions, ten latest polls, and provenance. Party pages add institute effects and threshold probabilities. Full tables, downloadable PNGs and bilingual method explanations support scrutiny.

Visual proposals: self-hosted IBM Plex Sans/Condensed, tabular figures, quiet 1120px layout, light/dark themes, party colours, React/TypeScript with modular D3 SVG, fieldwork segments, uncertainty bands, keyboard/mobile cursors, table alternatives and no analytics. Performance aspirations are <150KB initial gzip JS and Lighthouse mobile performance/accessibility ≥90.

Delivery proposals: Spring injects route-specific head metadata into Vite HTML; Java2D generates 1200×630 light-theme cards per run and language; images use run-versioned URLs and one-year immutable caching but seven-day disk retention. REST has both row and columnar competing history shapes, five-minute caching and runId validators. Backend originally specified Java21/Boot3/Postgres16; frontend suggested mirroring Tergo's Maven/Jib/Vite setup. These are proposals subject to investigation, not settled constraints.

## Existing site: evidence and limits

Direct HTTPS retrieval in this environment failed hostname certificate verification; the web opener returned 502. A direct HTTP retrieval returned a WAF block (455). These are observations from this environment, **not proof the site is unavailable to ordinary users**. No browser tool was exposed, and no mobile, keyboard, screenshot, live chart interaction, source-head metadata or export download inspection was completed. The block was not bypassed.

Search-index extraction of first-party pages was available. The [homepage](https://pollofpolls.se/) presents opinion history, fieldwork-date explanations, seats excluding sub-4% parties, blocks, ten recent polls and a link to underlying chart table data. Its latest-polls table includes party values and interview periods, but no sample-size or method column in the retrieved table. The [Liberal party page](https://pollofpolls.se/liberalerna/) explicitly describes poll segments as interview periods. Preserve this link between estimates and evidence; adding n, accessible tables and conspicuous as-of dates is justified. A table-data link is verified; a working CSV export is not.

The [method page](https://pollofpolls.se/metod/) explains composition, fieldwork dates, sample-size weighting, house effects and approximation of seats. It describes compositional local regression, not the proposed state-space model. The [tracking-poll note](https://pollofpolls.se/uppdatering-av-poll-of-polls-med-avseende-pa-tracking-polls/) explains downweighting overlapping polls from one institute. These are useful explanation patterns, not evidence of calibrated coverage for a replacement model.

Recommended journeys:

1. Voter: see where opinion stood as of the last interview date, distinguish uncertainty from 30-day change, inspect a party, then understand what the estimate does and does not imply for seats.
2. Journalist: filter polls, inspect sample/fieldwork/provenance and corrections, download the exact filtered dataset, and cite a dated run.
3. Sharer: choose a stable-language page, preview/download its dated summary image, share it and land on an explanation consistent with the image.

Use “current opinion estimate” rather than an election forecast. Coalition majority probability is a probability about a seat mapping of current support, not the probability of forming a government. These wording recommendations follow the proposed product's no-forecast scope.

## Tergo verified locally

Read-only inspection at Tergo commit `19bed05b6ef1c8a98375f6e312de52ebde11a975` found:

| Concern | Observed configuration |
|---|---|
| Backend | Spring Boot 4.1.0, Java 25, JDBC/Flyway |
| Frontend | React 19.2.7, Vite 8.2.2, TypeScript; `src/main/frontend` |
| Maven | frontend-maven-plugin 2.0.2, pinned Node/npm, install then `npm ci`, then `npm run build` in generate-resources |
| Vite | `../resources/static/`, emptyOutDir, React and Tailwind plugins, `/api` development proxy; no explicit base |
| Container | Jib 3.5.2 docker profile, amd64/arm64, Chainguard JRE latest base |
| Database | compose files specify Postgres 18.3 |
| API generation | no OpenAPI generator/springdoc in inspected pom; do not claim a generated-client convention |

Source files: [pom](https://github.com/ahemberg/tergo/blob/19bed05b6ef1c8a98375f6e312de52ebde11a975/pom.xml), [Vite configuration](https://github.com/ahemberg/tergo/blob/19bed05b6ef1c8a98375f6e312de52ebde11a975/src/main/frontend/vite.config.ts), [package manifest](https://github.com/ahemberg/tergo/blob/19bed05b6ef1c8a98375f6e312de52ebde11a975/src/main/frontend/package.json), [compose](https://github.com/ahemberg/tergo/blob/19bed05b6ef1c8a98375f6e312de52ebde11a975/compose.yaml). Links may require repository access. No credentials or runtime environment files were copied. No production host, resource capacity, DNS, Traefik configuration or operating cost was verified. Targeted inspection found no Traefik/PathPrefix configuration in these build/deployment/docs locations.

Recommendation: reuse the build shape, not Tergo's application dependencies. Write generated assets under Maven's build output rather than deleting/rewriting tracked resources. Choose supported pinned toolchain/container versions deliberately at implementation time; copying `latest` does not establish reproducibility. Current [Spring system requirements](https://docs.spring.io/spring-boot/system-requirements.html) corroborate that Boot4 is available; Java21/Boot3 is not automatically the correct “mirror Tergo” choice. No upgrades to Tergo are proposed.

## Sharing, server rendering and publication

[Open Graph](https://ogp.me/) defines head metadata, canonical object URL, locales, image dimensions/type and image alternative text. Initial HTML should contain a single escaped route-specific title, description, canonical and OG/Twitter block with absolute HTTPS URLs, `html lang`, translated alternate links, and image alt text. Use a configured public origin rather than trusting incoming Host headers. Unknown routes should return 404, not an arbitrary language catch-all returning 200.

Server head injection plus React client mounting is sufficient for metadata without introducing a Node production service. Calling that “hydration” is inaccurate unless React HTML was rendered on the server: [React hydrateRoot](https://react.dev/reference/react-dom/client/hydrateRoot) expects existing server-generated React markup. A small Spring-rendered summary/table with client charts is an economical alternative if no-JS readable content becomes an acceptance condition. Full React SSR is an option, not a share-card prerequisite.

**X verification limitation:** both requested [large-image card documentation](https://developer.x.com/en/docs/x-for-websites/cards/overview/summary-card-with-large-image) and [getting-started documentation](https://developer.x.com/en/docs/x-for-websites/cards/guides/getting-started) redirected to a generic X developer overview. The [validator](https://cards-dev.twitter.com/validator) returned 403. Therefore this research does not establish today's exact X image constraints, validator usability, crawler JavaScript behavior, crop, description visibility or refresh schedule. Keep 1200×630 as a candidate and verify against a real preview on a public test hostname. Changing the image URL can distinguish image versions when fetched; it **cannot guarantee X refreshes the containing page exactly when the model run changes**. Do not promise text visibility when platforms may hide descriptions.

The current publish sequence is unsafe: marking a run ok makes it serveable before its images exist. Proposed sequence: compute/write unpublished run → render all required language images to staging → verify files → publish immutable image directory → commit published run/pointer transaction. A failed image render leaves the old published run intact; a failed DB publication may leave removable orphan files. One application instance and durable volume are sufficient initially. PostgreSQL locking alone does not distribute local image files across replicas.

Retain published images for the promised lifetime of shared links, or define a durable regeneration mechanism that preserves original bytes and renderer version. Seven-day deletion leaves old uncached share images missing; one-year cache headers do not preserve them at the origin. [RFC8246](https://www.rfc-editor.org/rfc/rfc8246.html) describes immutable responses as unchanged during freshness; it does not provide origin storage. Include an asset/render version if a deployment changes card appearance without a new data run. Overview reuse is reasonable for polls/method, but the proposed pollster `house-effects` key has no renderer; choose an explicit overview reuse or a real layout in a later ticket. A fixed 12-month summary image does not equal the selected multi-year chart: label the download “summary image” unless matching chart state is implemented.

Java's [Font.createFont](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/Font.html) supports font streams and derived sizes; preserve IBM Plex's [font license](https://github.com/IBM/plex). Font presence alone does not prove the minimal JRE image has all native rendering dependencies. Require a headless PNG smoke test in the exact production container on the deployed architecture, including Swedish glyphs, no missing-glyph boxes, both font styles and output paths. This report did not execute a renderer/container build.

## Locale and API contradictions to resolve

Recommendations from tracing the supplied page requirements:

| Conflict or omission | Minimal coherent contract |
|---|---|
| Row vs columnar history | Choose columnar `{runId,days,parties:{...}}` once; consistent `lo95/hi95` names; latest endpoint returns one day plus metadata. Do not claim 5× gzip improvement without measuring. |
| `step` absent in base API | Accept bounded positive steps (initially 1/3/7); define date anchoring and inclusive bounds; always include last date. Downsampling is display selection, never refitting. |
| Missing chart inputs | Expose election results, institute display/method/inclusion/count/last-date metadata, sample counts and last interview date; add server CSV with identical filters. Unknown institute method stays unknown. |
| 30-day change | Return/computably supply the comparison day's same-run value; missing history renders unavailable. Label percentage points. |
| Point seats vs mean seats | Add integer `pointSeats` for the 349-dot diagram, separate from posterior `meanSeats` and quantiles. Never independently round means into a parliament. Define below-threshold “would-be” seats or omit this speculative display. |
| Threshold probability | Expose `pAboveThreshold` explicitly; do not silently equate election admission with a national-share threshold when documenting an approximate seat model. |
| Raw polls mutate while run is stable | Serve run-pinned poll revisions or expose a distinct input/data version; a runId alone cannot identify changed raw data. |
| Multi-request page races | Resolve one published run at page load and allow requests pinned to it, including OG download; every run-dependent response identifies that same run. |
| ETag = runId | Validator must change when that representation changes, including code/schema, query and input variants. Use quoted content hash or a stable composite version, with proper conditional requests. |
| Language toggle “swap prefix” | Use an explicit route-name mapping: `/mandat` ↔ `/en/seats`, not `/en/mandat`. |
| localStorage plus server choice | Server cannot read localStorage. Prefer stable Swedish `/` and `/en` with a suggestion; if automatic negotiation is retained, persist preference in a server-readable cookie, honor q=0 and weighted language preferences, and use correct Vary/private caching. |

HTTP validation and negotiation recommendations follow [RFC9110](https://www.rfc-editor.org/rfc/rfc9110.html). URL/query resources are normally distinct cache keys; the point is not that every endpoint must have globally unique tags, but that runId is insufficient where bodies can change within a run (poll edits, schema deploys, translations). W3C's [language navigation guidance](https://www.w3.org/International/questions/qa-site-conneg) supports explicit user selection alongside negotiation. If two root languages remain negotiated, test cached redirects and crawler requests explicitly.

## Measured contrast and accessibility

Computed directly from supplied hex values using WCAG sRGB relative luminance and `(lighter+.05)/(darker+.05)`. No browser antialiasing included. Values displayed rounded; evaluate thresholds before rounding.

| Party | Light on #FAFAF7 | Light on white | Dark on #121417 | Dark on #1A1D22 | 55% marker light | 55% marker dark |
|---|---:|---:|---:|---:|---:|---:|
| S | 4.414 | 4.616 | 5.702 | 5.222 | 2.562 | 2.478 |
| M | 6.598 | 6.899 | 5.344 | 4.894 | 2.637 | 2.449 |
| SD | 2.022 | 2.114 | 12.080 | 11.063 | 1.468 | 4.391 |
| V | 6.727 | 7.034 | 4.920 | 4.506 | 2.858 | 2.276 |
| C | 3.583 | 3.747 | 9.521 | 8.720 | 2.003 | 3.647 |
| KD | 13.597 | 14.219 | 5.441 | 4.983 | 3.582 | 2.489 |
| MP | 3.098 | 3.240 | 9.379 | 8.589 | 1.793 | 3.632 |
| L | 5.409 | 5.656 | 7.116 | 6.517 | 2.382 | 2.976 |

The proposed SD replacement fails the stated 3:1 chart target. Most translucent markers fail too. S/C/MP also fail 4.5:1 for ordinary small coloured text on the light page. Keep labels in ink; revise chart colours/opacities or add sufficient outlines. Pale intervals can supplement a clear line and equivalent table but cannot be the only readable representation of uncertainty. Sources: [W3C non-text contrast](https://www.w3.org/WAI/WCAG21/understanding/non-text-contrast.html), [text contrast](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum).

Acceptance should target [WCAG2.2 AA](https://www.w3.org/TR/wcag/) rather than a Lighthouse score alone. Test keyboard-only selection/cursor/table controls, visible focus, touch targets, screen-reader table headings/captions, non-colour identification, zoom/reflow, tooltip dismissal and reduced motion. Do not report asymmetric credible intervals as mean ± half-width; display actual lower/upper bounds. A house-effect heatmap should make all cell values available through its table, including values hidden by the 0.3pp print cutoff.

SVG inherited fonts scale with the viewBox; a desktop-sized viewBox shrunk to mobile can make text illegible. Adjust actual chart layout and text sizes at narrow widths; tick-density changes alone are not a guarantee. Provide readable mobile table scrolling without making the whole page overflow.

The 150KB gzip target is a useful build gate, not a verified outcome. Prefer React-owned SVG with D3 scale/shape calculations; do not include d3-selection/axis unless needed. Measure the actual initial-route chunks and their gzip sizes, lazy-load secondary pages, and account for fonts/CSS/API payloads separately. [Vite's build documentation](https://vite.dev/guide/build.html) supports production bundling/chunking; only an actual build can establish this site's budget.

## Decision handoff and release checks

Next decisions: choose a dated estimate/product vocabulary, primary overview layout, stable locale policy, rendering/build contract, coherent versioned API, and published image retention. Hostname/brand and actual host capacity remain owner/infrastructure decisions. No production app was built here.

Before release, require: desktop/mobile visual review; keyboard and screen-reader walkthrough; measured payload and Lighthouse runs; exact-container font/PNG test; no-JS response metadata for every language/route; public X unfurl evidence recorded with date and device; stale/failed run and image-render failure checks; filtered CSV parity; request pinning during publication; date/step/304/cache-language tests; old shared image retrieval; and explicit distinction between model uncertainty and election predictions. The absence of a working validator should not prevent building a preview endpoint, but genuine platform unfurl remains an external acceptance check.

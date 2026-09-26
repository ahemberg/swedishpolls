# V1 launch acceptance

Status on 2026-09-13: **blocked, not accepted**. Tracking issue [#28](https://github.com/ahemberg/swedishpolls/issues/28) remains open.

## Statistical prerequisite

The recorded [release audit](release-audit.json) has verdict `blocked`, 50 blocking reasons and no waivers. Five of six blocking gates pass; `development_gates` fails. The reasons include unresolved grid boundaries, loss to the window-average reference, unscored FI folds and subgroup misfit. The [approved handoff](https://github.com/ahemberg/swedishpolls/issues/9#issuecomment-5575883916) requires stopping at a failed statistical gate. This review did not rerun the once-only audit, change the model or waive a gate.

The shipped freeze retains that verdict. As described in [publication.md](../publication.md), the production worker cannot publish estimates under this freeze. Passing software tests and building images do not certify the statistical method.

## Available delivery evidence

[CI run 34749689106](https://github.com/ahemberg/swedishpolls/actions/runs/34749689106) passed for commit `a4b957814db30755b0c5e2914af0e45f6ec4cb61`. Its logs record successful build/integration, model validation, Fallow, and image jobs. Image publication depends on the build/integration, model-validation and Fallow jobs.

The published image digests in that run are:

| Architecture | Image |
| --- | --- |
| amd64 | `ghcr.io/ahemberg/swedishpolls@sha256:2218432fda6cf147f672d70f34bcc878e1977df6de5c5ac35f35f0e1cd710691` |
| arm64 | `ghcr.io/ahemberg/swedishpolls@sha256:6274e939a816d2d216a3069b783512722a1c590757bf65780aa884b017146e48` |

These are inspected build candidates, pending operator selection of the release. The hosted workflow checks both exact images for synthetic Java2D rendering and Swedish glyphs, Compose readiness, UID 10001 storage writes and persistence across restart. ARM64 uses QEMU. This does not establish real publication-card rendering or selected-host capacity.

The run retained `build-reports`, `fallow-reports`, `verified-classes` and `image-smoke`; the artifact API reported each unexpired during this review. `verified-classes` has one-day retention, so these artifacts are not a permanent launch archive. Earlier image evidence is in [build-evidence.md](build-evidence.md). [Page rendering checks](page-rendering.md) cover sixteen seats/coalitions language, viewport and theme combinations in headless Chromium, and explicitly exclude physical devices and assistive technology.

## Outstanding acceptance

| Required evidence | Status and next input |
| --- | --- |
| Statistical release permission | Blocked by the recorded verdict. Requires upstream resolution under an approved protocol or an explicit recorded owner waiver. |
| Selected host and release identity | Operator selected this local machine and supplied HTTP access at localhost:8080. Memory allocation, public HTTPS origin, final site name and exact release identity remain unconfirmed. |
| Real publication cards on both architectures | Pending validated publications and exact-image checks, including Swedish glyphs. Synthetic font smoke checks are insufficient. |
| Publication failure and persistence checks | Pending exact-release checks for failed checks/rendering, interrupted staging, first-publication unavailability, failed-update staleness, permanent bytes across updates/restart and pinned responses. Existing integration tests are supporting evidence only. |
| Every approved route without JavaScript | Pending exact-release checks in both languages for headline/date/results, escaped metadata, canonical/alternate links, historical FI, image dates, invalid routes/filters and unsupported values. |
| Physical devices and accessibility | Pending mouse/touch/keyboard, focus, contrast, summaries/table alternatives and overflow checks across mobile/desktop and light/dark, plus a real public share preview. |
| Production host resources | Pending a complete pipeline measurement including publication assets and peak memory. Required: finish within 30 minutes and fit allocated memory. |
| Optimization targets | Pending full recompute, initial JavaScript gzip size and Lighthouse mobile measurements. Targets: under ten seconds, under 150 KB and performance/accessibility scores at least 90. Target misses must be reported separately from required gates. |
| Final acceptance and artifact retention | Pending durable evidence for the selected release and a passed or explicitly owner-waived result for every launch gate. |

The owner operates deployment. Custom coalitions remain outside v1 launch dependencies. Recovery controls, notifications and backup/restore tooling remain deferred.

## Verification of this record

`./mvnw spotless:apply` passed on 2026-09-13. Local `./mvnw verify` stopped while extracting Node with `No space left on device`; the root filesystem had about 9 MB available. The local test suite therefore did not complete. The successful hosted run above applies to the named source commit, not this documentation change.

## Local service observation

On 2026-09-13 the operator selected this machine and started the service at `http://localhost:8080`. HTTP checks without JavaScript returned 200 for all 30 approved page routes: six non-party page families and nine party pages in each language, using the translated slugs in `SiteRoutes`. The overview showed the first-publication unavailable message and a source-check timestamp. `/api/v1/publication` and `/api/v1/estimates/latest` both returned 503 with `estimates_unavailable` and source-check time `2026-09-13T10:36:02.228056Z`. An unknown route and unknown party returned 404.

The overview identifies the site as `Swedish Polls` and its canonical URL as `https://localhost/`. These are observed settings, not a confirmed public deployment origin/name. The served script `/assets/main-BbKpT3Gm.js` measured 72,209 bytes after Python gzip compression, below the 150 KB target for that script alone. This does not measure browser transfer size, additional dynamically loaded JavaScript or Lighthouse performance.

These observations establish local HTTP reachability and first-publication unavailability only. They do not verify populated results, publication images, browser interactions, exact container identity or either architecture's deployment acceptance. No running service was restarted or changed.

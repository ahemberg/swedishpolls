# A publication is a set of immutable documents and staged image bytes

A published estimate has to stay readable exactly as it was published, and a failed update must
never replace it with a partial one. Two decisions follow from that.

## The worker renders; a request only cuts

One worker holds a PostgreSQL advisory lock for the whole of an attempt. It reads the active
source snapshot, runs the frozen estimator once, and renders every versioned surface into
`publication_document` rows, one per surface and language. A request never fits a model: a date
range, a display step, a poll filter, a coalition selection and a language all select from
documents that already exist.

The alternative, computing a response per request, was rejected on two counts. It would make the
published number depend on when it was asked for, and it would put a minutes-long fit behind an
HTTP timeout. Storing the rendered documents also gives the retention requirement for free: an
old publication is served from the rows it was published with, not recomputed from an estimator
that has since changed.

Filtered polls and the CSV download are the exception. They are not model output, so they are
read at request time from the publication's own pinned snapshot rather than from the active one.
Pinning is what makes them safe: an ingestion correction creates a new snapshot and a new
publication, and it cannot change a page that already resolved its publication.

## Documents live in PostgreSQL, image bytes on a volume

Documents are rows. Image bytes are files under `PUBLICATION_ROOT`, indexed by
`publication_asset`. A candidate's cards are written to a private staging directory, read back and
compared against the digest recorded for them, moved into place with a single atomic rename, and
verified again before the current pointer moves. An interrupted staging leaves a staging
directory and nothing else; no byte it wrote is reachable from any publication.

The pointer switch and the asset rows commit in one transaction, after the rename. Until it
commits, the candidate's `publication` row says `candidate` and no reader resolves it. On any
failure the candidate is abandoned, its documents are deleted, and the previous publication stays
current with a staleness notice on the pointer row rather than on its own immutable rows.

An asset link carries its publication, card, language and version. A renderer change writes a new
version beside the old one; published bytes are never overwritten, so an immutable link stays
honest for the year it is cached for.

## The estimator the worker runs is shipped, not read from the repository

`src/main/resources/publication/model-freeze.json` carries the protocol versions, seed, draw
count, interval levels, publication resolution, coverage rules, per-period fit parameters and the
release verdict. The development evidence under `docs/validation` stays the source of truth, and
`ModelFreezeTest` fails when the two disagree. The container image ships classes and resources
only, so the running application cannot read `docs/validation`; a purpose-built resource is
preferred to shipping the whole evidence directory or recomputing a verdict at start-up.

A blocked release verdict publishes nothing. That is the state this decision is recorded in: the
audited verdict in `docs/validation/release-audit.json` blocks release, so a deployment records a
`blocked` attempt and every estimate surface answers `estimates_unavailable`.

# React is the only site renderer

The site currently renders each page twice: Java builds Basic HTML, then React replaces it after
mounting. The two renderers have already drifted without a test noticing. React will become the
only renderer, and the backend will provide JSON and static files rather than Basic HTML.

## Page delivery

The backend will serve one static `index.html` for every site path. React will own routing,
translated paths and the not-found page. Unknown paths will therefore return HTTP 200 with a
client-rendered not-found page, which search engines may treat as a soft 404.

React will fetch page data from `/api/v1/*`; no bootstrap payload will be embedded in the page.
The extra request waterfall is accepted. Existing v1 responses will keep their shapes, and new
responses may be added. Removing publication metadata's `assets` field and `/assets/*` is the sole
breaking exception. Public data downloads, including
`/api/v1/polls.csv`, remain guaranteed without JavaScript as part of the site's public-interest
commitment.

Page wording will move from `SiteText` to the frontend. Java's publication `Translations` will
remain because `PublicationDocuments` uses it to render stored documents.

## Accepted losses

The static page will have no route-specific body or `head` content. Googlebot renders JavaScript on
a deferred pass, while Bing and DuckDuckGo have weaker JavaScript support. Reduced body indexing
is accepted.

Route-specific Open Graph and Twitter metadata will disappear, so shared links may preview as bare
URLs. Direct share-image downloads disappeared with the publication-asset subsystem. These are
accepted product losses, not temporary gaps.

This reverses ADR 0007's decision to retain immutable, versioned image bytes. Those images existed
to provide route-specific share previews and direct downloads. Once both uses are dropped, keeping
their renderer, database index and durable volume has no product purpose. Publication documents
remain immutable.

Server-side React rendering was rejected. The application is a single Java module packaged by
Jib. Rendering React on the server would require a Node runtime in that image or a second service,
and neither cost is justified for this site.

## Migration constraint

The frontend test harness and the existing page-content assertions must move to frontend tests
before the Java renderer is deleted. Both renderers stay live while those assertions are ported,
and the Java output is the comparison point. Issue #229 removed the publication-asset subsystem
and revised [ADR 0007](0007-publications-are-immutable-documents.md).

# Java package organization

Read [ADR 0008](adr/0008-java-package-organization.md) for the decision and its rationale. This
guide is the convention to follow when adding Java classes, moving classes, or changing package
dependencies. The migration is complete; see [Current state](#current-state).

## Package map

Responsibilities first, Spring MVC roles as subpackages where they exist. Empty role packages are
not required: a role subpackage appears when the responsibility has that role.

```
se.swedishpolls
├── Application                      root: boot entry only
├── source                           reading polls and stored reference inputs into the system
│   ├── (root)                       plain classes: PollCsv, PollQuery, Roster and Snapshot
│   ├── source.service               use cases: SnapshotIngest and its PollSourceClient @HttpExchange
│   │                                interface, PollQueryService, election and allocation lookups,
│   │                                SourceHttpConfig
│   ├── source.repository            JDBC lookups: snapshots, coverage periods, election references,
│   │                                national allocation rules
├── estimation                       the estimator and its model, all plain Java
│   └── (root)                       PollObservations, WindowFilter, DailyStateSpace, EstimateHistory,
│                                    HouseEffects, RecencyBaseline, JointUncertainty, Coalitions,
│                                    ComparableRemainder, SeatOutcomes, PredictiveComparison,
│                                    CoverageValidation, Development*, ReleaseAudit and national
│                                    seat allocation mathematics
├── publication                      rendering and storing published documents
│   ├── (root)                       plain classes: PublicationDocuments, PublicationRun,
│   │                                ModelFreeze, Translations, the values a publication is read
│   │                                through (PublicationHeader, CurrentPublication, ModelRun,
│   │                                PinnedSnapshot and PublicationOutcome) and the Digest their
│   │                                bytes are recorded under
│   ├── publication.service          the publish use case: Publisher; the read use case:
│   │                                Publications and PublicationMetadata; the nightly entry point:
│   │                                PublisherScheduler
│   └── publication.repository       document rows: PublicationStore; the worker's
│                                    advisory lock: PublicationLock
├── model                            shared immutable values used by more than one responsibility
│   └── (root)                       ElectionReference and NationalAllocationRule
└── web                              the HTTP surface and the server-rendered site
    ├── (root)                       plain site-rendering classes: PublicSite, SiteHtml, SiteText,
    │                                SiteFormat, SiteAssets, SiteRoutes, SiteBootstrap, and the
    │                                stored-history request sampling: EstimateQuery, and the
    │                                source chart's date axis: SourceChart
    └── web.controller               PageController, ApiV1Controller, ApiExceptionHandler,
                                     ApiErrors, Responses
```

Values needed by a single responsibility stay in that responsibility. A value moves to `model`
only when a second responsibility needs it. `model` depends on nothing except the JDK and EJML.

## Roles

**Controllers** (`web.controller`) do HTTP binding, input validation, status and header handling,
and response formatting. They call application services and never touch repositories, `JdbcClient`,
filesystem storage, or fitting operations directly. Cross-cutting error responses live in
`ApiExceptionHandler`; route-specific formatting stays in the controller.

**Services** (`*.service`) coordinate a real use case and its transaction requirements. A service
calls repositories and plain calculations, never the reverse. Services do not need
one-implementation interfaces; a concrete class is the norm.

**Repositories** (`*.repository`) own SQL and persistence operations, using `JdbcClient`. Spring
Data/JPA is not required and not used; a plain class with repository-named methods is the standard.
Repositories depend on values, not on controllers or service implementations.

**Scheduled entry points** (`@Scheduled` classes) are thin: read configuration, call one service
method, log the outcome. `PublisherScheduler` sits in `publication.service` and invokes the existing
ingest-then-publication flow. This keeps the value packages independent of the services that use
them. They carry no business logic.

**Configuration** for a responsibility lives in that responsibility, including the registration of
the outbound HTTP clients it owns. Only wiring that spans responsibilities, such as scheduling
enablement, stays with `Application`.

**Outbound HTTP clients** are `@HttpExchange` interfaces declared next to the service that uses
them and registered through that responsibility's Spring configuration, per
[ADR 0004](adr/0004-spring-infrastructure-and-integration-tests.md). The poll source client is
registered by `source.service.SourceHttpConfig` (#116).

**Numerical calculations and immutable model values** are plain Java with no Spring annotations.
The estimator, coverage validation, roster composition, and seat allocation stay plain, tested with
plain JUnit, per [ADR 0004](adr/0004-spring-infrastructure-and-integration-tests.md).

## Dependency direction

Acyclic, in one direction:

```
web ──▶ publication.service, source.service      web uses application services
publication ──▶ estimation, source               publication coordinates both
estimation ──▶ source values                     estimation uses source values
repositories ──▶ values only                     no controllers, no service implementations
model ──▶ (nothing)
```

No package imports upward from this order. The public surface of a responsibility for callers in
other packages is its service classes: `source.service.SnapshotIngest` for ingestion,
`publication.service.Publisher` for publishing and `publication.service.Publications` for reading
what was published. Everything else defaults to package-private or
stays inside its responsibility. Internal classes that only serve one responsibility are not opened
up to serve another.

Tightly coupled estimation and validation internals stay together in `estimation`; this first pass
does not split them by name.

## Splitting the existing shared classes

Two production classes mix a JDBC lookup with a calculation at the time this convention was
adopted. The migration separates them along the repository/calculation boundary:

- `Roster`: the supported-period lookup (`periods()`, backed by `JdbcClient`) becomes a method on a
  `source.repository` class. `compose`, `supportedPeriod`, and the `CoveragePeriod` and
  `Composition` values stay as plain calculations in `source`, independent of the repository
  (#116 moved the grouping there rather than into `estimation`, because estimation is not its only
  caller: publication and web resolve poll requests against it too).
- `NationalSeats`: the rules lookup (`rules(electionYear)`, backed by `JdbcClient`) becomes a
  repository method. `qualifies`, `allocate`, `distribute`, and `SeatDraws` stay as plain
  calculations in `estimation`.

## Placement examples

**A new endpoint**: `GET /api/v1/turnout`.
Handler method in `web.controller.ApiV1Controller`. The controller validates `@RequestParam` and
`@PathVariable` input, sets status and headers, and formats the response. It calls a service
method; if the logic needs a new use case, the service class goes into the owning responsibility's
`service` package. The response record is a plain immutable value, in `model` only if another
responsibility needs it.

**A new query**: polls by house.
SQL goes into a `source.repository` class as a `JdbcClient` method. The repository is called by a
`source.service` class if the web layer needs the result; the controller never calls the
repository directly.

**A new calculation**: coalition volatility.
A plain `final` class in `estimation`, a plain JUnit test beside it, no Spring annotations. If a
publication surface needs it, `publication.service` calls it; the web layer never does.

## Tests

- Test packages mirror production packages: a class in `estimation` is tested from
  `se.swedishpolls.estimation`.
- Package-private access is the default for internals; tests in the same package use it directly.
- Shared test-only fixtures that belong to no responsibility (`TestDatabase`, `PollCsvFixtures`)
  live in a test-support package under the test sourceset (`se.swedishpolls.testsupport`), so no
  production package ever imports a test helper.
- A fixture that needs a responsibility's package-private seam stays in that responsibility's test
  package and is public only to the extent another package's tests need it. `publication.TestFreeze`
  builds an adjusted freeze through package-private parsing, and
  `publication.service.TestPublication` builds a worker through the package-private constructor, so
  a web test asks publication for a publication instead of widening a production seam.
- Production Spring wiring is verified by `@SpringBootTest` integration tests per [ADR 0004](adr/0004-spring-infrastructure-and-integration-tests.md): each
  responsibility keeps integration coverage that starts the real Spring context instead of
  constructing production components manually.

## Architecture checks

The package boundaries this convention creates are the dependency rules `ArchitectureTest`
enforces:

1. `web.controller` calls application operations through services, never through repositories, `JdbcClient` or
   `JdbcTemplate`, filesystem access, or estimation internals.
2. Repositories never depend on controllers or service implementations.
3. `estimation` never depends on `publication` or `web`; `publication` may depend on `estimation`
   and `source`; `source` depends on nothing above it.
4. No package dependency cycle.
5. Only `Application` sits directly in `se.swedishpolls`.
6. `@Scheduled` classes only call service methods.

[ADR 0005](adr/0005-static-analysis-gating.md) declined ArchUnit while all production files lived in one package, with an explicit
condition to reconsider when such boundaries exist. #119 adopts the ArchUnit core library through
the existing JUnit runner.

## Current state

This section describes the tree and is updated as migration tickets land, unlike the rest of this
guide, which states the target.

`source` is organized per this convention as of #117: `source` (root) holds `PollCsv`, `PollQuery`,
`Roster` and `Snapshot`. `source.service` holds ingestion, poll-query, election-reference and
allocation-rule operations. `source.repository` holds their JDBC lookups. The shared `Snapshot`
value stays in `source`; `ElectionReference` and
`NationalAllocationRule` sit in `model` because repositories, services, estimation and publication
use them. Tests moved with their classes, and the shared CSV fixtures live in
`se.swedishpolls.testsupport`.

The numerical and validation cluster is organized in `estimation` as of #117. Its plain classes and
matching tests moved together, including `PollObservations` and `WindowFilter`, so their
package-private numerical cooperation remains local. Publication now calls a public house-effect
calculation instead of accessing fitting spans.

`publication` and `web` are organized per this convention as of #118, so only `Application`
remains directly in `se.swedishpolls`. `publication` (root) holds `ModelFreeze`, `PublicationRun`,
`PublicationDocuments`, `Translations`, and the values a publication is read through:
`PublicationHeader`, `CurrentPublication`, `ModelRun`, `PinnedSnapshot`, `PublicationOutcome` and
the `Digest` operation their stored bytes are recorded under. `publication.service` holds
`PublisherScheduler`, which owns the one nightly scheduled entry point, `Publisher`, the
`Publications` read service and `PublicationMetadata`;
`publication.repository` holds `PublicationStore` and the `PublicationLock` advisory lock. `web` (root) holds the
site-rendering cluster and `EstimateQuery`; `web.controller` holds `PageController`,
`ApiV1Controller`, `ApiExceptionHandler`, `ApiErrors` and the shared `Responses`
cache and ETag helper.

Three couplings were resolved rather than carried across the new boundary. The publication values
left `PublicationStore`, so `web.controller` names them without importing a repository, and storage
records an attempt against `PublicationOutcome` rather than a type the worker owns. The worker's
own SQL moved to the repository: the run identifier to `PublicationStore.nextRunId`, the advisory
lock to `PublicationLock.whileHeld`, which holds it for exactly one attempt. `web.controller`
computes its response ETag with the JDK digest instead of the publication's, and
`PublicationDocuments` words its own movement figure instead of borrowing the site's.

#119 completes the migration with compiled dependency checks. It moves both scheduled entry points
into their service packages to remove package cycles without changing scheduling or publication
behavior. `ArchitectureTest` checks every production class in `target/classes`, excluding test
fixtures. Run it with `./mvnw -Dtest=ArchitectureTest test`, or through `./mvnw verify`.
Controllers can use response values and web formatting helpers; their application operations go
through services. Scheduled entry points may read returned values and log outcomes, but invoke
application operations only on services.
[ADR 0003](adr/0003-immutable-model-values.md) (immutable model values),
[ADR 0004](adr/0004-spring-infrastructure-and-integration-tests.md) (Spring infrastructure and
integration tests), and [ADR 0007](adr/0007-publications-are-immutable-documents.md) (publications
are immutable documents) are unaffected by this
convention and remain in force. The change retains `JdbcClient` and the single Maven module.

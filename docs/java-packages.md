# Java package organization

Read [ADR 0008](adr/0008-java-package-organization.md) for the decision and its rationale. This
guide is the convention to follow when adding Java classes, moving classes, or changing package
dependencies. The current code is not yet migrated; see [Current state](#current-state-unmigrated-code).

## Package map

Responsibilities first, Spring MVC roles as subpackages where they exist. Empty role packages are
not required: a role subpackage appears when the responsibility has that role.

```
se.swedishpolls
├── Application, ImageSmokeCheck     root: boot entry and image-smoke launcher only
├── source                           reading polls into the system
│   ├── (root)                       plain classes: PollCsv, PollObservations, PollQuery, WindowFilter
│   ├── source.service               use cases: SnapshotIngest and its PollSourceClient @HttpExchange interface
│   ├── source.repository            JDBC lookups: snapshot rows, election references
│   └── source                       scheduled entry points live beside the service they call
├── estimation                       the estimator and its model, all plain Java
│   └── (root)                       DailyStateSpace, HouseEffects, RecencyBaseline, JointUncertainty,
│                                    PredictiveComparison, CoverageValidation, Development*,
│                                    roster composition and seat allocation mathematics
├── publication                      rendering and storing published documents
│   ├── (root)                       plain classes: PublicationDocuments, ShareImages, PublicationRun
│   ├── publication.service          the publish use case: Publisher
│   └── publication.repository       document rows, asset links: PublicationStore
├── model                            shared immutable values used by more than one responsibility
│   └── (root)                       Translations, Coalitions, ComparableRemainder, SeatOutcomes,
│                                    ModelFreeze and other frozen values (ADR 0003, ADR 0007)
└── web                              the HTTP surface
    └── web.controller               ApiV1Controller, AssetController, ApiExceptionHandler, ApiErrors
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
method, log the outcome. `SnapshotIngestScheduler` sits in `source`, `PublisherScheduler` in
`publication`. They carry no business logic.

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
other packages is its service classes: `source.service.SnapshotIngest` for ingestion and
`publication.service.Publisher` for publishing. Everything else defaults to package-private or
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
- Shared test-only fixtures (`TestDatabase`, `TestPublication`) live in a test-support package
  under the test sourceset (`se.swedishpolls.testsupport`), not inside responsibility packages, so
  no production package ever imports a test helper.
- Production Spring wiring is verified by `@SpringBootTest` integration tests per [ADR 0004](adr/0004-spring-infrastructure-and-integration-tests.md): each
  responsibility keeps integration coverage that starts the real Spring context instead of
  constructing production components manually.

## Rules for later architecture checks

The package boundaries this convention creates are the dependency rules future architecture checks
must enforce:

1. `web.controller` depends only on service classes, never on repositories, `JdbcClient` or
   `JdbcTemplate`, filesystem access, or estimation internals.
2. Repositories never depend on controllers or service implementations.
3. `estimation` never depends on `publication` or `web`; `publication` may depend on `estimation`
   and `source`; `source` depends on nothing above it.
4. No package dependency cycle.
5. Only `Application` and `ImageSmokeCheck` sit directly in `se.swedishpolls`.
6. `@Scheduled` classes only call service methods.

[ADR 0005](adr/0005-static-analysis-gating.md) declined ArchUnit while all production files lived in one package, with an explicit
condition to reconsider when such boundaries exist. This convention is that condition; the tickets
that implement the migration decide on the tool.

## Current state (partially migrated)

This section describes the tree and is updated as migration tickets land, unlike the rest of this
guide, which states the target.

`source` is organized per this convention as of #116: `source` (root) holds the plain parsing,
query and roster-grouping classes (`PollCsv`, `PollQuery`, `Roster`) and the
`SnapshotIngestScheduler` entry point; `source.service` holds `SnapshotIngest` (with its
`PollSourceClient` HTTP exchange interface), `PollQueryService`, and the `SourceHttpConfig` that
registers the poll-source HTTP client; `source.repository` holds `SnapshotRepository` and
`CoveragePeriodRepository`; the shared `Snapshot` value sits in `source` (root). Tests moved with
their classes, and the shared CSV fixtures live in `se.swedishpolls.testsupport`.

Everything else still lives in `se.swedishpolls` and this guide describes the target for it, not
the tree. Estimation, publication and web classes move in the tickets that own those migrations
(#117, #118, #119). [ADR 0003](adr/0003-immutable-model-values.md) (immutable model values),
[ADR 0004](adr/0004-spring-infrastructure-and-integration-tests.md) (Spring infrastructure and
integration tests), and [ADR 0007](adr/0007-publications-are-immutable-documents.md) (publications
are immutable documents) are unaffected by this
convention and remain in force. The change retains `JdbcClient` and the single Maven module.

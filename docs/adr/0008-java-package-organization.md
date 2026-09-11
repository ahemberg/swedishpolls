# Organize Java code by responsibility with Spring MVC role subpackages

Production Java now spans ingestion, estimation, publication and an HTTP surface, all in the single
package `se.swedishpolls`. The decision is to organize code by responsibility — `source`,
`estimation`, `publication`, `web` — with the classic Spring MVC roles as subpackages where a
responsibility needs them: `source.service`, `source.repository`, `publication.service`,
`publication.repository`, `web.controller`. Only `Application` and the intentional image-smoke
launcher stay directly in the root package. The full convention, placement examples and test rules
are recorded in the contributor guide at [docs/java-packages.md](../java-packages.md); follow that
guide whenever adding Java classes, moving classes, or changing package dependencies.

The responsibilities and their dependency direction are acyclic: estimation uses source values;
publication coordinates source and estimation; web uses application services; repositories depend
on values only and never on controllers or service implementations. Controllers reduce to HTTP
binding, input validation, status and header handling, and response formatting, and call
application services rather than repositories, JDBC, filesystem storage or fitting operations.
Services coordinate use cases and transactions; scheduled entry points invoke services. Shared
immutable values (ADR [0003](0003-immutable-model-values.md)) and published documents (ADR [0007](0007-publications-are-immutable-documents.md)) keep their existing contracts; the
packages place them, they do not change them. Spring infrastructure remains as decided in [ADR 0004](0004-spring-infrastructure-and-integration-tests.md).

## Why

The one-package arrangement made sense when the tree was a few estimator files. At 39 production
classes across four distinct concerns it no longer says anything: nothing in the layout distinguishes
a controller from a repository from a calculation, so placement is decided fresh — and differently —
by each contributor. Naming the responsibilities and roles up front makes placement mechanical and
gives the later architecture checks rules worth enforcing. This is the condition [ADR 0005](0005-static-analysis-gating.md) recorded
for reconsidering ArchUnit.

## Considered options

- Keep the single flat package: rejected. The package name carries no information, and the shared
  classes (`Roster`, `NationalSeats`) that mix JDBC lookup with calculation cannot be separated
  without some boundary to separate them along.
- Layer-first packages (`controller`, `service`, `repository` at the top): rejected because layers
  scatter one use case across packages and put the estimator's internals in the same bag as HTTP
  formatting. Responsibilities match how the system is reasoned about and changed; roles are the
  secondary cut within a responsibility.
- A second Maven module for the estimator: rejected for this pass. The boundaries are package-level
  and one module keeps the build and the frontend gate as they are.

## Consequences

No application code moves in this ticket; follow-up tickets implement the arrangement, including
splitting the JDBC lookup code out of `Roster` and `NationalSeats`. Tightly coupled estimation and
validation internals stay together for the first pass. The later architecture checks must enforce
the dependency rules the guide records; whether they run as ArchUnit rules or another mechanism is
decided by those tickets, not here. CONTEXT.md stays the domain glossary and gains no architecture
content; this ADR and the guide carry the structure.

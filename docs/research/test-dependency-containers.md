# Test dependency containers

Investigated 2026-09-09 against Spring Boot 4.1.1, Testcontainers 2.0.5, and the
current `swedishpolls` and `tergo` source trees.

## Observed facts

- `swedishpolls` uses Spring Boot 4.1.1 and Java 25. Its Compose and CI definitions
  pin PostgreSQL 18.4 by digest. The integration tests currently read
  `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD`; five tests create
  isolated schemas, while `ApplicationIT` starts the packaged JAR as a child JVM.
  [Project POM](../../pom.xml#L4-L15), [Compose service](../../compose.yaml#L1-L16),
  [ApplicationIT](../../src/test/java/se/swedishpolls/ApplicationIT.java#L16-L85),
  [CI database service](../../.github/workflows/verify.yml#L10-L35).
- Spring Boot 4.1.1 manages `testcontainers-postgresql` at 2.0.5, so the project
  should omit an explicit Testcontainers version. The PostgreSQL module supplies
  `PostgreSQLContainer`; the JDBC driver remains a separate dependency, which this
  project already has. [Boot managed dependencies](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html),
  [Testcontainers PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/).
- Spring Boot can manage container beans and derive JDBC and Flyway connection
  details from a `JdbcDatabaseContainer` annotated with `@ServiceConnection`.
  This requires the test-scoped `spring-boot-testcontainers` module. Boot recommends
  bean-managed containers when a cached Spring test context depends on them.
  [Boot Testcontainers reference](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html#testing.testcontainers.service-connections),
  [container lifecycle](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html#testing.testcontainers.lifecycle).
- That service-connection pattern fits Tergo because its tests import a
  `@TestConfiguration` into `@SpringBootTest`. `swedishpolls` has no equivalent
  Spring test context, and a connection bean in the Failsafe JVM cannot configure
  the separately launched packaged JAR. [Tergo configuration](https://github.com/ahemberg/tergo/blob/19bed05b6ef1c8a98375f6e312de52ebde11a975/src/test/java/se/tergo/common/testing/TestcontainersConfiguration.java),
  [Tergo integration-test base](https://github.com/ahemberg/tergo/blob/19bed05b6ef1c8a98375f6e312de52ebde11a975/src/test/java/se/tergo/common/testing/IntegrationTest.java).
- Boot's Docker Compose module discovers standard names such as `compose.yaml`,
  runs `docker compose up`, creates service connections from supported images such
  as `postgres`, and stops services at JVM shutdown. Existing services are reused
  and left running. Tests skip Compose by default. The prerequisites are a Docker
  daemon plus Docker Compose 2.2.0 or newer on `PATH`.
  [Boot development services](https://docs.spring.io/spring-boot/reference/features/dev-services.html#features.dev-services.docker-compose),
  [Compose in tests](https://docs.spring.io/spring-boot/reference/features/dev-services.html#features.dev-services.docker-compose.tests).
- Tergo uses the documented runtime, optional `spring-boot-docker-compose`
  dependency and names its nonstandard `compose.dev.yaml` explicitly.
  `swedishpolls` already has a PostgreSQL healthcheck, but open issue
  [#27](https://github.com/ahemberg/swedishpolls/issues/27) reserves the standard
  `compose.yaml` filename for the production application stack. Local development
  therefore needs the same explicit file separation as Tergo. [Tergo POM](https://github.com/ahemberg/tergo/blob/19bed05b6ef1c8a98375f6e312de52ebde11a975/pom.xml#L89-L94),
  [Tergo application configuration](https://github.com/ahemberg/tergo/blob/19bed05b6ef1c8a98375f6e312de52ebde11a975/src/main/resources/application.yml#L17-L20),
  [swedishpolls Compose service](../../compose.yaml#L1-L16).

## Recommendation

Use one suite-wide `PostgreSQLContainer` in a small test helper, pinned to the same
PostgreSQL 18.4 digest as Compose. Add only `testcontainers-postgresql` with test
scope. Start the singleton once in the Failsafe JVM, expose its JDBC URL, username,
and password, and keep the existing per-test schema isolation. Testcontainers'
documented singleton pattern leaves shutdown to Ryuk at JVM exit. Do not enable
experimental reusable containers: they require local opt-in, remain running, and
are explicitly unsuitable for CI. [Singleton lifecycle](https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/#singleton-containers),
[reusable-container limits](https://java.testcontainers.org/features/reuse/).

Pass all three generated connection values into each packaged-JAR
`ProcessBuilder`. This special handling is required for `ApplicationIT` and the
packaged-application method in `SnapshotIngestIT`; the child cannot see a parent
Spring context or its `@ServiceConnection`. Keep the parent container alive until
the child exits.

Do not add `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, or a
`@ServiceConnection` configuration in the first ticket. They add no behavior to
the current plain JUnit and child-process tests. If a later ticket introduces a
real `@SpringBootTest`, copy Tergo's bean pattern then; Boot will provide JDBC and
Flyway connection details and manage the bean lifecycle.

For local runs, add `spring-boot-docker-compose` with runtime scope and
`optional=true`. Put the development PostgreSQL service in `compose.dev.yaml` and
set `spring.docker.compose.file=compose.dev.yaml`, leaving `compose.yaml` to #27.
`DATABASE_PASSWORD` must still be supplied because the existing Compose
interpolation requires it. The normal command becomes
`DATABASE_PASSWORD=... ./mvnw spring-boot:run`. Keep Boot's default
`start-and-stop` lifecycle. Boot excludes Compose support from repackaged archives
by default, which keeps the production JAR free of development startup behavior.
[Boot module and archive behavior](https://docs.spring.io/spring-boot/reference/features/dev-services.html#features.dev-services.docker-compose).

## Ticket split

### 1. Provision PostgreSQL from integration tests

Add the managed `testcontainers-postgresql` test dependency and one shared test
helper. Route every database integration test through it, pass its connection
values to both packaged-JAR child processes, and remove the GitHub Actions
PostgreSQL service and database environment variables.

Acceptance checks:

- With no PostgreSQL container already running and no `DATABASE_*` variables set,
  `./mvnw --batch-mode --no-transfer-progress clean verify` starts PostgreSQL 18.4,
  passes all unit and integration tests, and removes the test container after the
  Maven JVM exits.
- `ApplicationIT` still verifies the packaged JAR, assets, Flyway migration, and
  PostgreSQL server version. The packaged-application test in `SnapshotIngestIT`
  still observes the scheduled ingest.
- CI passes without a workflow-level PostgreSQL service.

### 2. Start local PostgreSQL with `spring-boot:run`

Add the runtime, optional `spring-boot-docker-compose` dependency. Move the local
PostgreSQL service to `compose.dev.yaml`, preserve its healthcheck, credentials and
volume, and point Spring Boot at that file. Keep production `compose.yaml` in #27's
scope.

Acceptance checks:

- With Docker running and no project database container started manually,
  `DATABASE_PASSWORD=local-test ./mvnw spring-boot:run` starts the Compose database,
  waits for readiness, connects through Boot's PostgreSQL service connection, and
  starts the application.
- Stopping that Maven process stops a database started by that process without
  deleting the named volume. If the database was already running, Boot leaves it
  running.
- `mvn verify` uses Testcontainers and does not start `compose.dev.yaml`.

The tickets are independent.

## Risks

- Both workflows require a reachable Docker API. Testcontainers supports recent
  Docker Engine and Docker Desktop directly; alternative runtimes may need manual
  configuration. [Testcontainers runtime requirements](https://java.testcontainers.org/supported_docker_environment/).
- First execution must pull PostgreSQL and Testcontainers' cleanup images, so it
  will be slower and depends on registry availability.
- The Compose file binds host port 5432 by default. Local startup still fails if
  that port is occupied unless `DATABASE_PORT` is changed.
- During a Compose-backed local run, Boot service connection details take
  precedence over `spring.datasource.*`. A developer intending to use an external
  database must set `SPRING_DOCKER_COMPOSE_ENABLED=false`.

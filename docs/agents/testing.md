# Testing conventions

Read this file when adding tests or deciding which test tier owns a behavior.

- Controller tests use `@WebMvcTest` with `@MockitoBean` services. Assert request binding, status
  codes, error bodies, content types, cache headers and ETags here.
- Service tests use JUnit and Mockito without Spring. Assert orchestration and transaction outcomes
  through the service's public methods.
- Repository tests use `@JdbcTest`, `TestDatabase.Configuration`, Flyway and PostgreSQL. Assert SQL,
  constraints and row mapping here. Keep direct migration tests when they must control the schema
  version themselves.
- Integration tests use `@SpringBootTest` for happy-path production wiring. Keep at least one for
  each responsibility, and move edge cases to the narrower tiers above.
- External HTTP integration tests use WireMock through the production client, as required by
  [ADR 0004](../adr/0004-spring-infrastructure-and-integration-tests.md).
- The packaged-JAR smoke test and scheduling test remain integration tests because their behavior
  is Spring startup and scheduling.
- Backend Spring and repository tests require Docker for Testcontainers PostgreSQL.

## Focused commands

- Backend test class: `./mvnw -Dtest=ApiV1ControllerTest test`
- Integration test class: `./mvnw -Dit.test=PublicationIT failsafe:integration-test`
- Frontend checks: `cd frontend && npm run check`
- Full suite: `./mvnw verify`

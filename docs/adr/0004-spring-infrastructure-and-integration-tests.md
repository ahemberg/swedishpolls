# Let Spring manage infrastructure and test the production wiring

Spring Boot manages application orchestration, database infrastructure, and outbound HTTP clients through constructor injection. Numerical calculations and immutable model values remain plain Java with plain JUnit tests. This keeps calculation tests focused while application integration tests exercise the wiring used in production.

## HTTP clients and integration tests

- Define outbound HTTP clients with `@HttpExchange` interfaces, registered through `@ImportHttpServices` and backed by Boot's configured `RestClient`.
- Use `@SpringBootTest` for application integration tests, with PostgreSQL provided through Spring Boot's Testcontainers integration and the actual Spring-managed application components.
- Test external HTTP integrations by sending real HTTP requests through the production client to WireMock. Do not mock HTTP client interfaces or transport calls, and do not call live external services. WireMock supplies controlled responses and verifies requests, including conditional headers and failure cases.
- Keep direct migration tests for schema upgrades and the packaged-JAR smoke test for startup and bundled assets.
- Control scheduling in routine integration tests so background runs cannot interfere. Verify scheduling separately.

## Consequences

Convert the existing ingestion integration tests to use the Spring-managed component and WireMock, preserving conditional request, response validation, rollback, and locking coverage. The existing tests construct ingestion dependencies manually, while the packaged-JAR test disables ingestion, leaving its production wiring untested.

Full Boot contexts and WireMock add test infrastructure, but cover configuration and HTTP behavior that mocked clients or manually constructed services would miss. Calculation tests do not need that infrastructure. This decision records the agreed target; the application and test migration is separate implementation work.

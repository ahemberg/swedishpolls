package se.swedishpolls;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

final class TestDatabase {
  private static final PostgreSQLContainer POSTGRESQL =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  "postgres:18.4@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636")
              .asCompatibleSubstituteFor("postgres"));

  static {
    POSTGRESQL.start();
  }

  private TestDatabase() {}

  static DriverManagerDataSource dataSource() {
    return dataSourceAt(POSTGRESQL.getJdbcUrl());
  }

  static DriverManagerDataSource dataSource(String schema) {
    var url = POSTGRESQL.getJdbcUrl();
    return dataSourceAt(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
  }

  static ProcessBuilder configure(ProcessBuilder builder, DriverManagerDataSource dataSource) {
    builder.environment().put("DATABASE_URL", dataSource.getUrl());
    builder.environment().put("DATABASE_USER", dataSource.getUsername());
    builder.environment().put("DATABASE_PASSWORD", dataSource.getPassword());
    return builder;
  }

  private static DriverManagerDataSource dataSourceAt(String url) {
    return new DriverManagerDataSource(url, POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
  }
}

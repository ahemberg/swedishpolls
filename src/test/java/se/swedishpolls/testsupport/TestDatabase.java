package se.swedishpolls.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class TestDatabase {
  @TestConfiguration(proxyBeanMethods = false)
  public static class Configuration {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return newContainer();
    }
  }

  private TestDatabase() {}

  public static DriverManagerDataSource dataSource() {
    return dataSourceAt(Shared.POSTGRESQL.getJdbcUrl());
  }

  public static DriverManagerDataSource dataSource(String schema) {
    final java.lang.String url = Shared.POSTGRESQL.getJdbcUrl();
    return dataSourceAt(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
  }

  public static ProcessBuilder configure(
      ProcessBuilder builder, DriverManagerDataSource dataSource) {
    builder.environment().put("DATABASE_URL", dataSource.getUrl());
    builder.environment().put("DATABASE_USER", dataSource.getUsername());
    builder.environment().put("DATABASE_PASSWORD", dataSource.getPassword());
    return builder;
  }

  private static DriverManagerDataSource dataSourceAt(String url) {
    return new DriverManagerDataSource(
        url, Shared.POSTGRESQL.getUsername(), Shared.POSTGRESQL.getPassword());
  }

  private static PostgreSQLContainer newContainer() {
    return new PostgreSQLContainer(
        DockerImageName.parse(
                "postgres:18.4@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636")
            .asCompatibleSubstituteFor("postgres"));
  }

  private static final class Shared {
    private static final PostgreSQLContainer POSTGRESQL = newContainer();

    static {
      POSTGRESQL.start();
    }
  }
}

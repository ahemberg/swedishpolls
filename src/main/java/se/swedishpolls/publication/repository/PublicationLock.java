package se.swedishpolls.publication.repository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * The PostgreSQL advisory lock one publication worker holds for the whole of an attempt. The lock
 * is taken and released on a single connection, so its lifetime is exactly the work it guards and a
 * second worker sees it held rather than publishing the same snapshot twice.
 */
@Component
public final class PublicationLock {
  /** Distinct from the ingest lock: a publication may run while a source check is not running. */
  public static final long ADVISORY_LOCK = 1_717_002L;

  /**
   * One session to take the lock on. The lock needs nothing else a {@link DataSource} offers, and
   * holding only this keeps the pool itself out of the repository's state.
   */
  @FunctionalInterface
  interface Session {
    Connection open() throws SQLException;
  }

  private final Session session;

  public PublicationLock(DataSource dataSource) {
    this.session = dataSource::getConnection;
  }

  /** Runs the work while holding the lock, or returns empty when another worker holds it. */
  public <T> Optional<T> whileHeld(Supplier<T> work) {
    try (final Connection connection = session.open()) {
      if (!lock(connection)) {
        return Optional.empty();
      }
      try {
        return Optional.of(work.get());
      } finally {
        unlock(connection);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("The publication lock is unavailable", e);
    }
  }

  private static boolean lock(Connection connection) throws SQLException {
    try (final Statement statement = connection.createStatement();
        final ResultSet result =
            statement.executeQuery("SELECT pg_try_advisory_lock(" + ADVISORY_LOCK + ")")) {
      return result.next() && result.getBoolean(1);
    }
  }

  private static void unlock(Connection connection) throws SQLException {
    try (final Statement statement = connection.createStatement()) {
      statement.execute("SELECT pg_advisory_unlock(" + ADVISORY_LOCK + ")");
    }
  }
}

package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Tunes the frozen development folds on the archived pre-2022 development rows. The default run
 * covers a three-fold subset; `-Dtuning.full=true` reruns every fold and rewrites
 * docs/validation/tuning.json.
 */
class DevelopmentTuningIT {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final Path RESULT = Path.of("docs", "validation", "tuning.json");

  @Test
  void resolvesFiniteParametersPerFoldAndRosterAndListsEveryGridBoundary() throws Exception {
    var schema = "tuning_" + UUID.randomUUID().toString().replace("-", "");
    var url =
        System.getenv()
            .getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/swedishpolls");
    var dataSource =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
            System.getenv().getOrDefault("DATABASE_USER", "swedishpolls"),
            System.getenv("DATABASE_PASSWORD"));
    var flyway =
        Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load();
    try {
      flyway.migrate();
      var db = JdbcClient.create(dataSource);
      var periods = new Roster(db).periods();
      var elections =
          db.sql("SELECT election_date FROM election_reference ORDER BY election_date")
              .query(LocalDate.class)
              .list();
      List<PollCsv.Poll> polls;
      try (var input = getClass().getResourceAsStream("/polls/audit.csv")) {
        polls = PollCsv.parse(input.readAllBytes());
      }
      var registered = DevelopmentTuning.protocol(PROTOCOL);
      boolean full = Boolean.getBoolean("tuning.full");
      var folds =
          full
              ? registered.folds()
              : List.of(
                  registered.folds().getFirst(),
                  registered.folds().get(registered.folds().size() / 2),
                  registered.folds().getLast());
      var protocol = new DevelopmentTuning.Protocol(registered.version(), folds, registered.grid());

      var tuning = DevelopmentTuning.tuneAll(periods, polls, elections, protocol);

      assertEquals(
          folds.size() * periods.size(), tuning.resolved().size() + tuning.unresolved().size());
      // Only the candidate FI period, which starts in 2014, can lack training observations in an
      // early fold.
      assertTrue(
          tuning.unresolved().stream()
              .allMatch(
                  unresolved ->
                      unresolved.periodId().equals("fi_candidate_2014_2018")
                          && unresolved.reason().contains("no eligible training observation")));
      // The multiplier reaches its grid floor on the development data, so the aggregate gate must
      // block.
      assertTrue(tuning.resolved().stream().anyMatch(DevelopmentTuning.Resolved::onGridBoundary));
      assertTrue(tuning.gate().blocked());
      assertTrue(
          tuning.gate().reasons().stream().anyMatch(reason -> reason.contains("grid boundary")),
          () -> tuning.gate().reasons().toString());
      for (var resolved : tuning.resolved()) {
        assertTrue(Double.isFinite(resolved.logLikelihood()), resolved::toString);
        assertTrue(
            resolved.observations() > 0 && resolved.observations() <= resolved.trainingPolls());
        assertEquals(resolved.trainingPolls(), resolved.observations() + resolved.exclusions());
        // One excluded poll can carry several reasons, so the counts sum to at least the poll
        // count.
        assertTrue(
            resolved.exclusionReasons().values().stream().mapToInt(Integer::intValue).sum()
                >= resolved.exclusions());
        assertEquals(resolved.exclusions() == 0, resolved.exclusionReasons().isEmpty());
        assertTrue(
            registered.grid().walkVariances().contains(resolved.parameters().walkVariance()));
        assertTrue(registered.grid().houseScales().contains(resolved.parameters().houseScale()));
        assertTrue(
            registered
                .grid()
                .covarianceMultipliers()
                .contains(resolved.parameters().covarianceMultiplier()));
        // Training never reaches past its own cutoff, so a later fold never trains on less data.
        assertTrue(resolved.fold().cutoff().isBefore(LocalDate.of(2022, 1, 1)));
      }
      var eight =
          tuning.resolved().stream().filter(r -> r.periodId().equals("eight_party_2010")).toList();
      assertEquals(folds.size(), eight.size());
      assertTrue(eight.getLast().trainingPolls() > eight.getFirst().trainingPolls());
      // Seeded reproduction of the same fold must resolve the identical point and likelihood.
      var repeated =
          DevelopmentTuning.tune(
              periods.getFirst(), polls, elections, folds.getFirst(), registered.grid());
      assertEquals(eight.getFirst(), repeated);

      if (full)
        Files.writeString(RESULT, DevelopmentTuning.report(tuning) + "\n", StandardCharsets.UTF_8);
      else
        assertTrue(
            Files.exists(RESULT),
            "Committed tuning evidence is missing; rerun with -Dtuning.full=true");
    } finally {
      flyway.clean();
    }
  }
}

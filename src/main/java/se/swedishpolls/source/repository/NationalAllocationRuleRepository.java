package se.swedishpolls.source.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import se.swedishpolls.model.NationalAllocationRule;

/** JDBC lookup of election-era national allocation rules. */
@Component
public class NationalAllocationRuleRepository {
  private static final String SELECT =
      """
      SELECT election_year, seats, national_threshold_percent, threshold_inclusive,
             first_divisor, subsequent_divisor_formula, tie_order, other_receives_seats,
             constituency_exceptions_included, official_tie_rule, source_url
      FROM national_allocation_rule
      """;

  private final JdbcClient db;

  public NationalAllocationRuleRepository(JdbcClient db) {
    this.db = db;
  }

  /** The stored rule of one election. */
  public NationalAllocationRule rule(int electionYear) {
    return db.sql(SELECT + " WHERE election_year = :year")
        .param("year", electionYear)
        .query(NationalAllocationRuleRepository::rule)
        .optional()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No configured national allocation rule for " + electionYear));
  }

  /** Every stored rule, oldest first. */
  public List<NationalAllocationRule> all() {
    return db.sql(SELECT + " ORDER BY election_year")
        .query(NationalAllocationRuleRepository::rule)
        .list();
  }

  /** The next configured election year, or the latest one when no later rule exists. */
  public int approximatedElection(int year) {
    return db.sql(
            "SELECT election_year FROM national_allocation_rule WHERE election_year >= ?"
                + " ORDER BY election_year LIMIT 1")
        .param(year)
        .query(Integer.class)
        .optional()
        .orElseGet(
            () ->
                db.sql("SELECT max(election_year) FROM national_allocation_rule")
                    .query(Integer.class)
                    .single());
  }

  private static NationalAllocationRule rule(java.sql.ResultSet rs, int row)
      throws java.sql.SQLException {
    return new NationalAllocationRule(
        rs.getInt("election_year"),
        rs.getInt("seats"),
        rs.getBigDecimal("national_threshold_percent").doubleValue(),
        rs.getBoolean("threshold_inclusive"),
        rs.getBigDecimal("first_divisor").doubleValue(),
        rs.getString("subsequent_divisor_formula"),
        List.of((String[]) rs.getArray("tie_order").getArray()),
        rs.getBoolean("other_receives_seats"),
        rs.getBoolean("constituency_exceptions_included"),
        rs.getString("official_tie_rule"),
        rs.getString("source_url"));
  }
}

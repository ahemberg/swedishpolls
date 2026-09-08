package se.swedishpolls;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Groups an eligible poll into the modeled components of one coverage period. */
@Component
public class Roster {
    public record CoveragePeriod(String id, LocalDate effectiveFrom, LocalDate effectiveTo, List<String> roster,
                                 boolean individualFi, boolean supportValidated, String decisionUrl) {
        public CoveragePeriod { roster = List.copyOf(roster); }
        public boolean covers(PollCsv.Poll poll) {
            return poll.collectionFrom() != null && poll.collectionTo() != null
                    && !poll.collectionFrom().isBefore(effectiveFrom)
                    && (effectiveTo == null || !poll.collectionTo().isAfter(effectiveTo));
        }
    }

    /** Components are empty when a reason excludes the poll; the archived source observation is unaffected. */
    public record Composition(String periodId, Map<String, BigDecimal> components, List<String> exclusionReasons) {
        public Composition {
            components = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(components));
            exclusionReasons = List.copyOf(exclusionReasons);
        }
        public boolean complete() { return exclusionReasons.isEmpty(); }
        /** Support outside the fixed eight, including FI in every period. */
        public BigDecimal comparableRemainder() {
            return components.entrySet().stream().filter(component -> !PollCsv.PARTIES.contains(component.getKey()))
                    .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private final JdbcClient db;

    public Roster(JdbcClient db) { this.db = db; }

    public List<CoveragePeriod> periods() {
        return db.sql("""
                SELECT id, effective_from, effective_to, roster, individual_fi, support_validated, decision_url
                FROM coverage_period ORDER BY effective_from, id
                """).query((rs, row) -> new CoveragePeriod(rs.getString("id"),
                        rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class),
                        List.of((String[]) rs.getArray("roster").getArray()), rs.getBoolean("individual_fi"),
                        rs.getBoolean("support_validated"), rs.getString("decision_url"))).list();
    }

    /** A candidate segment never replaces the validated roster until the estimator ticket validates its fits. */
    public CoveragePeriod supportedPeriod(PollCsv.Poll poll) {
        return periods().stream().filter(CoveragePeriod::supportValidated).filter(period -> period.covers(poll)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No validated coverage period covers " + poll.collectionFrom()));
    }

    public static Composition compose(CoveragePeriod period, PollCsv.Poll poll) {
        var reasons = new ArrayList<>(poll.exclusionReasons());
        if (!period.covers(poll)) reasons.add("outside_coverage_period:" + period.id());
        var fi = poll.shares().get("FI");
        if (period.individualFi() && fi == null) reasons.add("missing_share:FI");
        var components = new LinkedHashMap<String, BigDecimal>();
        if (reasons.isEmpty()) {
            for (var party : PollCsv.PARTIES) components.put(party, poll.shares().get(party));
            if (!period.individualFi()) {
                // The eight-party remainder already contains FI; adding FI again would double-count it.
                components.put("OTHER", poll.remainder());
            } else {
                var residual = poll.remainder().subtract(fi);
                if (residual.signum() < 0) reasons.add("negative_residual");
                components.put("FI", fi);
                components.put("RESIDUAL", residual);
            }
        }
        if (!reasons.isEmpty()) components.clear();
        return new Composition(period.id(), components, reasons);
    }
}

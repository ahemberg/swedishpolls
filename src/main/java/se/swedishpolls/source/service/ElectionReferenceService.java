package se.swedishpolls.source.service;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import se.swedishpolls.model.ElectionReference;
import se.swedishpolls.source.repository.ElectionReferenceRepository;

/** Provides stored official election outcomes to publication use cases. */
@Component
public class ElectionReferenceService {
  /**
   * The last election whose result may be stored. Issue #247 moves it once the prospective
   * holdout's scoring is registered.
   */
  static final LocalDate LATEST_PERMITTED_ELECTION = LocalDate.of(2026, 9, 13);

  private final ElectionReferenceRepository repository;

  public ElectionReferenceService(ElectionReferenceRepository repository) {
    this.repository = repository;
  }

  public List<ElectionReference> all() {
    return repository.all();
  }

  /** Refuses a stored election result later than the embargo allows. */
  public void requireEmbargo() {
    final LocalDate latest = repository.latestElectionDate().orElse(LATEST_PERMITTED_ELECTION);
    if (latest.isAfter(LATEST_PERMITTED_ELECTION)) {
      throw new IllegalStateException(
          "Election reference "
              + latest
              + " is later than "
              + LATEST_PERMITTED_ELECTION
              + ", the last election whose result may be stored. Later results are embargoed to"
              + " preserve future prospective holdouts. Issue"
              + " https://github.com/ahemberg/swedishpolls/issues/247 lifted the 2026 embargo only"
              + " after the scoring rule in docs/validation/2026-prospective-holdout was"
              + " registered. Reading a later result early destroys its holdout permanently."
              + " Remove the reference.");
    }
  }
}

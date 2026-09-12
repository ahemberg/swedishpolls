package se.swedishpolls.source.service;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import se.swedishpolls.model.NationalAllocationRule;
import se.swedishpolls.source.repository.NationalAllocationRuleRepository;

/** Provides election-era allocation rules to publication use cases. */
@Component
public class NationalAllocationRuleService {
  private final NationalAllocationRuleRepository repository;

  public NationalAllocationRuleService(NationalAllocationRuleRepository repository) {
    this.repository = repository;
  }

  public NationalAllocationRule rule(int electionYear) {
    return repository.rule(electionYear);
  }

  public List<NationalAllocationRule> all() {
    return repository.all();
  }

  public NationalAllocationRule forDate(LocalDate lastFieldworkDate) {
    return rule(repository.approximatedElection(lastFieldworkDate.getYear()));
  }
}

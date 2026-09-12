package se.swedishpolls.source.service;

import java.util.List;
import org.springframework.stereotype.Component;
import se.swedishpolls.model.ElectionReference;
import se.swedishpolls.source.repository.ElectionReferenceRepository;

/** Provides stored official election outcomes to publication use cases. */
@Component
public class ElectionReferenceService {
  private final ElectionReferenceRepository repository;

  public ElectionReferenceService(ElectionReferenceRepository repository) {
    this.repository = repository;
  }

  public List<ElectionReference> all() {
    return repository.all();
  }
}

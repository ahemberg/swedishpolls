package se.swedishpolls.source.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import se.swedishpolls.source.repository.ElectionReferenceRepository;

class ElectionReferenceServiceTest {
  private final ElectionReferenceRepository repository = mock(ElectionReferenceRepository.class);
  private final ElectionReferenceService elections = new ElectionReferenceService(repository);

  @Test
  void theEmbargoAdmitsElectionsUpToTheLatestPermittedOne() {
    when(repository.latestElectionDate())
        .thenReturn(Optional.of(ElectionReferenceService.LATEST_PERMITTED_ELECTION));
    assertDoesNotThrow(elections::requireEmbargo);
  }

  @Test
  void aLaterElectionIsRefusedWithAMessageNamingTheHoldout() {
    final LocalDate later = ElectionReferenceService.LATEST_PERMITTED_ELECTION.plusDays(1);
    when(repository.latestElectionDate()).thenReturn(Optional.of(later));

    final IllegalStateException refused =
        assertThrows(IllegalStateException.class, elections::requireEmbargo);
    final String message = refused.getMessage();
    assertTrue(message.contains(later.toString()), message);
    assertTrue(message.contains("docs/validation/2026-prospective-holdout"), message);
    assertTrue(message.contains("permanently"), message);
    assertTrue(message.contains("https://github.com/ahemberg/swedishpolls/issues/247"), message);
  }
}

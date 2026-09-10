package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevelopmentGatesTest {
  @Test
  void failedConditionalCoverageNamesTheGridMixtureAndStopsPublication(@TempDir Path directory)
      throws Exception {
    var protocol = write(directory, "protocol.json", protocol());
    var coverage = write(directory, "coverage.json", coverage());
    var uncertainty = write(directory, "uncertainty.json", uncertainty());
    var diagnostics = write(directory, "diagnostics.json", diagnostics(0.89));
    var report =
        DevelopmentGates.evaluate(
            protocol, coverage, uncertainty, diagnostics, drift(), architectures(), resources());
    assertEquals(DevelopmentGates.FALLBACK_REQUIRED, report.uncertaintyFallback());
    assertTrue(report.gate().blocked());
    assertThrows(IllegalStateException.class, () -> DevelopmentGates.requirePassed(report));

    Files.writeString(diagnostics, diagnostics(0.95));
    var passed =
        DevelopmentGates.evaluate(
            protocol, coverage, uncertainty, diagnostics, drift(), architectures(), resources());
    assertEquals(DevelopmentGates.FALLBACK_NOT_REQUIRED, passed.uncertaintyFallback());
    assertFalse(passed.gate().blocked());
    DevelopmentGates.requirePassed(passed);
  }

  private static Path write(Path directory, String name, String content) throws Exception {
    return Files.writeString(directory.resolve(name), content);
  }

  private static String protocol() {
    return """
        {
          "version": "test",
          "tolerance_status": "frozen-development",
          "resolved_tolerances": {
            "seeded_reproduction_points": 0.01,
            "historical_interval_endpoint_precision_points": 0.2,
            "coverage_boundary_stability_points": 0.5,
            "snapshot_drift_points": 0.5
          },
          "resource_measurement": {"full_estimator_target_millis": 10},
          "predictive_coverage95": [0.9, 0.98],
          "predictive_coverage50": [0.4, 0.6]
        }
        """;
  }

  private static String coverage() {
    return """
        {
          "periods": [{
            "stability": [{
              "comparedDays": 1,
              "maxShiftPoints": {"S": 0.1}
            }]
          }]
        }
        """;
  }

  private static String uncertainty() {
    return """
        {
          "proposedTolerances": [
            {
              "name": "test:seeded_reproduction",
              "units": "points",
              "maxObservedError": 0.0,
              "cases": 1,
              "seeds": [1],
              "rationale": "same seed"
            },
            {
              "name": "test:interval_endpoint_precision",
              "units": "points",
              "maxObservedError": 0.1,
              "cases": 1,
              "seeds": [1, 2],
              "rationale": "repeated seeds"
            }
          ]
        }
        """;
  }

  private static String diagnostics(double coverage95) {
    return """
        {
          "gate": {"blocked": false, "reasons": []},
          "misfit": [{
            "candidate": "midpoint",
            "scope": "all",
            "coverage95": %s,
            "coverage50": 0.5
          }]
        }
        """
        .formatted(coverage95);
  }

  private static DevelopmentGates.Drift drift() {
    return new DevelopmentGates.Drift(
        "input",
        List.of(
            new DevelopmentGates.Perturbation(
                "correction", 1, "Test", 1, 0.1, LocalDate.of(2020, 1, 1), "S")));
  }

  private static DevelopmentGates.CrossArchitecture architectures() {
    return new DevelopmentGates.CrossArchitecture(
        1,
        0,
        0,
        List.of(
            new DevelopmentGates.ArchitectureRun("amd64", "25", "a"),
            new DevelopmentGates.ArchitectureRun("arm64", "25", "a")));
  }

  private static DevelopmentGates.Resources resources() {
    return new DevelopmentGates.Resources(1, 1, 10, 1, 1, 1, "amd64", "25", "vm", "os");
  }
}

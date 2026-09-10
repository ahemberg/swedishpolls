package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevelopmentGatesTest {
  @Test
  void failedConditionalCoverageNamesTheGridMixtureAndStopsPublication(@TempDir Path directory)
      throws Exception {
    var protocol = write(directory, "protocol.json", protocol());
    var coverage = write(directory, "coverage.json", coverage());
    var uncertainty = write(directory, "uncertainty.json", uncertainty(0));
    var diagnostics = write(directory, "diagnostics.json", diagnostics(0.89));
    var architectures = write(directory, "cross-architecture.json", architectures());
    var report =
        DevelopmentGates.evaluate(
            protocol,
            coverage,
            uncertainty,
            diagnostics,
            architectures,
            drift(),
            precision(),
            resources());
    assertEquals(DevelopmentGates.FALLBACK_REQUIRED, report.uncertaintyFallback());
    assertTrue(report.gate().blocked());
    assertThrows(IllegalStateException.class, () -> DevelopmentGates.requirePassed(report));

    Files.writeString(diagnostics, diagnostics(0.95));
    var passed =
        DevelopmentGates.evaluate(
            protocol,
            coverage,
            uncertainty,
            diagnostics,
            architectures,
            drift(),
            precision(),
            resources());
    assertEquals(DevelopmentGates.FALLBACK_NOT_REQUIRED, passed.uncertaintyFallback());
    assertFalse(passed.gate().blocked());
    DevelopmentGates.requirePassed(passed);

    Files.writeString(uncertainty, uncertainty(1e-15));
    var inexact =
        DevelopmentGates.evaluate(
            protocol,
            coverage,
            uncertainty,
            diagnostics,
            architectures,
            drift(),
            precision(),
            resources());
    assertTrue(inexact.gate().blocked());
    assertTrue(
        inexact.gate().reasons().stream()
            .anyMatch(reason -> reason.contains("seeded_reproduction observed")));
  }

  @Test
  void probabilityBoundariesAreInclusiveAndSeatAllocationCloses() {
    assertEquals(
        2.0 / 3,
        DevelopmentGates.probabilityAtOrAbove(
            new double[] {Math.nextDown(4.0), 4.0, Math.nextUp(4.0)}, 4.0));
    assertEquals(2.0 / 3, DevelopmentGates.probabilityAtOrAbove(new double[] {174, 175, 176}, 175));
    assertEquals(0.005, DevelopmentGates.monteCarloStandardError(10_000));
    var rules = new DevelopmentGates.AllocationRules(349, 4, 1.2, 175, List.of("A", "B", "C"));
    var seats = DevelopmentGates.allocate(Map.of("A", 50.0, "B", 46.0, "C", 4.0), rules);
    assertEquals(349, seats.values().stream().mapToInt(Integer::intValue).sum());
    assertTrue(seats.containsKey("C"));
  }

  private static Path write(Path directory, String name, String content) throws Exception {
    return Files.writeString(directory.resolve(name), content);
  }

  private static String protocol() {
    return """
        {
          "version": "test",
          "seed": 1,
          "final_draws": 10000,
          "uncertainty": {"interval_levels": [0.5, 0.95], "precision_repeats": 2},
          "tolerance_status": "frozen-development",
          "resolved_tolerances": {
            "seeded_reproduction_points": 0,
            "cross_architecture_reproduction_points": 0.01,
            "historical_interval_endpoint_precision_points": 0.2,
            "coverage_boundary_stability_points": 0.5,
            "probability_monte_carlo_standard_error": 0.005,
            "threshold_probability_precision": 0.03,
            "majority_probability_precision": 0.03,
            "snapshot_drift_points": 0.5
          },
          "resolved_diagnostic_gates": {
            "minimum_subgroup_cases": 100,
            "maximum_absolute_mean_standardized_residual": 0.5,
            "minimum_root_mean_square_standardized_residual": 0.5,
            "maximum_root_mean_square_standardized_residual": 1.5,
            "maximum_absolute_residual_autocorrelation": 0.45,
            "autocorrelation_explanation": "test evidence"
          },
          "probability_precision": {
            "seats": 349,
            "threshold_percent": 4,
            "first_divisor": 1.2,
            "majority_seats": 175,
            "majority_coalition": ["A"],
            "tie_order": ["A", "B"]
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
          "protocolVersion": "test",
          "gate": {"blocked": false, "reasons": []},
          "periods": [{
            "stability": [{
              "comparedDays": 1,
              "maxShiftPoints": {"S": 0.1}
            }]
          }]
        }
        """;
  }

  private static String uncertainty(double reproductionError) {
    return """
        {
          "protocolVersion": "test",
          "gate": {"blocked": false, "reasons": []},
          "proposedTolerances": [
            {
              "name": "test:seeded_reproduction",
              "units": "points",
              "maxObservedError": %s,
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
        """
        .formatted(reproductionError);
  }

  private static String diagnostics(double coverage95) {
    return """
        {
          "protocolVersion": "test",
          "gate": {"blocked": false, "reasons": []},
          "misfit": [
            {"periodId":"period","candidate":"midpoint","scope":"all","name":"all","coverage95":%s,"coverage50":0.5},
            {"periodId":"period","candidate":"midpoint","scope":"party","name":"A","cases":100,"coverage95":0.95,"coverage50":0.5,"meanStandardizedResidual":0,"rootMeanSquareStandardizedResidual":1},
            {"periodId":"period","candidate":"midpoint","scope":"institute","name":"I","cases":100,"coverage95":0.95,"coverage50":0.5,"meanStandardizedResidual":0,"rootMeanSquareStandardizedResidual":1},
            {"periodId":"period","candidate":"midpoint","scope":"fieldwork_days","name":"1-7","cases":100,"coverage95":0.95,"coverage50":0.5,"meanStandardizedResidual":0,"rootMeanSquareStandardizedResidual":1}
          ],
          "autocorrelation": [
            {"periodId":"period","lag":1,"correlation":0.1},
            {"periodId":"period","lag":2,"correlation":0.1},
            {"periodId":"period","lag":3,"correlation":0.1}
          ]
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

  private static String architectures() {
    return """
        {
          "comparedValues": 1,
          "differingValues": 0,
          "maxAbsoluteDifferencePoints": 0,
          "runs": [
            {"architecture": "amd64", "javaRuntime": "25", "drawsSha256": "a"},
            {"architecture": "arm64", "javaRuntime": "25", "drawsSha256": "a"}
          ]
        }
        """;
  }

  private static DevelopmentGates.ProbabilityPrecision precision() {
    var rules = new DevelopmentGates.AllocationRules(349, 4, 1.2, 175, List.of("A", "B"));
    return new DevelopmentGates.ProbabilityPrecision(
        rules,
        List.of("A"),
        10_000,
        List.of(
            new DevelopmentGates.ProbabilityRun(1, ordered(0.5, 0.5), 0.5),
            new DevelopmentGates.ProbabilityRun(2, ordered(0.51, 0.49), 0.51)));
  }

  private static Map<String, Double> ordered(double a, double b) {
    var result = new LinkedHashMap<String, Double>();
    result.put("A", a);
    result.put("B", b);
    return result;
  }

  private static DevelopmentGates.Resources resources() {
    return new DevelopmentGates.Resources(1, 1, 10, 1, 1, 1, "amd64", "25", "vm", "os");
  }
}

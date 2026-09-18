package se.swedishpolls.estimation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.ejml.simple.SimpleMatrix;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reproduction step of the accepted {@code synthetic-recovery-v1} protocol: every completed
 * predictive array is regenerated from the retained prediction and its hash is compared without
 * replacement, and the intervals, coverage indicators and aggregate results are recomputed from the
 * retained inputs.
 *
 * <p>Only the hash, seed, dimensions and generating prediction of an array are retained, so this is
 * what makes a completed predictive output checkable at all. A hash that is missing, short or
 * different is a finding rather than something to overwrite: nothing here writes into the run it
 * reads. The recomputed arrays run in the pinned environment on the same architecture; cross
 * architecture reproduction is outside this protocol.
 */
final class SyntheticReproduction {
  private SyntheticReproduction() {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String SCORED = "scored";

  private static final String NOT_RUN = "not_run";

  /** One convention and stage, recomputed from the evidence its datasets retained. */
  record Stage(
      String convention,
      String stage,
      int datasets,
      int arrays,
      List<SyntheticCoverage.Cell> cells,
      String verdict,
      List<String> findings) {
    Stage {
      cells = List.copyOf(cells);
      findings = List.copyOf(findings);
    }

    @Override
    public List<SyntheticCoverage.Cell> cells() {
      return List.copyOf(cells);
    }

    @Override
    public List<String> findings() {
      return List.copyOf(findings);
    }
  }

  /** What the whole reproduction found, and whether it confirmed the completed output. */
  record Outcome(
      List<Stage> stages, int datasets, int arrays, List<String> findings, boolean interrupted) {
    Outcome {
      stages = List.copyOf(stages);
      findings = List.copyOf(findings);
    }

    @Override
    public List<Stage> stages() {
      return List.copyOf(stages);
    }

    @Override
    public List<String> findings() {
      return List.copyOf(findings);
    }

    boolean reproduced() {
      return findings.isEmpty();
    }
  }

  /**
   * Reproduces one completed run under the watchdog that can stop scheduling further datasets. The
   * report says what each stage planned and completed, so a stage that stopped or was interrupted
   * is reported incomplete instead of silently reproducing the part that exists.
   */
  static Outcome reproduce(
      Path directory,
      JsonNode report,
      double intervalTolerance,
      double coverageTolerance,
      Deadline deadline) {
    final List<Stage> stages = new ArrayList<>();
    final List<String> findings = new ArrayList<>();
    int datasets = 0;
    int arrays = 0;
    boolean interrupted = false;
    for (String field : List.of("methods", "estimatedStages"))
      for (JsonNode method : SyntheticRegistration.required(report, field)) {
        final String convention = SyntheticRegistration.required(method, "convention").asString();
        final String stageName = SyntheticRegistration.required(method, "stage").asString();
        final String status = SyntheticRegistration.required(method, "status").asString();
        if (!status.equals("completed")) {
          // A stage the protocol left explicitly not run has no predictive output to reproduce.
          // A stage that stopped or was interrupted does, and its absence is a finding.
          final boolean expected = status.equals(NOT_RUN);
          if (!expected)
            findings.add(
                stageName
                    + "/"
                    + convention
                    + ": the stage is "
                    + status
                    + ", so its predictive output is not complete");
          stages.add(
              new Stage(
                  convention,
                  stageName,
                  0,
                  0,
                  List.of(),
                  expected ? NOT_RUN : SyntheticCoverage.INCOMPLETE,
                  expected ? List.of() : List.of("stage status " + status)));
          continue;
        }
        if (deadline.expired()) {
          interrupted = true;
          findings.add(
              stageName
                  + "/"
                  + convention
                  + ": reproduction stopped at the 24-hour cap before this stage");
          continue;
        }
        final Stage reproduced =
            stage(
                directory,
                convention,
                stageName,
                method,
                intervalTolerance,
                coverageTolerance,
                deadline);
        stages.add(reproduced);
        datasets += reproduced.datasets();
        arrays += reproduced.arrays();
        findings.addAll(
            reproduced.findings().stream()
                .map(finding -> stageName + "/" + convention + ": " + finding)
                .toList());
      }
    return new Outcome(stages, datasets, arrays, findings, interrupted);
  }

  /** The watchdog seam: the cap is enforced between datasets rather than inside one. */
  interface Deadline {
    boolean expired();
  }

  private static Stage stage(
      Path directory,
      String convention,
      String stageName,
      JsonNode method,
      double intervalTolerance,
      double coverageTolerance,
      Deadline deadline) {
    final List<String> findings = new ArrayList<>();
    final Path evidence =
        directory.resolve(SyntheticRegistration.required(method, "evidence").asString());
    final int planned = SyntheticRegistration.required(method, "completedDatasets").intValue();
    final List<Path> files = datasets(evidence);
    if (files.size() != planned)
      findings.add(
          "retained "
              + files.size()
              + " datasets where the report completed "
              + planned
              + "; summaries are missing");
    final int levels = SyntheticCoverage.LEVELS.size();
    final List<String> components = SyntheticRecovery.components();
    final double[][][] fractions = new double[components.size()][levels][files.size()];
    final long[][] covered = new long[components.size()][levels];
    final long[][] scored = new long[components.size()][levels];
    int arrays = 0;
    int reproducedDatasets = 0;
    for (int index = 0; index < files.size(); index++) {
      if (deadline.expired()) {
        findings.add("reproduction stopped at the 24-hour cap after " + index + " datasets");
        break;
      }
      final Path file = files.get(index);
      final JsonNode document = SyntheticRegistration.read(file);
      final String status = SyntheticRegistration.required(document, "status").asString();
      if (!status.equals(SCORED)) {
        findings.add(file.getFileName() + " retains " + status + " rather than scored evidence");
        continue;
      }
      final long[][] datasetCovered = new long[components.size()][levels];
      final int polls = dataset(file, document, intervalTolerance, datasetCovered, findings);
      arrays += polls;
      reproducedDatasets++;
      for (int component = 0; component < components.size(); component++)
        for (int level = 0; level < levels; level++) {
          covered[component][level] += datasetCovered[component][level];
          scored[component][level] += polls;
          fractions[component][level][index] = datasetCovered[component][level] / (double) polls;
        }
    }

    final List<SyntheticCoverage.Cell> cells = new ArrayList<>();
    if (reproducedDatasets == files.size() && files.size() >= 2) {
      for (int component = 0; component < components.size(); component++)
        for (int level = 0; level < levels; level++)
          cells.add(
              SyntheticCoverage.cell(
                  components.get(component),
                  SyntheticCoverage.LEVELS.get(level),
                  fractions[component][level],
                  covered[component][level],
                  scored[component][level],
                  true));
      compareCells(
          cells, SyntheticRegistration.required(method, "cells"), coverageTolerance, findings);
    } else findings.add("the aggregate results cannot be recomputed from incomplete evidence");
    return new Stage(
        convention,
        stageName,
        reproducedDatasets,
        arrays,
        cells,
        cells.isEmpty() ? SyntheticCoverage.INCOMPLETE : SyntheticCoverage.stageVerdict(cells),
        findings);
  }

  /**
   * One dataset: each retained prediction is drawn from again and its array hash compared exactly,
   * and the intervals and coverage indicators are recomputed from the same draws.
   */
  static int dataset(
      Path file, JsonNode document, double tolerance, long[][] covered, List<String> findings) {
    final String prefix = SyntheticRegistration.required(document, "streamPrefix").asString();
    final Roster.CoveragePeriod period = SyntheticRecovery.period(prefix);
    final ModelValues covariance =
        matrix(SyntheticRegistration.required(document, "observationCovariance"));
    final JsonNode polls = SyntheticRegistration.required(document, "polls");
    final List<PollObservations.Observation> observations = new ArrayList<>();
    for (JsonNode poll : polls)
      observations.add(
          SyntheticRecovery.observation(
              SyntheticRecovery.poll(
                  SyntheticRegistration.required(poll, "rowNumber").intValue(),
                  SyntheticRegistration.required(poll, "institute").asString(),
                  LocalDate.parse(SyntheticRegistration.required(poll, "fieldworkFrom").asString()),
                  LocalDate.parse(SyntheticRegistration.required(poll, "fieldworkTo").asString()),
                  SyntheticRegistration.required(poll, "sampleSize").decimalValue()),
              period,
              column(SyntheticRegistration.required(poll, "observedIlr")),
              covariance));
    final PollObservations.Batch batch = PollObservations.explicit(period, observations);
    final int components = batch.components().size();
    for (int index = 0; index < polls.size(); index++) {
      final JsonNode poll = polls.get(index);
      final String retainedHash = SyntheticRegistration.required(poll, "drawsSha256").asString();
      if (!retainedHash.matches("[0-9a-f]{64}")) {
        findings.add(
            file.getFileName()
                + " row "
                + SyntheticRegistration.required(poll, "rowNumber").intValue()
                + " retains no predictive draw hash");
        continue;
      }
      final PredictiveScoring.Summary summary =
          PredictiveScoring.score(
              batch,
              observations.get(index),
              column(SyntheticRegistration.required(poll, "predictiveMean")),
              matrix(SyntheticRegistration.required(poll, "predictiveCovariance")),
              SyntheticRegistration.required(poll, "draws").intValue(),
              SyntheticRegistration.required(poll, "masterSeed").longValue(),
              SyntheticRegistration.required(poll, "stream").asString(),
              (ignoredStream, ignoredDraws) -> {});
      // The retained hash is compared, never replaced: a regenerated array that hashes differently
      // is reported as it stands. The comparison is the constant-time one the security analysis
      // requires of a digest comparison.
      if (!MessageDigest.isEqual(
          retainedHash.getBytes(StandardCharsets.UTF_8),
          summary.drawsSha256().getBytes(StandardCharsets.UTF_8)))
        findings.add(
            file.getFileName()
                + " row "
                + SyntheticRegistration.required(poll, "rowNumber").intValue()
                + " regenerated a different predictive draw array");
      if (summary.seed() != SyntheticRegistration.required(poll, "seed").longValue())
        findings.add(file.getFileName() + " row " + index + " retains another derived seed");
      final JsonNode retained = SyntheticRegistration.required(poll, "components");
      if (retained.size() != components) {
        findings.add(file.getFileName() + " row " + index + " retains incomplete summaries");
        continue;
      }
      for (int component = 0; component < components; component++) {
        final PredictiveScoring.Component recomputed = summary.components().get(component);
        final JsonNode stored = retained.get(component);
        for (String field : List.of("mean", "variance", "lower95", "upper95", "lower50", "upper50"))
          if (Math.abs(
                  SyntheticRegistration.required(stored, field).doubleValue()
                      - value(recomputed, field))
              > tolerance)
            findings.add(
                file.getFileName()
                    + " row "
                    + index
                    + " component "
                    + recomputed.component()
                    + " recomputed a different "
                    + field);
        for (int level = 0; level < SyntheticCoverage.LEVELS.size(); level++) {
          final int nominal = SyntheticCoverage.LEVELS.get(level);
          final boolean indicator = nominal == 95 ? recomputed.covered95() : recomputed.covered50();
          if (indicator
              != SyntheticRegistration.required(stored, "covered" + nominal).booleanValue())
            findings.add(
                file.getFileName()
                    + " row "
                    + index
                    + " component "
                    + recomputed.component()
                    + " recomputed a different "
                    + nominal
                    + "% coverage indicator");
          if (indicator) covered[component][level]++;
        }
      }
    }
    return polls.size();
  }

  private static double value(PredictiveScoring.Component component, String field) {
    return switch (field) {
      case "mean" -> component.mean();
      case "variance" -> component.variance();
      case "lower95" -> component.lower95();
      case "upper95" -> component.upper95();
      case "lower50" -> component.lower50();
      default -> component.upper50();
    };
  }

  /** The recomputed cells against the ones the report published. */
  private static void compareCells(
      List<SyntheticCoverage.Cell> recomputed,
      JsonNode published,
      double tolerance,
      List<String> findings) {
    if (published.size() != recomputed.size()) {
      findings.add("the report published " + published.size() + " cells rather than a full stage");
      return;
    }
    for (int index = 0; index < recomputed.size(); index++) {
      final SyntheticCoverage.Cell cell = recomputed.get(index);
      final JsonNode reported = published.get(index);
      final String name = cell.component() + "/" + cell.level();
      if (!SyntheticRegistration.required(reported, "component").asString().equals(cell.component())
          || SyntheticRegistration.required(reported, "level").intValue() != cell.level()) {
        findings.add(
            "cell " + index + " recomputed as " + name + " against another published cell");
        continue;
      }
      if (Math.abs(
              SyntheticRegistration.required(reported, "coverage").doubleValue() - cell.coverage())
          > tolerance) findings.add("cell " + name + " recomputed a different coverage");
      if (Math.abs(
              SyntheticRegistration.required(reported, "standardError").doubleValue()
                  - cell.standardError())
          > tolerance) findings.add("cell " + name + " recomputed a different standard error");
      for (String field : List.of("lower", "upper"))
        if (Math.abs(
                SyntheticRegistration.required(reported, field).doubleValue()
                    - (field.equals("lower") ? cell.lower() : cell.upper()))
            > tolerance)
          findings.add("cell " + name + " recomputed a different confidence " + field);
      if (!SyntheticRegistration.required(reported, "verdict").asString().equals(cell.verdict()))
        findings.add("cell " + name + " recomputed the verdict " + cell.verdict());
    }
  }

  /** The retained datasets of one stage, in dataset-index order. */
  private static List<Path> datasets(Path evidence) {
    if (!Files.isDirectory(evidence)) return List.of();
    try (final Stream<Path> files = Files.list(evidence)) {
      return files
          .filter(file -> name(file).endsWith(".json"))
          .sorted(Comparator.comparingInt(SyntheticReproduction::index))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static int index(Path file) {
    final String name = name(file);
    return Integer.parseInt(name.substring(0, name.length() - ".json".length()));
  }

  private static String name(Path file) {
    return Objects.requireNonNull(file.getFileName()).toString();
  }

  private static ModelValues column(JsonNode values) {
    final double[] column = new double[values.size()];
    for (int r = 0; r < values.size(); r++) column[r] = values.get(r).doubleValue();
    return SyntheticRecovery.column(column);
  }

  private static ModelValues matrix(JsonNode values) {
    final SimpleMatrix matrix = new SimpleMatrix(values.size(), values.get(0).size());
    for (int r = 0; r < values.size(); r++)
      for (int c = 0; c < values.get(r).size(); c++)
        matrix.set(r, c, values.get(r).get(c).doubleValue());
    return ModelValues.owned(matrix);
  }

  /** The recomputed stages, retained as the reproduction record of one run. */
  static ObjectNode record(Outcome outcome) {
    final ObjectNode retained = JSON.createObjectNode();
    retained.put("reproducedDatasets", outcome.datasets());
    retained.put("reproducedArrays", outcome.arrays());
    retained.put(
        "comparison",
        "each retained predictive array is regenerated from its prediction and its hash compared"
            + " without replacement; intervals, indicators and aggregates are recomputed");
    retained.put(
        "scope",
        "the pinned implementation and environment on the registered architecture; cross"
            + " architecture reproduction is outside this protocol");
    final ArrayNode stages = retained.putArray("stages");
    for (Stage stage : outcome.stages()) {
      final ObjectNode entry = stages.addObject();
      entry.put("convention", stage.convention());
      entry.put("stage", stage.stage());
      entry.put("datasets", stage.datasets());
      entry.put("arrays", stage.arrays());
      entry.put("verdict", stage.verdict());
      entry.set("cells", JSON.valueToTree(stage.cells()));
      entry.set("findings", JSON.valueToTree(stage.findings()));
    }
    retained.set("findings", JSON.valueToTree(outcome.findings()));
    retained.put("interrupted", outcome.interrupted());
    retained.put("outcome", outcome.reproduced() ? "reproduced" : "not_reproduced");
    return retained;
  }
}

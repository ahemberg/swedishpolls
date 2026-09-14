package se.swedishpolls.estimation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The older-fold development report: predictive log scores against the registered baseline and
 * reference, predictive coverage with the observed poll noise, residual autocorrelation, misfit by
 * party, institute and fieldwork length, overlapping-poll dependence, and the two registered
 * sensitivity reruns.
 *
 * <p>Every number here is measured on development folds under a blocked tuning gate, so none of it
 * is a release value and none of it touches the reserved comparison.
 */
public final class DevelopmentDiagnostics {
  private DevelopmentDiagnostics() {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The same legacy generator the joint draws use, so a rerun reproduces every interval. */
  private static final RandomGeneratorFactory<RandomGenerator> RANDOM_FACTORY =
      RandomGeneratorFactory.of("Random");

  /** The candidates a fold scores, in the order the gate compares them. */
  public static final String CANDIDATE = "midpoint";

  public static final String BASELINE = "recency";
  public static final String REFERENCE = "ilr_window";

  /** A movement larger than this needs a disclosure beside the number it moves. */
  public static final double DISCLOSED_SHIFT_POINTS = 10;

  /** The frozen gate needs at least this many paired folds per roster. */
  public static final int MIN_FOLDS = 8;

  /** No development fold may score a poll from the reserved comparison. */
  public static final LocalDate RESERVED_FROM = LocalDate.of(2022, 1, 1);

  /** The registered diagnostic rules. They are development rules, not release tolerances. */
  public record Rules(
      long seed,
      int scoreHorizonDays,
      int scoreDraws,
      List<Integer> pairedStandardErrorLags,
      List<Integer> residualLags,
      List<Integer> fieldworkBands,
      List<Double> coverage95,
      List<Double> coverage50) {
    public Rules {
      if (scoreHorizonDays < 1) throw new IllegalArgumentException("A horizon scores a poll");
      if (scoreDraws < 2) throw new IllegalArgumentException("Coverage needs at least two draws");
      pairedStandardErrorLags = ascending(pairedStandardErrorLags, "paired standard error lag");
      residualLags = ascending(residualLags, "residual lag");
      fieldworkBands = ascending(fieldworkBands, "fieldwork band");
      if (fieldworkBands.getFirst() != 1)
        throw new IllegalArgumentException("The first fieldwork band starts at one day");
      coverage95 = band(coverage95);
      coverage50 = band(coverage50);
    }

    @Override
    public List<Integer> pairedStandardErrorLags() {
      return List.copyOf(pairedStandardErrorLags);
    }

    @Override
    public List<Integer> residualLags() {
      return List.copyOf(residualLags);
    }

    @Override
    public List<Integer> fieldworkBands() {
      return List.copyOf(fieldworkBands);
    }

    @Override
    public List<Double> coverage95() {
      return List.copyOf(coverage95);
    }

    @Override
    public List<Double> coverage50() {
      return List.copyOf(coverage50);
    }
  }

  private static List<Integer> ascending(List<Integer> values, String name) {
    if (values == null || values.isEmpty())
      throw new IllegalArgumentException("At least one " + name + " is required");
    for (int i = 0; i < values.size(); i++)
      if (values.get(i) < 1 || (i > 0 && values.get(i) <= values.get(i - 1)))
        throw new IllegalArgumentException("Every " + name + " is positive and ascending");
    return List.copyOf(values);
  }

  private static List<Double> band(List<Double> band) {
    if (band == null || band.size() != 2 || !(band.get(0) < band.get(1)))
      throw new IllegalArgumentException("A coverage band is a lower and a higher proportion");
    return List.copyOf(band);
  }

  /**
   * One fold of one roster, with the identities it read and the mean log score of each candidate
   * over the polls all three scored.
   */
  public record Fold(
      String periodId,
      LocalDate cutoff,
      LocalDate scoreThrough,
      int trainingPolls,
      int trainingObservations,
      int scoredPolls,
      int excludedHeldOutPolls,
      Map<String, Integer> heldOutExclusionReasons,
      String trainingRowsSha256,
      String scoredRowsSha256,
      DailyStateSpace.Parameters candidateParameters,
      DailyStateSpace.Parameters referenceParameters,
      List<String> referenceGridBoundaries,
      Map<String, Double> meanLogScore) {
    public Fold {
      heldOutExclusionReasons = Map.copyOf(heldOutExclusionReasons);
      referenceGridBoundaries = List.copyOf(referenceGridBoundaries);
      meanLogScore = ordered(meanLogScore);
    }

    @Override
    public Map<String, Integer> heldOutExclusionReasons() {
      return Map.copyOf(heldOutExclusionReasons);
    }

    @Override
    public List<String> referenceGridBoundaries() {
      return List.copyOf(referenceGridBoundaries);
    }

    @Override
    public Map<String, Double> meanLogScore() {
      return ordered(meanLogScore);
    }
  }

  /** A fold that scored nothing. It is listed rather than dropped, and it blocks the aggregate. */
  public record Unscored(String periodId, LocalDate cutoff, String reason) {}

  /**
   * The paired comparison of one roster's folds: the frozen lag-three gate, and the same statistic
   * at the other registered lags so a longer dependence is visible rather than selected away.
   */
  public record Paired(
      String periodId,
      int folds,
      double baselineDifference,
      double referenceDifference,
      double pairedStandardError,
      Map<Integer, Double> standardErrorByLag,
      boolean passes) {
    public Paired {
      standardErrorByLag = Collections.unmodifiableMap(new TreeMap<>(standardErrorByLag));
    }

    @Override
    public Map<Integer, Double> standardErrorByLag() {
      return Collections.unmodifiableMap(new TreeMap<>(standardErrorByLag));
    }
  }

  /**
   * Predictive coverage and misfit over one group of scored polls. Coverage counts a party in a
   * poll, so the pooled row counts every party of every poll it scored.
   */
  public record Misfit(
      String periodId,
      String candidate,
      String scope,
      String name,
      int polls,
      int cases,
      double coverage95,
      double coverage50,
      double meanLogScore,
      double meanStandardizedResidual,
      double rootMeanSquareStandardizedResidual) {}

  /** How far the whitened residuals of consecutive scored polls stay correlated. */
  public record Autocorrelation(String periodId, int lag, int pairs, double correlation) {}

  /**
   * Dependence between polls scored in the same fold, measured on the whitened residuals that the
   * model says should be independent.
   */
  public record Dependence(
      String periodId,
      int overlappingPairs,
      double overlappingCorrelation,
      int disjointPairs,
      double disjointCorrelation,
      int sameInstitutePairs,
      double sameInstituteCorrelation) {}

  /** How far the published composition moves under the poll-count centering rerun. */
  public record CenteringShift(
      String periodId,
      LocalDate headlineDate,
      int comparedDays,
      Map<String, Double> headlineShiftPoints,
      double maxHeadlineShiftPoints,
      double maxDailyShiftPoints) {
    public CenteringShift {
      headlineShiftPoints = ordered(headlineShiftPoints);
    }

    @Override
    public Map<String, Double> headlineShiftPoints() {
      return ordered(headlineShiftPoints);
    }
  }

  /** How far the published composition moves when one institute is left out of the fit. */
  public record LeftOut(
      String periodId,
      String institute,
      int droppedPolls,
      LocalDate comparedOn,
      Map<String, Double> shiftPoints,
      double maxShiftPoints,
      int comparedDays,
      double maxDailyShiftPoints,
      LocalDate maxDailyShiftOn,
      boolean needsDisclosure) {
    public LeftOut {
      shiftPoints = ordered(shiftPoints);
    }

    @Override
    public Map<String, Double> shiftPoints() {
      return ordered(shiftPoints);
    }
  }

  public record Report(
      String protocolVersion,
      CoverageValidation.Gate gate,
      Rules rules,
      List<Fold> folds,
      List<Unscored> unscored,
      List<Paired> paired,
      List<Misfit> misfit,
      List<Autocorrelation> autocorrelation,
      List<Dependence> dependence,
      List<CenteringShift> centering,
      List<LeftOut> leaveOneInstituteOut) {
    public Report {
      folds = List.copyOf(folds);
      unscored = List.copyOf(unscored);
      paired = List.copyOf(paired);
      misfit = List.copyOf(misfit);
      autocorrelation = List.copyOf(autocorrelation);
      dependence = List.copyOf(dependence);
      centering = List.copyOf(centering);
      leaveOneInstituteOut = List.copyOf(leaveOneInstituteOut);
    }
  }

  private static <T> Map<String, T> ordered(Map<String, T> values) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  public static Rules rules(Path file) {
    try {
      final tools.jackson.databind.JsonNode root = JSON.readTree(Files.readAllBytes(file));
      final tools.jackson.databind.JsonNode diagnostics = required(root, "diagnostics", file);
      return new Rules(
          required(root, "seed", file).longValue(),
          required(root, "score_horizon_days", file).intValue(),
          required(diagnostics, "score_draws", file).intValue(),
          integers(diagnostics, "paired_standard_error_lags", file),
          integers(diagnostics, "residual_lags", file),
          integers(diagnostics, "fieldwork_bands", file),
          doubles(root, "predictive_coverage95", file),
          doubles(root, "predictive_coverage50", file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode required(JsonNode parent, String field, Path file) {
    final tools.jackson.databind.JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || value.isNull())
      throw new IllegalArgumentException(
          "Incomplete diagnostic rules in " + file + ": missing " + field);
    return value;
  }

  private static List<Integer> integers(JsonNode parent, String field, Path file) {
    final java.util.ArrayList<java.lang.Integer> values = new ArrayList<Integer>();
    for (tools.jackson.databind.JsonNode value : required(parent, field, file))
      values.add(value.intValue());
    return values;
  }

  private static List<Double> doubles(JsonNode parent, String field, Path file) {
    final java.util.ArrayList<java.lang.Double> values = new ArrayList<Double>();
    for (tools.jackson.databind.JsonNode value : required(parent, field, file))
      values.add(value.doubleValue());
    return values;
  }

  /**
   * The polls one fold scores: eligible, published after the cutoff and within the registered
   * horizon. A poll is scored once, by the fold whose horizon it falls in.
   */
  public static List<PollCsv.Poll> heldOut(
      List<PollCsv.Poll> polls, DevelopmentTuning.Fold fold, Rules rules) {
    final java.time.LocalDate through = fold.cutoff().plusDays(rules.scoreHorizonDays());
    if (!through.equals(fold.scoreThrough()))
      throw new IllegalArgumentException(
          "Fold " + fold.cutoff() + " scores through " + fold.scoreThrough() + ", not " + through);
    return polls.stream()
        .filter(PollCsv.Poll::publicationTimeEligible)
        .filter(poll -> poll.publicationDate().isAfter(fold.cutoff()))
        .filter(poll -> !poll.publicationDate().isAfter(through))
        .toList();
  }

  /** One scored poll under one candidate. */
  record Scored(
      PollCsv.Poll poll,
      WindowFilter.Window window,
      double logScore,
      List<Double> whitened,
      List<Double> standardized,
      List<Boolean> covered95,
      List<Boolean> covered50,
      List<ComponentPrediction> components,
      String stream) {}

  record ComponentPrediction(
      String component,
      double observed,
      double mean,
      double lower95,
      double upper95,
      double lower50,
      double upper50,
      double standardizedResidual) {}

  /**
   * The predictive log density of the poll's own transformed composition, its whitened residual and
   * the marginal composition coverage read from joint draws of that same predictive distribution.
   */
  private static Scored score(
      PollObservations.Batch batch,
      PollObservations.Observation observation,
      ModelValues mean,
      ModelValues covariance,
      Rules rules,
      String stream) {
    final int dimension = mean.getNumRows();
    final double[][] matrix = new double[dimension][dimension];
    for (int r = 0; r < dimension; r++)
      for (int c = 0; c < dimension; c++) matrix[r][c] = covariance.get(r, c);
    final double[][] factor = WindowFilter.factor(matrix);
    final double[] innovation = new double[dimension];
    for (int i = 0; i < dimension; i++) innovation[i] = observation.ilr().get(i) - mean.get(i);
    final double[] solved = WindowFilter.solve(factor, innovation);
    double quadratic = 0;
    for (int i = 0; i < dimension; i++) quadratic += innovation[i] * solved[i];
    final double logScore =
        -0.5
            * (dimension * Math.log(2 * Math.PI) + WindowFilter.logDeterminant(factor) + quadratic);
    if (!Double.isFinite(logScore))
      throw new IllegalArgumentException("Nonfinite joint predictive log score");
    final java.util.ArrayList<java.lang.Double> whitened = new ArrayList<Double>(dimension);
    for (int r = 0; r < dimension; r++) {
      double value = innovation[r];
      for (int c = 0; c < r; c++) value -= factor[r][c] * whitened.get(c);
      whitened.add(value / factor[r][r]);
    }
    // Coverage is read off joint draws of the predictive distribution, transformed one draw at a
    // time, because a marginal share is not a coordinate of the Gaussian.
    final double[][] basis = PollObservations.transposedBasis(batch);
    final int components = batch.components().size();
    final double[][] draws = new double[components][rules.scoreDraws()];
    final java.util.random.RandomGenerator random =
        RANDOM_FACTORY.create(streamSeed(stream, rules.seed()));
    final double[] normal = new double[dimension];
    final double[] state = new double[dimension];
    final double[] shares = new double[components];
    for (int draw = 0; draw < rules.scoreDraws(); draw++) {
      for (int i = 0; i < dimension; i++) normal[i] = random.nextGaussian();
      for (int i = 0; i < dimension; i++) {
        double value = mean.get(i);
        for (int j = 0; j <= i; j++) value += factor[i][j] * normal[j];
        state[i] = value;
      }
      PollObservations.close(basis, state, shares, batch.period().id());
      for (int component = 0; component < components; component++)
        draws[component][draw] = shares[component];
    }
    final java.util.Map<java.lang.String, java.lang.Double> observed =
        PollObservations.shares(batch, observation.ilr());
    final java.util.ArrayList<java.lang.Boolean> covered95 = new ArrayList<Boolean>(components);
    final java.util.ArrayList<java.lang.Boolean> covered50 = new ArrayList<Boolean>(components);
    final java.util.ArrayList<java.lang.Double> standardized = new ArrayList<Double>(components);
    final List<ComponentPrediction> componentPredictions = new ArrayList<>();
    for (int component = 0; component < components; component++) {
      final double[] column = draws[component];
      double total = 0;
      for (double value : column) total += value;
      final double drawnMean = total / column.length;
      double variance = 0;
      for (double value : column) variance += (value - drawnMean) * (value - drawnMean);
      variance /= column.length - 1;
      Arrays.sort(column);
      final double share = observed.get(batch.components().get(component));
      if (!Double.isFinite(variance) || variance <= 0)
        throw new IllegalArgumentException("Invalid predictive composition variance");
      standardized.add((share - drawnMean) / Math.sqrt(variance));
      componentPredictions.add(
          new ComponentPrediction(
              batch.components().get(component),
              share,
              drawnMean,
              JointUncertainty.quantile(column, (1 - 0.95) / 2),
              JointUncertainty.quantile(column, (1 + 0.95) / 2),
              JointUncertainty.quantile(column, 0.25),
              JointUncertainty.quantile(column, 0.75),
              standardized.getLast()));
      covered95.add(inside(column, share, 0.95));
      covered50.add(inside(column, share, 0.5));
    }
    // The window here is the poll's own fieldwork, whatever convention predicted it: the
    // length bands and the overlap between polls are properties of the source, not of a candidate.
    return new Scored(
        observation.poll(),
        WindowFilter.Convention.FIELDWORK.window(observation),
        logScore,
        List.copyOf(whitened),
        List.copyOf(standardized),
        List.copyOf(covered95),
        List.copyOf(covered50),
        List.copyOf(componentPredictions),
        stream);
  }

  private static boolean inside(double[] sorted, double value, double level) {
    return value >= JointUncertainty.quantile(sorted, (1 - level) / 2)
        && value <= JointUncertainty.quantile(sorted, (1 + level) / 2);
  }

  /** Each scored poll draws from its own stream, named by the fold, candidate and source row. */
  private static long streamSeed(String stream, long seed) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((stream + "|" + seed).getBytes(StandardCharsets.UTF_8));
      long value = 0;
      for (int index = 0; index < Long.BYTES; index++)
        value = (value << 8) | (digest[index] & 0xFF);
      return value;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The SHA-256 of the ordered {@code row:sha256} identities of one fold's rows. */
  private static String rowsSha256(List<PollCsv.Poll> polls) {
    final java.lang.StringBuilder rows = new StringBuilder();
    for (se.swedishpolls.source.PollCsv.Poll poll : polls)
      rows.append(poll.rowNumber())
          .append(':')
          .append(sha256(String.join(",", poll.raw().values())))
          .append('\n');
    return sha256(rows.toString());
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Tunes the ilr-window reference independently on the fold's own training data, over the same
   * frozen grid as the candidate. A reference tuned on the candidate's parameters would not be the
   * comparison the protocol registered.
   */
  static DevelopmentTuning.Resolved tuneReference(
      PollObservations.Batch training,
      String periodId,
      DevelopmentTuning.Fold fold,
      List<LocalDate> elections,
      DevelopmentTuning.Grid grid) {
    final java.util.List<se.swedishpolls.estimation.DailyStateSpace.Parameters> points =
        grid.points();
    final double[] likelihoods =
        points.parallelStream()
            .mapToDouble(
                point -> {
                  final double likelihood =
                      WindowFilter.logLikelihood(
                          training, elections, point, WindowFilter.Convention.FIELDWORK);
                  if (!Double.isFinite(likelihood))
                    throw new IllegalArgumentException(
                        "Nonfinite reference likelihood at "
                            + point
                            + " for "
                            + periodId
                            + " fold "
                            + fold.cutoff());
                  return likelihood;
                })
            .toArray();
    int best = 0;
    for (int i = 1; i < likelihoods.length; i++) if (likelihoods[i] > likelihoods[best]) best = i;
    final se.swedishpolls.estimation.DailyStateSpace.Parameters resolved = points.get(best);
    final java.util.ArrayList<java.lang.String> boundaries = new ArrayList<String>();
    boundary(boundaries, "walkVariance", grid.walkVariances(), resolved.walkVariance());
    boundary(boundaries, "houseScale", grid.houseScales(), resolved.houseScale());
    boundary(
        boundaries,
        "covarianceMultiplier",
        grid.covarianceMultipliers(),
        resolved.covarianceMultiplier());
    return new DevelopmentTuning.Resolved(
        periodId,
        fold,
        resolved,
        likelihoods[best],
        training.observations().size(),
        training.observations().size(),
        training.exclusions().size(),
        Map.of(),
        boundaries);
  }

  private static void boundary(
      List<String> boundaries, String name, List<Double> axis, double value) {
    if (axis.indexOf(value) == 0) boundaries.add(name + ":lower");
    if (axis.indexOf(value) == axis.size() - 1) boundaries.add(name + ":upper");
  }

  /** One fold's scored polls, under each candidate, beside the fold record they came from. */
  record Folded(Fold fold, Map<String, List<Scored>> scored) {}

  /**
   * Scores one fold of one roster. The candidate reads the parameters the tuning run resolved for
   * that fold; the reference tunes its own on the same training data and grid; the baseline has no
   * parameters to tune. All three predict the same held-out polls without ever updating on them.
   */
  static Folded fold(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DevelopmentTuning.Fold fold,
      DevelopmentTuning.Grid grid,
      DailyStateSpace.Parameters candidateParameters,
      Rules rules) {
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> trainingPolls =
        DevelopmentTuning.training(polls, fold);
    final se.swedishpolls.estimation.PollObservations.Batch training =
        PollObservations.prepare(period, trainingPolls);
    if (training.observations().isEmpty())
      throw new IllegalArgumentException("no eligible training observation in the period");
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> heldOutPolls =
        heldOut(polls, fold, rules);
    final se.swedishpolls.estimation.PollObservations.Batch heldOut =
        PollObservations.prepare(period, heldOutPolls);
    if (heldOut.observations().isEmpty())
      throw new IllegalArgumentException("no held-out observation the period can compose");
    final DevelopmentTuning.Resolved reference =
        tuneReference(training, period.id(), fold, elections, grid);
    return fold(
        period,
        polls,
        elections,
        fold,
        candidateParameters,
        reference.parameters(),
        reference.gridBoundaries(),
        rules);
  }

  static Folded fold(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DevelopmentTuning.Fold fold,
      DailyStateSpace.Parameters candidateParameters,
      DailyStateSpace.Parameters referenceParameters,
      List<String> referenceBoundaries,
      Rules rules) {
    if (!fold.scoreThrough().isBefore(RESERVED_FROM))
      throw new IllegalArgumentException("Fold scores into the reserved comparison");
    final List<PollCsv.Poll> trainingPolls = DevelopmentTuning.training(polls, fold);
    final PollObservations.Batch training = PollObservations.prepare(period, trainingPolls);
    final PollObservations.Batch heldOut =
        PollObservations.prepare(period, heldOut(polls, fold, rules));
    final List<PollObservations.Observation> observations = heldOut.observations();
    if (training.observations().isEmpty() || observations.isEmpty())
      throw new IllegalArgumentException("Unexpected empty active fold");
    final se.swedishpolls.estimation.WindowFilter.Scored candidatePredictions =
        WindowFilter.score(
            training,
            observations,
            elections,
            candidateParameters,
            WindowFilter.Convention.MIDPOINT);
    final se.swedishpolls.estimation.WindowFilter.Scored referencePredictions =
        WindowFilter.score(
            training,
            observations,
            elections,
            referenceParameters,
            WindowFilter.Convention.FIELDWORK);
    final se.swedishpolls.estimation.RecencyBaseline.Fit baseline =
        RecencyBaseline.fit(training, fold.cutoff());
    final java.util.LinkedHashMap<
            java.lang.String,
            java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Scored>>
        scored = new LinkedHashMap<String, List<Scored>>();
    scored.put(
        CANDIDATE, scoreAll(heldOut, observations, candidatePredictions, fold, CANDIDATE, rules));
    scored.put(
        REFERENCE, scoreAll(heldOut, observations, referencePredictions, fold, REFERENCE, rules));
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.Scored>
        baselineScored = new ArrayList<Scored>();
    for (se.swedishpolls.estimation.PollObservations.Observation observation : observations)
      baselineScored.add(
          score(
              heldOut,
              observation,
              baseline.mean(),
              RecencyBaseline.predictiveCovariance(heldOut, baseline, observation),
              rules,
              stream(period.id(), fold, BASELINE, observation)));
    scored.put(BASELINE, List.copyOf(baselineScored));
    final java.util.LinkedHashMap<java.lang.String, java.lang.Double> meanLogScore =
        new LinkedHashMap<String, Double>();
    for (java.util.Map.Entry<
            java.lang.String,
            java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Scored>>
        candidate : scored.entrySet())
      meanLogScore.put(
          candidate.getKey(),
          candidate.getValue().stream().mapToDouble(Scored::logScore).average().orElseThrow());
    final java.util.TreeMap<java.lang.String, java.lang.Integer> reasons =
        new TreeMap<String, Integer>();
    for (se.swedishpolls.estimation.PollObservations.Exclusion exclusion : heldOut.exclusions())
      for (java.lang.String reason : exclusion.reasons()) reasons.merge(reason, 1, Integer::sum);
    return new Folded(
        new Fold(
            period.id(),
            fold.cutoff(),
            fold.scoreThrough(),
            trainingPolls.size(),
            training.observations().size(),
            observations.size(),
            heldOut.exclusions().size(),
            reasons,
            rowsSha256(trainingPolls),
            rowsSha256(observations.stream().map(PollObservations.Observation::poll).toList()),
            candidateParameters,
            referenceParameters,
            referenceBoundaries,
            meanLogScore),
        Map.copyOf(scored));
  }

  private static List<Scored> scoreAll(
      PollObservations.Batch heldOut,
      List<PollObservations.Observation> observations,
      WindowFilter.Scored predictions,
      DevelopmentTuning.Fold fold,
      String candidate,
      Rules rules) {
    if (predictions.predictions().size() != observations.size())
      throw new IllegalStateException("Every held-out poll is predicted exactly once");
    // Predictions come back in the order their windows resolve, which is not the order the polls
    // were composed in, so they are matched by source row.
    final java.util.LinkedHashMap<
            java.lang.Integer, se.swedishpolls.estimation.WindowFilter.Prediction>
        byRow = new LinkedHashMap<Integer, WindowFilter.Prediction>();
    for (se.swedishpolls.estimation.WindowFilter.Prediction prediction : predictions.predictions())
      byRow.put(prediction.poll().rowNumber(), prediction);
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.Scored> scored =
        new ArrayList<Scored>();
    for (se.swedishpolls.estimation.PollObservations.Observation observation : observations) {
      final se.swedishpolls.estimation.WindowFilter.Prediction prediction =
          byRow.get(observation.poll().rowNumber());
      if (prediction == null)
        throw new IllegalStateException(
            "No prediction for held-out row " + observation.poll().rowNumber());
      scored.add(
          score(
              heldOut,
              observation,
              prediction.mean(),
              prediction.covariance(),
              rules,
              stream(heldOut.period().id(), fold, candidate, observation)));
    }
    return List.copyOf(scored);
  }

  private static String stream(
      String periodId,
      DevelopmentTuning.Fold fold,
      String candidate,
      PollObservations.Observation observation) {
    return periodId + "|" + fold.cutoff() + "|" + candidate + "|" + observation.poll().rowNumber();
  }

  /**
   * The frozen gate over one roster's folds, with the same paired statistic at the other registered
   * lags. The lag-three value is the gate's own; the others are reported so unexplained longer
   * dependence is visible rather than selected against.
   */
  static Paired paired(String periodId, List<Fold> folds, Rules rules) {
    final double[] candidate =
        folds.stream().mapToDouble(f -> f.meanLogScore().get(CANDIDATE)).toArray();
    final double[] baseline =
        folds.stream().mapToDouble(f -> f.meanLogScore().get(BASELINE)).toArray();
    final double[] reference =
        folds.stream().mapToDouble(f -> f.meanLogScore().get(REFERENCE)).toArray();
    final se.swedishpolls.estimation.PredictiveComparison.Result gate =
        PredictiveComparison.evaluate(candidate, baseline, reference);
    final double[] differences = new double[candidate.length];
    for (int i = 0; i < differences.length; i++) differences[i] = candidate[i] - reference[i];
    final java.util.TreeMap<java.lang.Integer, java.lang.Double> byLag =
        new TreeMap<Integer, Double>();
    for (int lag : rules.pairedStandardErrorLags())
      byLag.put(lag, pairedStandardError(differences, lag));
    return new Paired(
        periodId,
        folds.size(),
        gate.baselineDifference(),
        gate.referenceDifference(),
        gate.pairedStandardError(),
        byLag,
        gate.passes());
  }

  /** The Bartlett/Newey-West standard error of a paired mean at one truncation lag. */
  static double pairedStandardError(double[] differences, int lag) {
    final int count = differences.length;
    final double mean = Arrays.stream(differences).average().orElseThrow();
    double variance = 0;
    for (int l = 0; l <= lag; l++) {
      double covariance = 0;
      for (int i = l; i < count; i++)
        covariance += (differences[i] - mean) * (differences[i - l] - mean) / count;
      variance += (l == 0 ? 1 : 2 * (1 - l / (lag + 1.0))) * covariance / count;
    }
    if (!Double.isFinite(variance) || variance < 0)
      throw new IllegalArgumentException("Invalid paired score variance at lag " + lag);
    return Math.sqrt(variance);
  }

  /** One group's running coverage and misfit counts. */
  private static final class Group {
    private int polls;
    private int cases;
    private int covered95;
    private int covered50;
    private double logScore;
    private double residual;
    private double squared;

    private void add(Scored scored, int component) {
      cases++;
      if (scored.covered95().get(component)) covered95++;
      if (scored.covered50().get(component)) covered50++;
      residual += scored.standardized().get(component);
      squared += scored.standardized().get(component) * scored.standardized().get(component);
    }

    private Misfit misfit(String periodId, String candidate, String scope, String name) {
      return new Misfit(
          periodId,
          candidate,
          scope,
          name,
          polls,
          cases,
          covered95 / (double) cases,
          covered50 / (double) cases,
          logScore / polls,
          residual / cases,
          Math.sqrt(squared / cases));
    }
  }

  /**
   * Coverage and misfit pooled, by party, by institute and by fieldwork-length band. The pooled row
   * exists for every candidate; the breakdowns describe the estimator under test.
   */
  static List<Misfit> misfit(
      String periodId, List<String> components, Map<String, List<Scored>> scored, Rules rules) {
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.Misfit> misfit =
        new ArrayList<Misfit>();
    for (java.util.Map.Entry<
            java.lang.String,
            java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Scored>>
        candidate : scored.entrySet()) {
      final se.swedishpolls.estimation.DevelopmentDiagnostics.Group pooled = new Group();
      for (se.swedishpolls.estimation.DevelopmentDiagnostics.Scored poll : candidate.getValue()) {
        pooled.polls++;
        pooled.logScore += poll.logScore();
        for (int component = 0; component < components.size(); component++)
          pooled.add(poll, component);
      }
      misfit.add(pooled.misfit(periodId, candidate.getKey(), "all", "all"));
    }
    final java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Scored> polls =
        scored.get(CANDIDATE);
    final java.util.LinkedHashMap<
            java.lang.String, se.swedishpolls.estimation.DevelopmentDiagnostics.Group>
        parties = new LinkedHashMap<String, Group>();
    final java.util.TreeMap<
            java.lang.String, se.swedishpolls.estimation.DevelopmentDiagnostics.Group>
        institutes = new TreeMap<String, Group>();
    final java.util.TreeMap<
            java.lang.Integer, se.swedishpolls.estimation.DevelopmentDiagnostics.Group>
        bands = new TreeMap<Integer, Group>();
    for (se.swedishpolls.estimation.DevelopmentDiagnostics.Scored poll : polls) {
      final se.swedishpolls.estimation.DevelopmentDiagnostics.Group institute =
          institutes.computeIfAbsent(poll.poll().institute(), name -> new Group());
      final se.swedishpolls.estimation.DevelopmentDiagnostics.Group band =
          bands.computeIfAbsent(band(poll.window().days(), rules), days -> new Group());
      institute.polls++;
      institute.logScore += poll.logScore();
      band.polls++;
      band.logScore += poll.logScore();
      for (int component = 0; component < components.size(); component++) {
        final se.swedishpolls.estimation.DevelopmentDiagnostics.Group party =
            parties.computeIfAbsent(components.get(component), name -> new Group());
        party.polls++;
        party.logScore += poll.logScore();
        party.add(poll, component);
        institute.add(poll, component);
        band.add(poll, component);
      }
    }
    for (java.util.Map.Entry<
            java.lang.String, se.swedishpolls.estimation.DevelopmentDiagnostics.Group>
        party : parties.entrySet())
      misfit.add(party.getValue().misfit(periodId, CANDIDATE, "party", party.getKey()));
    for (java.util.Map.Entry<
            java.lang.String, se.swedishpolls.estimation.DevelopmentDiagnostics.Group>
        institute : institutes.entrySet())
      misfit.add(institute.getValue().misfit(periodId, CANDIDATE, "institute", institute.getKey()));
    for (java.util.Map.Entry<
            java.lang.Integer, se.swedishpolls.estimation.DevelopmentDiagnostics.Group>
        band : bands.entrySet())
      misfit.add(
          band.getValue()
              .misfit(periodId, CANDIDATE, "fieldwork_days", bandName(band.getKey(), rules)));
    return List.copyOf(misfit);
  }

  private static int band(int days, Rules rules) {
    int band = rules.fieldworkBands().getFirst();
    for (int edge : rules.fieldworkBands()) if (days >= edge) band = edge;
    return band;
  }

  private static String bandName(int band, Rules rules) {
    final int index = rules.fieldworkBands().indexOf(band);
    return index + 1 < rules.fieldworkBands().size()
        ? band + "-" + (rules.fieldworkBands().get(index + 1) - 1)
        : band + "+";
  }

  /**
   * The autocorrelation of the whitened residuals of consecutive scored polls inside one fold. The
   * model says these are independent standard normals, so anything away from zero is misfit the
   * paired standard error has to carry.
   */
  static List<Autocorrelation> autocorrelation(
      String periodId, List<List<Scored>> byFold, Rules rules) {
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.Autocorrelation>
        autocorrelation = new ArrayList<Autocorrelation>();
    for (int lag : rules.residualLags()) {
      double product = 0;
      double squared = 0;
      int pairs = 0;
      int values = 0;
      for (java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Scored> fold : byFold) {
        final java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Scored> ordered =
            fold.stream()
                .sorted(
                    Comparator.comparing((Scored scored) -> scored.window().to())
                        .thenComparingInt(scored -> scored.poll().rowNumber()))
                .toList();
        for (se.swedishpolls.estimation.DevelopmentDiagnostics.Scored scored : ordered)
          for (double residual : scored.whitened()) {
            squared += residual * residual;
            values++;
          }
        for (int i = lag; i < ordered.size(); i++) {
          for (int c = 0; c < ordered.get(i).whitened().size(); c++)
            product += ordered.get(i).whitened().get(c) * ordered.get(i - lag).whitened().get(c);
          pairs++;
        }
      }
      autocorrelation.add(
          new Autocorrelation(
              periodId,
              lag,
              pairs,
              pairs == 0
                  ? 0
                  : product
                      / (pairs * (double) byFold.getFirst().getFirst().whitened().size())
                      / (squared / values)));
    }
    return List.copyOf(autocorrelation);
  }

  /**
   * Dependence between polls scored in the same fold: overlapping fieldwork against disjoint, and
   * the same institute against the rest. Independent whitened residuals average to zero on every
   * one of these groups.
   */
  static Dependence dependence(String periodId, List<List<Scored>> byFold) {
    double overlapping = 0;
    double disjoint = 0;
    double institute = 0;
    int overlappingPairs = 0;
    int disjointPairs = 0;
    int institutePairs = 0;
    for (java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Scored> fold : byFold)
      for (int i = 0; i < fold.size(); i++)
        for (int j = i + 1; j < fold.size(); j++) {
          double correlation = 0;
          final se.swedishpolls.estimation.DevelopmentDiagnostics.Scored left = fold.get(i);
          final se.swedishpolls.estimation.DevelopmentDiagnostics.Scored right = fold.get(j);
          for (int c = 0; c < left.whitened().size(); c++)
            correlation +=
                left.whitened().get(c) * right.whitened().get(c) / left.whitened().size();
          if (left.window().overlaps(right.window())) {
            overlapping += correlation;
            overlappingPairs++;
          } else {
            disjoint += correlation;
            disjointPairs++;
          }
          if (left.poll().institute().equals(right.poll().institute())) {
            institute += correlation;
            institutePairs++;
          }
        }
    return new Dependence(
        periodId,
        overlappingPairs,
        overlappingPairs == 0 ? 0 : overlapping / overlappingPairs,
        disjointPairs,
        disjointPairs == 0 ? 0 : disjoint / disjointPairs,
        institutePairs,
        institutePairs == 0 ? 0 : institute / institutePairs);
  }

  /**
   * The poll-count centering rerun. The two runs share every fitted state and differ only in the
   * ensemble the level is reported against, so the difference is a change of reference.
   */
  static CenteringShift centering(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules rules) {
    final se.swedishpolls.estimation.EstimateHistory.Fitted equal =
        EstimateHistory.fitted(
            period, polls, elections, parameters, rules, DailyStateSpace.Centering.EQUAL_INSTITUTE);
    final se.swedishpolls.estimation.EstimateHistory.Fitted counted =
        EstimateHistory.fitted(
            period, polls, elections, parameters, rules, DailyStateSpace.Centering.POLL_COUNT);
    final se.swedishpolls.estimation.DevelopmentDiagnostics.Compared compared =
        compare(equal, counted);
    final java.time.LocalDate headline = lastDay(equal);
    final java.util.Map<java.lang.String, java.lang.Double> shifts =
        shift(equal, counted, headline);
    return new CenteringShift(
        period.id(),
        headline,
        compared.days(),
        shifts,
        shifts.values().stream().mapToDouble(Math::abs).max().orElse(0),
        compared.maxShiftPoints());
  }

  /**
   * The leave-one-institute-out reruns. Each drops one institute's polls entirely and refits, which
   * moves the supported window as well as the level, so the two runs are compared on the last day
   * both of them estimate.
   */
  static List<LeftOut> leaveOneInstituteOut(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules rules) {
    final se.swedishpolls.estimation.EstimateHistory.Fitted whole =
        EstimateHistory.fitted(period, polls, elections, parameters, rules);
    final java.util.TreeMap<java.lang.String, java.lang.Integer> counts =
        new TreeMap<String, Integer>();
    for (se.swedishpolls.estimation.EstimateHistory.Span span : whole.spans())
      for (se.swedishpolls.estimation.PollObservations.Observation observation :
          span.batch().observations())
        counts.merge(observation.poll().institute(), 1, Integer::sum);
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.LeftOut> left =
        new ArrayList<LeftOut>();
    for (java.util.Map.Entry<java.lang.String, java.lang.Integer> counted : counts.entrySet()) {
      final java.lang.String institute = counted.getKey();
      final java.util.List<se.swedishpolls.source.PollCsv.Poll> kept =
          polls.stream().filter(poll -> !institute.equals(poll.institute())).toList();
      final se.swedishpolls.estimation.EstimateHistory.Fitted dropped =
          EstimateHistory.fitted(period, kept, elections, parameters, rules);
      final java.time.LocalDate date =
          lastDay(dropped).isBefore(lastDay(whole)) ? lastDay(dropped) : lastDay(whole);
      final java.util.Map<java.lang.String, java.lang.Double> shifts = shift(whole, dropped, date);
      // An institute that stopped publishing years ago barely moves the headline, so the largest
      // shift over the days both runs estimate is reported beside it.
      final se.swedishpolls.estimation.DevelopmentDiagnostics.Compared compared =
          compare(whole, dropped);
      final double worst = shifts.values().stream().mapToDouble(Math::abs).max().orElse(0);
      left.add(
          new LeftOut(
              period.id(),
              institute,
              counted.getValue(),
              date,
              shifts,
              worst,
              compared.days(),
              compared.maxShiftPoints(),
              compared.maxShiftOn(),
              Math.max(worst, compared.maxShiftPoints()) > DISCLOSED_SHIFT_POINTS));
    }
    return List.copyOf(left);
  }

  /** The largest daily movement between two runs of one period, over the days both estimate. */
  private record Compared(int days, double maxShiftPoints, LocalDate maxShiftOn) {}

  private static Compared compare(EstimateHistory.Fitted left, EstimateHistory.Fitted right) {
    int days = 0;
    double worst = 0;
    LocalDate on = null;
    for (se.swedishpolls.estimation.EstimateHistory.Span span : left.spans())
      for (se.swedishpolls.estimation.DailyStateSpace.Day day : span.fit().days()) {
        final java.util.Map<java.lang.String, java.lang.Double> there = sharesOn(right, day.date());
        if (there == null) continue;
        days++;
        final java.util.Map<java.lang.String, java.lang.Double> here =
            PollObservations.shares(span.batch(), day.smoothedMean());
        for (java.util.Map.Entry<java.lang.String, java.lang.Double> component : here.entrySet()) {
          final double shift = Math.abs(there.get(component.getKey()) - component.getValue());
          if (shift > worst) {
            worst = shift;
            on = day.date();
          }
        }
      }
    return new Compared(days, worst, on);
  }

  /** One day's per-component movement from the first run to the second, in percentage points. */
  private static Map<String, Double> shift(
      EstimateHistory.Fitted from, EstimateHistory.Fitted to, LocalDate date) {
    final java.util.Map<java.lang.String, java.lang.Double> here = sharesOn(from, date);
    final java.util.Map<java.lang.String, java.lang.Double> there = sharesOn(to, date);
    if (here == null || there == null)
      throw new IllegalArgumentException("Neither run estimates " + date);
    final java.util.LinkedHashMap<java.lang.String, java.lang.Double> shifts =
        new LinkedHashMap<String, Double>();
    for (java.util.Map.Entry<java.lang.String, java.lang.Double> component : here.entrySet())
      shifts.put(component.getKey(), there.get(component.getKey()) - component.getValue());
    return shifts;
  }

  private static LocalDate lastDay(EstimateHistory.Fitted fitted) {
    return fitted.spans().getLast().fit().days().getLast().date();
  }

  /** The smoothed composition of one date, or null when no run of this fit estimates it. */
  private static Map<String, Double> sharesOn(EstimateHistory.Fitted fitted, LocalDate date) {
    for (se.swedishpolls.estimation.EstimateHistory.Span span : fitted.spans()) {
      final java.util.List<se.swedishpolls.estimation.DailyStateSpace.Day> days = span.fit().days();
      if (!date.isBefore(days.getFirst().date()) && !date.isAfter(days.getLast().date()))
        return PollObservations.shares(
            span.batch(),
            days.get((int) ChronoUnit.DAYS.between(days.getFirst().date(), date)).smoothedMean());
    }
    return null;
  }

  /**
   * Scores every fold of every tuned roster, aggregates the registered diagnostics and reruns both
   * sensitivity cases on the validated periods. The coverage gate carries in whole, and every
   * unscored fold, failed comparison and coverage band outside its registered range adds its own
   * reason.
   */
  public static Report report(
      List<Roster.CoveragePeriod> periods,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DevelopmentTuning.Protocol protocol,
      DevelopmentTuning.Tuning tuning,
      CoverageValidation.Report coverage,
      Rules rules) {
    // Folds are bounded by their own cutoff and horizon, not by the coverage-evidence window: a
    // poll published inside the horizon is scored even though its fieldwork ends after the last
    // cutoff. The horizon still stops before the reserved comparison.
    for (se.swedishpolls.estimation.DevelopmentTuning.Fold registered : protocol.folds())
      if (!registered.scoreThrough().isBefore(RESERVED_FROM))
        throw new IllegalArgumentException(
            "Fold " + registered.cutoff() + " scores into the reserved comparison");
    final java.util.LinkedHashMap<
            java.lang.String, se.swedishpolls.estimation.DevelopmentTuning.Resolved>
        resolved = new LinkedHashMap<String, DevelopmentTuning.Resolved>();
    for (se.swedishpolls.estimation.DevelopmentTuning.Resolved point : tuning.resolved())
      resolved.put(point.periodId() + "|" + point.fold().cutoff(), point);
    final java.util.ArrayList<java.lang.String> reasons = new ArrayList<String>();
    for (java.lang.String reason : coverage.gate().reasons())
      reasons.add("coverage validation: " + reason);
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.Fold> folds =
        new ArrayList<Fold>();
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.Unscored> unscored =
        new ArrayList<Unscored>();
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.Paired> paired =
        new ArrayList<Paired>();
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.Misfit> misfit =
        new ArrayList<Misfit>();
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.Autocorrelation>
        autocorrelation = new ArrayList<Autocorrelation>();
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.Dependence>
        dependence = new ArrayList<Dependence>();
    for (se.swedishpolls.source.Roster.CoveragePeriod period : periods) {
      final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.Fold>
          periodFolds = new ArrayList<Fold>();
      final java.util.ArrayList<
              java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Scored>>
          byFold = new ArrayList<List<Scored>>();
      final java.util.LinkedHashMap<
              java.lang.String,
              java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Scored>>
          pooled = new LinkedHashMap<String, List<Scored>>();
      for (se.swedishpolls.estimation.DevelopmentTuning.Fold fold : protocol.folds()) {
        final se.swedishpolls.estimation.DevelopmentTuning.Resolved point =
            resolved.get(period.id() + "|" + fold.cutoff());
        if (point == null) {
          unscored.add(
              new Unscored(
                  period.id(),
                  fold.cutoff(),
                  "the tuning run resolved no parameters for this fold"));
          continue;
        }
        try {
          final se.swedishpolls.estimation.DevelopmentDiagnostics.Folded folded =
              fold(period, polls, elections, fold, protocol.grid(), point.parameters(), rules);
          periodFolds.add(folded.fold());
          byFold.add(folded.scored().get(CANDIDATE));
          for (java.util.Map.Entry<
                  java.lang.String,
                  java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Scored>>
              candidate : folded.scored().entrySet())
            pooled
                .computeIfAbsent(candidate.getKey(), name -> new ArrayList<>())
                .addAll(candidate.getValue());
        } catch (RuntimeException e) {
          unscored.add(new Unscored(period.id(), fold.cutoff(), e.getMessage()));
        }
      }
      folds.addAll(periodFolds);
      if (periodFolds.size() < MIN_FOLDS) {
        reasons.add(
            period.id()
                + ": only "
                + periodFolds.size()
                + " folds scored, below the "
                + MIN_FOLDS
                + " the paired comparison requires");
        continue;
      }
      final se.swedishpolls.estimation.DevelopmentDiagnostics.Paired comparison =
          paired(period.id(), periodFolds, rules);
      paired.add(comparison);
      if (comparison.baselineDifference() <= 0)
        reasons.add(
            period.id()
                + ": the midpoint candidate does not beat the recency baseline, at "
                + comparison.baselineDifference()
                + " mean paired log score");
      if (comparison.referenceDifference() < -comparison.pairedStandardError())
        reasons.add(
            period.id()
                + ": the midpoint candidate loses to the ilr-window reference by "
                + -comparison.referenceDifference()
                + ", beyond the paired standard error of "
                + comparison.pairedStandardError());
      final java.util.List<java.lang.String> components =
          PollObservations.prepare(period, polls).components();
      final java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Misfit> measured =
          misfit(period.id(), components, pooled, rules);
      misfit.addAll(measured);
      autocorrelation.addAll(autocorrelation(period.id(), byFold, rules));
      dependence.add(dependence(period.id(), byFold));
      for (se.swedishpolls.estimation.DevelopmentDiagnostics.Misfit row : measured)
        if (row.scope().equals("all") && row.candidate().equals(CANDIDATE)) {
          if (outside(row.coverage95(), rules.coverage95()))
            reasons.add(
                period.id()
                    + ": pooled 95% predictive coverage of "
                    + row.coverage95()
                    + " is outside the registered "
                    + rules.coverage95());
          if (outside(row.coverage50(), rules.coverage50()))
            reasons.add(
                period.id()
                    + ": pooled 50% predictive coverage of "
                    + row.coverage50()
                    + " is outside the registered "
                    + rules.coverage50());
        }
      for (se.swedishpolls.estimation.DevelopmentDiagnostics.Fold fold : periodFolds)
        if (!fold.referenceGridBoundaries().isEmpty()) {
          reasons.add(
              period.id()
                  + ": the ilr-window reference resolved on a grid boundary in "
                  + periodFolds.stream()
                      .filter(other -> !other.referenceGridBoundaries().isEmpty())
                      .count()
                  + " folds, which needs the same documented expansion as the candidate's");
          break;
        }
    }
    for (se.swedishpolls.estimation.DevelopmentDiagnostics.Unscored fold : unscored)
      reasons.add(fold.periodId() + " fold " + fold.cutoff() + " scored nothing: " + fold.reason());
    final java.util.LinkedHashMap<
            java.lang.String, se.swedishpolls.estimation.CoverageValidation.Validated>
        evidence = new LinkedHashMap<String, CoverageValidation.Validated>();
    for (se.swedishpolls.estimation.CoverageValidation.Validated validated : coverage.periods())
      evidence.put(validated.periodId(), validated);
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.CenteringShift>
        centering = new ArrayList<CenteringShift>();
    final java.util.ArrayList<se.swedishpolls.estimation.DevelopmentDiagnostics.LeftOut> leftOut =
        new ArrayList<LeftOut>();
    for (se.swedishpolls.source.Roster.CoveragePeriod period : periods) {
      final se.swedishpolls.estimation.CoverageValidation.Validated validated =
          evidence.get(period.id());
      if (!period.supportValidated() || validated == null || !validated.supported()) continue;
      centering.add(centering(period, polls, elections, validated.parameters(), coverage.rules()));
      leftOut.addAll(
          leaveOneInstituteOut(period, polls, elections, validated.parameters(), coverage.rules()));
    }
    return new Report(
        coverage.protocolVersion(),
        new CoverageValidation.Gate(!reasons.isEmpty(), reasons),
        rules,
        folds,
        unscored,
        paired,
        misfit,
        autocorrelation,
        dependence,
        centering,
        leftOut);
  }

  private static boolean outside(double value, List<Double> band) {
    return value < band.get(0) || value > band.get(1);
  }

  public static String report(Report report) {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
  }
}

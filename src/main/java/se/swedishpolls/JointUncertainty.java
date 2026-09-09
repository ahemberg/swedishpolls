package se.swedishpolls;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.simple.SimpleMatrix;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Joint draws of the estimated composition, transformed before any averaging, with the metadata a
 * rerun needs to reproduce every number exactly.
 */
public final class JointUncertainty {
  private JointUncertainty() {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final RandomGeneratorFactory<RandomGenerator> RANDOM_FACTORY =
      RandomGeneratorFactory.of("Random");

  /** The estimator classes the implementation digest covers, hashed in this order. */
  private static final List<Class<?>> IMPLEMENTATION =
      List.of(
          PollCsv.class,
          Roster.class,
          PollObservations.class,
          DailyStateSpace.class,
          DevelopmentTuning.class,
          CoverageValidation.class,
          EstimateHistory.class,
          JointUncertainty.class);

  /** The registered draw rules. They are development rules, not release tolerances. */
  public record Rules(long seed, int draws, List<Double> intervalLevels, int precisionRepeats) {
    public Rules {
      if (draws < 2) throw new IllegalArgumentException("A joint summary needs at least two draws");
      if (precisionRepeats < 2)
        throw new IllegalArgumentException("A precision study needs at least two seeds");
      intervalLevels = List.copyOf(intervalLevels);
      if (intervalLevels.isEmpty())
        throw new IllegalArgumentException("At least one interval level is required");
      for (int i = 0; i < intervalLevels.size(); i++) {
        double level = intervalLevels.get(i);
        if (!Double.isFinite(level) || level <= 0 || level >= 1)
          throw new IllegalArgumentException("Inadmissible interval level " + level);
        if (i > 0 && level <= intervalLevels.get(i - 1))
          throw new IllegalArgumentException("Interval levels must be strictly ascending");
      }
    }

    @Override
    public List<Double> intervalLevels() {
      return List.copyOf(intervalLevels);
    }

    /** The same rules at another seed, which is the only thing a precision repeat changes. */
    public Rules withSeed(long seed) {
      return new Rules(seed, draws, intervalLevels, precisionRepeats);
    }
  }

  /** A marginal interval of one component, read off its own draws. Endpoints are never summed. */
  public record Interval(double level, double lower, double upper) {}

  /**
   * One component's summary of a day's joint draws. {@code mean} averages the transformed draws and
   * is the number to publish; {@code stateMean} transforms the mean state once, which is the
   * checkpoint 6 series. The two differ because the transform is nonlinear.
   */
  public record Component(
      String component, double mean, double stateMean, List<Interval> intervals) {
    public Component {
      intervals = List.copyOf(intervals);
    }

    @Override
    public List<Interval> intervals() {
      return List.copyOf(intervals);
    }
  }

  /** One estimated day's composition, in percent and component order. */
  public record Day(LocalDate date, List<Component> components) {
    public Day {
      components = List.copyOf(components);
    }

    @Override
    public List<Component> components() {
      return List.copyOf(components);
    }
  }

  /** One separately fitted run's estimated days. Segments are never joined across a boundary. */
  public record Segment(String periodId, LocalDate from, LocalDate to, List<Day> days) {
    public Segment {
      days = List.copyOf(days);
    }

    @Override
    public List<Day> days() {
      return List.copyOf(days);
    }
  }

  /**
   * The retained final-day joint draws, one row per draw and one column per component, in percent.
   * Threshold, seat and coalition quantities read these rows rather than the marginal summaries.
   */
  public record Draws(
      String periodId, LocalDate date, List<String> components, long daySeed, ModelValues shares) {
    public Draws {
      components = List.copyOf(components);
    }

    @Override
    public List<String> components() {
      return List.copyOf(components);
    }

    public int count() {
      return shares.getNumRows();
    }
  }

  /** Everything a rerun needs to reproduce this run's numbers exactly. */
  public record Reproduction(
      long seed,
      int draws,
      String periodId,
      List<String> components,
      String basisSha256,
      String inputRowsSha256,
      int inputRows,
      DailyStateSpace.Parameters parameters,
      String implementationSha256,
      String javaRuntimeVersion,
      String javaVmVersion,
      String osName,
      String osArch,
      String linearAlgebraVersion) {
    public Reproduction {
      components = List.copyOf(components);
    }

    @Override
    public List<String> components() {
      return List.copyOf(components);
    }
  }

  /** One period's drawn estimate, with the final-day draws the later summaries read. */
  public record Estimated(
      String periodId, List<Segment> segments, Draws finalDraws, Reproduction reproduction) {
    public Estimated {
      segments = List.copyOf(segments);
    }

    @Override
    public List<Segment> segments() {
      return List.copyOf(segments);
    }
  }

  /** A rerun at the same seed, which must return every retained draw unchanged. */
  public record Reproduced(long daySeed, int comparedValues, double maxAbsoluteDifference) {
    public boolean exact() {
      return maxAbsoluteDifference == 0;
    }
  }

  /** How far a summary moves between runs that differ only in their seed. */
  public record Precision(
      String component,
      double level,
      double meanSpreadPoints,
      double lowerSpreadPoints,
      double upperSpreadPoints,
      int comparedDays,
      int seeds) {}

  /**
   * A development bound proposed from this run's evidence. The manifest resolves none of them, so
   * each carries its units, largest observed error, case count, seeds and rationale instead.
   */
  public record ProposedTolerance(
      String name,
      String units,
      double maxObservedError,
      int cases,
      List<Long> seeds,
      String rationale) {
    public ProposedTolerance {
      seeds = List.copyOf(seeds);
    }

    @Override
    public List<Long> seeds() {
      return List.copyOf(seeds);
    }
  }

  /** One period's recorded evidence. The daily series itself is an estimator output. */
  public record Published(
      String periodId,
      Reproduction reproduction,
      Day headline,
      List<Day> segmentEdges,
      int estimatedDays,
      double maxStateMeanShiftPoints,
      Reproduced reproduced,
      List<Precision> precision) {
    public Published {
      segmentEdges = List.copyOf(segmentEdges);
      precision = List.copyOf(precision);
    }

    @Override
    public List<Day> segmentEdges() {
      return List.copyOf(segmentEdges);
    }

    @Override
    public List<Precision> precision() {
      return List.copyOf(precision);
    }
  }

  public record Report(
      String protocolVersion,
      CoverageValidation.Gate gate,
      Rules rules,
      List<Published> periods,
      List<ProposedTolerance> proposedTolerances) {
    public Report {
      periods = List.copyOf(periods);
      proposedTolerances = List.copyOf(proposedTolerances);
    }

    @Override
    public List<Published> periods() {
      return List.copyOf(periods);
    }

    @Override
    public List<ProposedTolerance> proposedTolerances() {
      return List.copyOf(proposedTolerances);
    }
  }

  public static Rules rules(Path file) {
    try {
      var root = JSON.readTree(Files.readAllBytes(file));
      var uncertainty = required(root, "uncertainty", file);
      var levels = new ArrayList<Double>();
      for (var level : required(uncertainty, "interval_levels", file))
        levels.add(level.doubleValue());
      return new Rules(
          required(root, "seed", file).longValue(),
          required(root, "final_draws", file).intValue(),
          levels,
          required(uncertainty, "precision_repeats", file).intValue());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode required(JsonNode parent, String field, Path file) {
    var value = parent == null ? null : parent.get(field);
    if (value == null || value.isNull())
      throw new IllegalArgumentException(
          "Incomplete uncertainty rules in " + file + ": missing " + field);
    return value;
  }

  /** The seeds of the repeated-seed precision study: the registered seed and its successors. */
  public static List<Long> precisionSeeds(Rules rules) {
    var seeds = new ArrayList<Long>();
    for (int repeat = 0; repeat < rules.precisionRepeats(); repeat++)
      seeds.add(rules.seed() + repeat);
    return List.copyOf(seeds);
  }

  /**
   * Draws the joint composition of every estimated day of one period, over the same separately
   * fitted runs the daily history publishes, and retains the final day's draws.
   */
  public static Estimated estimate(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules coverage,
      Rules rules) {
    var fitted = EstimateHistory.fitted(period, polls, elections, parameters, coverage);
    var segments = new ArrayList<Segment>();
    for (var span : fitted.spans()) {
      var basis = PollObservations.transposedBasis(span.batch());
      var days =
          span.fit().days().parallelStream()
              .map(day -> summarize(span.batch(), basis, period.id(), day, rules))
              .toList();
      segments.add(new Segment(period.id(), days.getFirst().date(), days.getLast().date(), days));
    }
    var last = fitted.spans().getLast();
    var lastDay = last.fit().days().getLast();
    var draws =
        transformed(
            last.batch(),
            PollObservations.transposedBasis(last.batch()),
            period.id(),
            lastDay,
            rules);
    var retained = new SimpleMatrix(draws.length, last.batch().components().size());
    for (int draw = 0; draw < draws.length; draw++)
      for (int component = 0; component < draws[draw].length; component++)
        retained.set(draw, component, draws[draw][component]);
    return new Estimated(
        period.id(),
        segments,
        new Draws(
            period.id(),
            lastDay.date(),
            last.batch().components(),
            daySeed(period.id(), lastDay.date(), rules.seed()),
            ModelValues.owned(retained)),
        reproduction(period, fitted, parameters, rules));
  }

  /**
   * The day's joint draws, each transformed to percent shares on its own. Averaging in ilr space
   * and transforming once afterwards would report a different composition, so the transform comes
   * first and every summary below reads these rows.
   */
  static double[][] transformed(
      PollObservations.Batch batch,
      double[][] basis,
      String periodId,
      DailyStateSpace.Day day,
      Rules rules) {
    var factor = cholesky(day.smoothedCovariance(), periodId, day.date());
    var mean = day.smoothedMean().toArray();
    var random = RANDOM_FACTORY.create(daySeed(periodId, day.date(), rules.seed()));
    var draws = new double[rules.draws()][batch.components().size()];
    var normal = new double[mean.length];
    var state = new double[mean.length];
    for (int draw = 0; draw < draws.length; draw++) {
      for (int i = 0; i < normal.length; i++) normal[i] = random.nextGaussian();
      for (int i = 0; i < state.length; i++) {
        double value = mean[i];
        for (int j = 0; j <= i; j++) value += factor[i][j] * normal[j];
        state[i] = value;
      }
      PollObservations.close(basis, state, draws[draw], periodId);
    }
    return draws;
  }

  /** The mean and marginal intervals of one day, all read off its transformed draws. */
  private static Day summarize(
      PollObservations.Batch batch,
      double[][] basis,
      String periodId,
      DailyStateSpace.Day day,
      Rules rules) {
    var draws = transformed(batch, basis, periodId, day, rules);
    var state = new double[batch.components().size()];
    var mean = day.smoothedMean().toArray();
    PollObservations.close(basis, mean, state, periodId);
    var components = new ArrayList<Component>(state.length);
    var column = new double[draws.length];
    for (int component = 0; component < state.length; component++) {
      double total = 0;
      for (int draw = 0; draw < draws.length; draw++) {
        column[draw] = draws[draw][component];
        total += column[draw];
      }
      Arrays.sort(column);
      var intervals = new ArrayList<Interval>(rules.intervalLevels().size());
      for (double level : rules.intervalLevels())
        intervals.add(
            new Interval(
                level, quantile(column, (1 - level) / 2), quantile(column, (1 + level) / 2)));
      components.add(
          new Component(
              batch.components().get(component),
              total / draws.length,
              state[component],
              intervals));
    }
    return new Day(day.date(), components);
  }

  /** The order statistic at {@code h = (n-1)p}, interpolated linearly between its neighbours. */
  static double quantile(double[] sorted, double probability) {
    double position = (sorted.length - 1) * probability;
    int low = (int) Math.floor(position);
    int high = Math.min(low + 1, sorted.length - 1);
    return sorted[low] + (position - low) * (sorted[high] - sorted[low]);
  }

  /**
   * The lower Cholesky factor of the day's centered smoothed covariance. A covariance that is not
   * finite, symmetric and positive definite stops the run; there is no jitter and no clipping.
   */
  static double[][] cholesky(ModelValues covariance, String periodId, LocalDate date) {
    if (covariance.hasUncountable()
        || covariance.symmetryError() > 1e-12 * covariance.elementMaxAbs())
      throw new IllegalArgumentException(
          "Covariance of " + periodId + " on " + date + " must be finite and symmetric");
    int size = covariance.getNumRows();
    var decomposition = DecompositionFactory_DDRM.chol(size, true);
    if (!decomposition.decompose(covariance.matrixCopy()))
      throw new IllegalArgumentException(
          "Covariance of " + periodId + " on " + date + " is not positive definite");
    var lower = decomposition.getT(null);
    var factor = new double[size][size];
    for (int row = 0; row < size; row++)
      for (int column = 0; column <= row; column++) factor[row][column] = lower.get(row, column);
    return factor;
  }

  /**
   * Each day draws from its own stream, so its draws never depend on how many days a run computes
   * or on the order threads finish them.
   */
  private static long daySeed(String periodId, LocalDate date, long seed) {
    var digest = sha256((periodId + "|" + date + "|" + seed).getBytes(StandardCharsets.UTF_8));
    long value = 0;
    for (int byteIndex = 0; byteIndex < Long.BYTES; byteIndex++)
      value = (value << 8) | (digest[byteIndex] & 0xFF);
    return value;
  }

  private static Reproduction reproduction(
      Roster.CoveragePeriod period,
      EstimateHistory.Fitted fitted,
      DailyStateSpace.Parameters parameters,
      Rules rules) {
    var batch = fitted.spans().getFirst().batch();
    var rows = new StringBuilder();
    int count = 0;
    for (var span : fitted.spans())
      for (var observation : span.batch().observations()) {
        rows.append(observation.poll().rowNumber())
            .append(':')
            .append(hex(sha256(row(observation.poll()))))
            .append('\n');
        count++;
      }
    return new Reproduction(
        rules.seed(),
        rules.draws(),
        period.id(),
        batch.components(),
        hex(sha256(basis(batch).getBytes(StandardCharsets.UTF_8))),
        hex(sha256(rows.toString().getBytes(StandardCharsets.UTF_8))),
        count,
        parameters,
        hex(implementation()),
        Runtime.version().toString(),
        System.getProperty("java.vm.version"),
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        linearAlgebraVersion());
  }

  /** The archived row as it was parsed, its fields joined in source column order. */
  private static byte[] row(PollCsv.Poll poll) {
    return String.join(",", poll.raw().values()).getBytes(StandardCharsets.UTF_8);
  }

  /** The roster and the orthonormal basis the draws are transformed through. */
  private static String basis(PollObservations.Batch batch) {
    var text = new StringBuilder(String.join(",", batch.components()));
    for (int row = 0; row < batch.basis().getNumRows(); row++)
      for (int column = 0; column < batch.basis().getNumCols(); column++)
        text.append('\n').append(Double.toString(batch.basis().get(row, column)));
    return text.toString();
  }

  private static byte[] implementation() {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (var type : IMPLEMENTATION) {
        var resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var bytes = JointUncertainty.class.getResourceAsStream(resource)) {
          if (bytes == null)
            throw new IllegalStateException("No compiled bytecode for " + type.getName());
          digest.update(bytes.readAllBytes());
        }
      }
      return digest.digest();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The loaded jar's manifest where it carries one, otherwise the version compiled against. */
  private static String linearAlgebraVersion() {
    var version = SimpleMatrix.class.getPackage().getImplementationVersion();
    return version == null ? org.ejml.EjmlVersion.VERSION : version;
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  /**
   * Compares the retained draws of two runs of the same seed. A rerun that moves any draw at all
   * breaks the reproduction requirement, so the difference is reported rather than a tolerance.
   */
  public static Reproduced reproduced(Draws first, Draws second) {
    if (!first.periodId().equals(second.periodId())
        || !first.date().equals(second.date())
        || !first.components().equals(second.components())
        || first.daySeed() != second.daySeed()
        || first.count() != second.count())
      throw new IllegalArgumentException("These runs did not draw the same final day");
    double largest = 0;
    int compared = 0;
    for (int draw = 0; draw < first.count(); draw++)
      for (int component = 0; component < first.components().size(); component++) {
        largest =
            Math.max(
                largest,
                Math.abs(
                    first.shares().get(draw, component) - second.shares().get(draw, component)));
        compared++;
      }
    return new Reproduced(first.daySeed(), compared, largest);
  }

  /**
   * How far each mean and interval endpoint moves across runs that differ only in their seed. This
   * is the Monte Carlo precision of the historical intervals; the manifest resolves no tolerance
   * for it, so the spread is evidence for the eventual bound.
   */
  public static List<Precision> precision(List<Estimated> repeats) {
    if (repeats.size() < 2)
      throw new IllegalArgumentException("A precision study compares at least two runs");
    var first = repeats.getFirst();
    for (var repeat : repeats)
      if (!repeat.periodId().equals(first.periodId())
          || repeat.segments().size() != first.segments().size())
        throw new IllegalArgumentException("These runs did not estimate the same period");
    // One entry per component and level, holding the largest spread of the mean, the lower
    // endpoint and the upper endpoint over every compared day.
    var spreads = new LinkedHashMap<Summarized, double[]>();
    int days = 0;
    for (int segment = 0; segment < first.segments().size(); segment++)
      for (int day = 0; day < first.segments().get(segment).days().size(); day++) {
        days++;
        var reference = first.segments().get(segment).days().get(day).components();
        for (int component = 0; component < reference.size(); component++)
          for (int level = 0; level < reference.get(component).intervals().size(); level++) {
            var summarized =
                new Summarized(
                    reference.get(component).component(),
                    reference.get(component).intervals().get(level).level());
            var spread = spreads.computeIfAbsent(summarized, absent -> new double[3]);
            var means = new double[repeats.size()];
            var lowers = new double[repeats.size()];
            var uppers = new double[repeats.size()];
            for (int repeat = 0; repeat < repeats.size(); repeat++) {
              var summary =
                  repeats
                      .get(repeat)
                      .segments()
                      .get(segment)
                      .days()
                      .get(day)
                      .components()
                      .get(component);
              means[repeat] = summary.mean();
              lowers[repeat] = summary.intervals().get(level).lower();
              uppers[repeat] = summary.intervals().get(level).upper();
            }
            spread[0] = Math.max(spread[0], range(means));
            spread[1] = Math.max(spread[1], range(lowers));
            spread[2] = Math.max(spread[2], range(uppers));
          }
      }
    var precision = new ArrayList<Precision>();
    for (var spread : spreads.entrySet())
      precision.add(
          new Precision(
              spread.getKey().component(),
              spread.getKey().level(),
              spread.getValue()[0],
              spread.getValue()[1],
              spread.getValue()[2],
              days,
              repeats.size()));
    return List.copyOf(precision);
  }

  private record Summarized(String component, double level) {}

  private static double range(double[] values) {
    return Arrays.stream(values).max().orElseThrow() - Arrays.stream(values).min().orElseThrow();
  }

  /**
   * Draws every validated period whose coverage evidence passed, reruns the registered seed to
   * check reproduction and repeats the run over the given seeds to measure interval precision. The
   * coverage gate carries in whole, so no number here is a release value.
   */
  public static Report report(
      List<Roster.CoveragePeriod> periods,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      CoverageValidation.Report coverage,
      Rules rules,
      List<Long> seeds) {
    if (seeds.isEmpty() || seeds.getFirst() != rules.seed())
      throw new IllegalArgumentException("A run starts from the registered seed");
    var evidence = new LinkedHashMap<String, CoverageValidation.Validated>();
    for (var validated : coverage.periods()) evidence.put(validated.periodId(), validated);
    var reasons = new ArrayList<>(coverage.gate().reasons());
    var published = new ArrayList<Published>();
    var tolerances = new ArrayList<ProposedTolerance>();
    for (var period : periods) {
      if (!period.supportValidated()) continue;
      var validated = evidence.get(period.id());
      if (validated == null)
        throw new IllegalArgumentException("No recorded coverage evidence for " + period.id());
      if (!validated.supported()) {
        reasons.add(period.id() + ": coverage evidence failed, so no draws are published");
        continue;
      }
      var repeats = new ArrayList<Estimated>();
      for (long seed : seeds)
        repeats.add(
            estimate(
                period,
                polls,
                elections,
                validated.parameters(),
                coverage.rules(),
                rules.withSeed(seed)));
      var run = repeats.getFirst();
      var rerun =
          estimate(period, polls, elections, validated.parameters(), coverage.rules(), rules);
      var reproduced = reproduced(run.finalDraws(), rerun.finalDraws());
      if (!reproduced.exact())
        reasons.add(
            period.id()
                + ": a rerun at the registered seed moved a retained draw by "
                + reproduced.maxAbsoluteDifference()
                + " points, so the run does not reproduce");
      var precision = repeats.size() > 1 ? precision(repeats) : List.<Precision>of();
      var edges = new ArrayList<Day>();
      int days = 0;
      double shift = 0;
      for (var segment : run.segments()) {
        edges.add(segment.days().getFirst());
        edges.add(segment.days().getLast());
        days += segment.days().size();
        for (var day : segment.days())
          for (var component : day.components())
            shift = Math.max(shift, Math.abs(component.mean() - component.stateMean()));
      }
      published.add(
          new Published(
              period.id(),
              run.reproduction(),
              run.segments().getLast().days().getLast(),
              edges,
              days,
              shift,
              reproduced,
              precision));
      tolerances.add(
          new ProposedTolerance(
              period.id() + ":seeded_reproduction",
              "percentage points between retained draws of two runs at the same seed",
              reproduced.maxAbsoluteDifference(),
              reproduced.comparedValues(),
              List.of(rules.seed()),
              "A rerun of the same seed on the same implementation must return every draw"
                  + " unchanged, so the proposed bound is exactly zero. Cross-architecture reruns"
                  + " are still owed before this becomes a resolved tolerance."));
      if (!precision.isEmpty())
        tolerances.add(
            new ProposedTolerance(
                period.id() + ":interval_endpoint_precision",
                "percentage points between the extreme interval endpoints of runs differing only"
                    + " in seed",
                precision.stream()
                    .mapToDouble(
                        measured ->
                            Math.max(measured.lowerSpreadPoints(), measured.upperSpreadPoints()))
                    .max()
                    .orElse(0),
                precision.getFirst().comparedDays() * precision.size(),
                seeds,
                "The Monte Carlo error of a published interval endpoint at "
                    + rules.draws()
                    + " draws per day. It bounds how much of a reported interval is sampling noise"
                    + " rather than estimated uncertainty, and it is measured, never enlarged"
                    + " afterwards."));
    }
    return new Report(
        coverage.protocolVersion(),
        new CoverageValidation.Gate(!reasons.isEmpty(), reasons),
        rules,
        published,
        tolerances);
  }

  public static String report(Report report) {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
  }
}

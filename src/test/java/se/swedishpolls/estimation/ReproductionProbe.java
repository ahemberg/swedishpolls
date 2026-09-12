package se.swedishpolls.estimation;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;

/** Prints a digest of the final development-day draws for cross-architecture comparison. */
public final class ReproductionProbe {
  private ReproductionProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 3 && args[0].equals("--compare")) {
      compare(Path.of(args[1]), Path.of(args[2]));
      return;
    }
    final java.nio.file.Path protocol = Path.of("docs", "validation", "protocol.json");
    final se.swedishpolls.estimation.CoverageValidation.Report coverage =
        CoverageValidation.validation(Path.of("docs", "validation", "coverage.json"));
    final se.swedishpolls.source.Roster.CoveragePeriod period =
        new Roster.CoveragePeriod(
            "eight_party_2010",
            LocalDate.of(2010, 1, 1),
            null,
            PollCsv.PARTIES,
            false,
            true,
            "https://github.com/ahemberg/swedishpolls/issues/12#issuecomment-5575789888");
    final se.swedishpolls.estimation.CoverageValidation.Validated validated =
        coverage.periods().stream()
            .filter(candidate -> candidate.periodId().equals(period.id()))
            .findFirst()
            .orElseThrow();
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
        PollCsv.parse(
            Files.readAllBytes(Path.of("src", "test", "resources", "polls", "audit.csv")));
    final se.swedishpolls.estimation.EstimateHistory.Fitted fitted =
        EstimateHistory.fitted(
            period,
            polls,
            List.of(
                LocalDate.of(2010, 9, 19),
                LocalDate.of(2014, 9, 14),
                LocalDate.of(2018, 9, 9),
                LocalDate.of(2022, 9, 11)),
            validated.parameters(),
            coverage.rules());
    final se.swedishpolls.estimation.EstimateHistory.Span span = fitted.spans().getLast();
    final se.swedishpolls.estimation.DailyStateSpace.Day day = span.fit().days().getLast();
    final double[][] draws =
        JointUncertainty.transformed(
            span.batch(),
            PollObservations.transposedBasis(span.batch()),
            period.id(),
            day,
            JointUncertainty.rules(protocol));
    final java.security.MessageDigest digest = MessageDigest.getInstance("SHA-256");
    final java.nio.ByteBuffer bytes = ByteBuffer.allocate(Double.BYTES);
    for (double[] draw : draws)
      for (double value : draw) {
        bytes.clear();
        bytes.putLong(Double.doubleToLongBits(value));
        digest.update(bytes.array());
      }
    if (args.length == 1)
      try (final java.io.DataOutputStream output =
          new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[0]))))) {
        for (double[] draw : draws) for (double value : draw) output.writeDouble(value);
      }
    System.out.println(
        System.getProperty("os.arch")
            + "\t"
            + Runtime.version()
            + "\t"
            + draws.length * draws[0].length
            + "\t"
            + HexFormat.of().formatHex(digest.digest()));
  }

  private static void compare(Path first, Path second) throws Exception {
    final java.nio.ByteBuffer left = ByteBuffer.wrap(Files.readAllBytes(first));
    final java.nio.ByteBuffer right = ByteBuffer.wrap(Files.readAllBytes(second));
    if (left.remaining() != right.remaining() || left.remaining() % Double.BYTES != 0)
      throw new IllegalArgumentException("Draw files differ in size");
    long compared = 0;
    long different = 0;
    double maximum = 0;
    while (left.hasRemaining()) {
      final double firstValue = left.getDouble();
      final double secondValue = right.getDouble();
      compared++;
      if (Double.doubleToLongBits(firstValue) != Double.doubleToLongBits(secondValue)) different++;
      maximum = Math.max(maximum, Math.abs(firstValue - secondValue));
    }
    System.out.println(compared + "\t" + different + "\t" + maximum);
  }
}

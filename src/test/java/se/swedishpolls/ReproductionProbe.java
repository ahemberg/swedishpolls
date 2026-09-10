package se.swedishpolls;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/** Prints a digest of the final development-day draws for cross-architecture comparison. */
public final class ReproductionProbe {
  private ReproductionProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 3 && args[0].equals("--compare")) {
      compare(Path.of(args[1]), Path.of(args[2]));
      return;
    }
    var protocol = Path.of("docs", "validation", "protocol.json");
    var coverage = CoverageValidation.validation(Path.of("docs", "validation", "coverage.json"));
    var period =
        new Roster.CoveragePeriod(
            "eight_party_2010",
            LocalDate.of(2010, 1, 1),
            null,
            PollCsv.PARTIES,
            false,
            true,
            "https://github.com/ahemberg/swedishpolls/issues/12#issuecomment-5575789888");
    var validated =
        coverage.periods().stream()
            .filter(candidate -> candidate.periodId().equals(period.id()))
            .findFirst()
            .orElseThrow();
    var polls =
        PollCsv.parse(
            Files.readAllBytes(Path.of("src", "test", "resources", "polls", "audit.csv")));
    var fitted =
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
    var span = fitted.spans().getLast();
    var day = span.fit().days().getLast();
    var draws =
        JointUncertainty.transformed(
            span.batch(),
            PollObservations.transposedBasis(span.batch()),
            period.id(),
            day,
            JointUncertainty.rules(protocol));
    var digest = MessageDigest.getInstance("SHA-256");
    var bytes = ByteBuffer.allocate(Double.BYTES);
    for (var draw : draws)
      for (double value : draw) {
        bytes.clear();
        bytes.putLong(Double.doubleToLongBits(value));
        digest.update(bytes.array());
      }
    if (args.length == 1)
      try (var output =
          new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[0]))))) {
        for (var draw : draws) for (double value : draw) output.writeDouble(value);
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
    var left = ByteBuffer.wrap(Files.readAllBytes(first));
    var right = ByteBuffer.wrap(Files.readAllBytes(second));
    if (left.remaining() != right.remaining() || left.remaining() % Double.BYTES != 0)
      throw new IllegalArgumentException("Draw files differ in size");
    long compared = 0;
    long different = 0;
    double maximum = 0;
    while (left.hasRemaining()) {
      double firstValue = left.getDouble();
      double secondValue = right.getDouble();
      compared++;
      if (Double.doubleToLongBits(firstValue) != Double.doubleToLongBits(secondValue)) different++;
      maximum = Math.max(maximum, Math.abs(firstValue - secondValue));
    }
    System.out.println(compared + "\t" + different + "\t" + maximum);
  }
}

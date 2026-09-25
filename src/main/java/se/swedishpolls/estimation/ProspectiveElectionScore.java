package se.swedishpolls.estimation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.swedishpolls.model.NationalAllocationRule;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Executes the outcome-blind 2026 scoring registration. */
public final class ProspectiveElectionScore {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<LocalDate> PRIOR_ELECTIONS =
      List.of(
          LocalDate.of(2010, 9, 19),
          LocalDate.of(2014, 9, 14),
          LocalDate.of(2018, 9, 9),
          LocalDate.of(2022, 9, 11));

  private ProspectiveElectionScore() {}

  record Outcome(Map<String, String> shares, Map<String, Integer> seats) {
    Outcome {
      shares = Collections.unmodifiableMap(new LinkedHashMap<>(shares));
      seats = Collections.unmodifiableMap(new LinkedHashMap<>(seats));
    }
  }

  private record MethodScore(String id, String resultStatus, String reason, JsonNode score) {}

  private record Report(
      String registrationVersion,
      String holdoutSha256,
      String officialResultSha256,
      List<MethodScore> methods) {}

  public static void main(String[] args) {
    if (args.length != 6 || !"execute".equals(args[0])) {
      throw new IllegalArgumentException(
          "Usage: execute METHOD REGISTRATION HOLDOUT OFFICIAL_RESULT OUTPUT");
    }
    execute(args[1], Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]));
  }

  private static void execute(
      String methodId, Path registrationFile, Path holdoutFile, Path outcomeFile, Path outputFile) {
    try {
      final byte[] registrationBytes = Files.readAllBytes(registrationFile);
      final byte[] holdoutBytes = Files.readAllBytes(holdoutFile);
      final byte[] outcomeBytes = Files.readAllBytes(outcomeFile);
      final JsonNode registration = JSON.readTree(registrationBytes);
      final JsonNode holdout = required(registration, "holdout");
      final LocalDate cutoff = LocalDate.parse(required(holdout, "cutoff").asString());
      final LocalDate election = LocalDate.parse(required(holdout, "electionDate").asString());
      final String holdoutHash = sha256(holdoutBytes);
      requireEqual(required(holdout, "sha256").asString(), holdoutHash, "holdout SHA-256");

      final Outcome outcome = outcome(outcomeBytes);
      final String outcomeHash = sha256(outcomeBytes);
      final List<PollCsv.Poll> candidates =
          PollCsv.parse(holdoutBytes).stream()
              .filter(PollCsv.Poll::publicationTimeEligible)
              .filter(poll -> poll.collectionFrom() != null && poll.collectionTo() != null)
              .filter(poll -> !poll.collectionFrom().isBefore(LocalDate.of(2010, 1, 1)))
              .filter(poll -> !poll.collectionFrom().isAfter(poll.collectionTo()))
              .filter(poll -> !poll.publicationDate().isAfter(cutoff))
              .filter(poll -> !poll.collectionTo().isAfter(cutoff))
              .toList();
      final ReleaseAudit.Selection selection = selection(holdoutBytes, candidates);
      final Roster.CoveragePeriod period =
          new Roster.CoveragePeriod(
              "eight_party_2010",
              LocalDate.of(2010, 1, 1),
              null,
              PollCsv.PARTIES,
              false,
              true,
              "https://github.com/ahemberg/swedishpolls/issues/12#issuecomment-5575789888");
      final CoverageValidation.Rules baseRules =
          CoverageValidation.rules(Path.of("docs", "validation", "protocol.json"));
      final CoverageValidation.Rules coverage =
          new CoverageValidation.Rules(
              cutoff,
              baseRules.minObservations(),
              baseRules.minInstitutes(),
              baseRules.maxInternalGapDays(),
              new ArrayList<>(baseRules.boundaryShiftDays()),
              baseRules.stabilityBurnInDays(),
              baseRules.maxStabilityShiftPoints());
      JsonNode selected = null;
      for (final JsonNode method : required(registration, "methods"))
        if (methodId.equals(required(method, "id").asString())) selected = method;
      if (selected == null) throw new IllegalArgumentException("Unregistered method " + methodId);
      final MethodScore score =
          score(
              selected,
              registration,
              selection,
              period,
              candidates,
              cutoff,
              election,
              coverage,
              outcome,
              outcomeHash);
      final Report report =
          new Report(
              required(registration, "version").asString(),
              holdoutHash,
              outcomeHash,
              List.of(score));
      Files.writeString(
          outputFile,
          JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static MethodScore score(
      JsonNode method,
      JsonNode registration,
      ReleaseAudit.Selection selection,
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> candidates,
      LocalDate cutoff,
      LocalDate election,
      CoverageValidation.Rules coverage,
      Outcome outcome,
      String outcomeHash) {
    final JsonNode parameters = required(method, "parameters");
    final DailyStateSpace.Parameters model =
        new DailyStateSpace.Parameters(
            required(parameters, "walkVariance").doubleValue(),
            required(parameters, "houseScale").doubleValue(),
            required(parameters, "covarianceMultiplier").doubleValue());
    final JointUncertainty.Rules uncertainty =
        new JointUncertainty.Rules(
            required(method, "seed").longValue(),
            required(method, "draws").intValue(),
            List.of(0.5, 0.95),
            2);
    final EstimateHistory.Fitted fitted =
        EstimateHistory.fitted(period, candidates, PRIOR_ELECTIONS, model, coverage);
    final JointUncertainty.FinalDay finalDay =
        JointUncertainty.finalDay(
            period, candidates, PRIOR_ELECTIONS, model, coverage, uncertainty);
    final JointUncertainty.FinalDay repeated =
        JointUncertainty.finalDay(
            period, candidates, PRIOR_ELECTIONS, model, coverage, uncertainty);
    final JsonNode identity = required(method, "identity");
    final JsonNode registeredImplementation = identity.get("implementationSha256");
    if (registeredImplementation != null) {
      requireEqual(
          registeredImplementation.asString(),
          finalDay.reproduction().implementationSha256(),
          "compiled implementation SHA-256");
    }
    final Map<String, Double> composition = new LinkedHashMap<>();
    for (final JointUncertainty.Component component : finalDay.day().components()) {
      composition.put(component.component(), component.mean());
    }
    final NationalSeats.Allocation approximate =
        NationalSeats.allocate(composition, allocationRule(election.getYear()));
    final ReleaseAudit.Manifest manifest =
        new ReleaseAudit.Manifest(
            cutoff,
            election,
            required(registration, "version").asString(),
            required(required(registration, "holdout"), "sha256").asString(),
            "physical CSV line",
            required(required(registration, "holdout"), "rule").asString(),
            selection.rows(),
            selection.rowsSha256(),
            required(required(registration, "outcome"), "sourceUrl").asString(),
            required(registration, "version").asString(),
            "official-result.json",
            outcomeHash,
            outcome.shares());
    final ReleaseAudit.Election scored =
        ReleaseAudit.compare(
            manifest,
            selection,
            finalDay,
            fitted.spans().stream().map(span -> span.fit().logLikelihood()).toList(),
            fitted.spans().stream().mapToInt(span -> span.batch().observations().size()).sum(),
            approximate,
            outcome.seats(),
            JointUncertainty.reproduced(finalDay.draws(), repeated.draws()));
    final ObjectNode prospective = (ObjectNode) JSON.valueToTree(scored);
    prospective.put(
        "interpretation",
        "A prospective score against the result first opened after this method, holdout,"
            + " outcome template and execution were registered. The revised method remains"
            + " non-qualifying because it was registered after the holdout cutoff.");
    return new MethodScore(
        required(method, "id").asString(),
        required(method, "resultStatus").asString(),
        required(method, "reason").asString(),
        prospective);
  }

  static Outcome outcome(byte[] bytes) {
    final JsonNode result = JSON.readTree(bytes);
    if (!"slutlig".equals(required(result, "rakningstillfalle").asString())
        || required(result, "antalValdistriktRaknade").intValue()
            != required(result, "antalValdistriktSomSkaRaknas").intValue()) {
      throw new IllegalArgumentException("The official national result is not final and complete");
    }
    final Map<String, String> shares = new LinkedHashMap<>();
    for (final JsonNode party : required(required(result, "rosterPaverkaMandat"), "partiroster")) {
      final JsonNode abbreviation = party.get("partiforkortning");
      if (abbreviation == null || abbreviation.isNull()) continue;
      final String component = abbreviation.asString();
      if (PollCsv.PARTIES.contains(component)) {
        final BigDecimal share =
            new BigDecimal(required(party, "andelRoster").asString())
                .setScale(2, RoundingMode.UNNECESSARY);
        shares.put(component, share.toPlainString());
      }
    }
    if (!shares.keySet().containsAll(PollCsv.PARTIES) || shares.size() != PollCsv.PARTIES.size()) {
      throw new IllegalArgumentException("The official result does not contain the eight parties");
    }
    BigDecimal named = BigDecimal.ZERO;
    for (final String component : PollCsv.PARTIES) {
      named = named.add(new BigDecimal(shares.get(component)));
    }
    shares.put("OTHER", new BigDecimal("100.00").subtract(named).toPlainString());

    final Map<String, Integer> seats = new LinkedHashMap<>();
    for (final JsonNode party : required(result, "partiMandat")) {
      final String component = required(party, "partiforkortning").asString();
      if (component.isBlank()
          || seats.put(component, required(party, "antalMandat").intValue()) != null) {
        throw new IllegalArgumentException("The official allocation has an invalid party key");
      }
    }
    if (!seats.keySet().containsAll(PollCsv.PARTIES)
        || seats.values().stream().mapToInt(Integer::intValue).sum() != 349) {
      throw new IllegalArgumentException(
          "The official allocation is not a complete 349-seat result");
    }
    return new Outcome(shares, seats);
  }

  private static ReleaseAudit.Selection selection(byte[] source, List<PollCsv.Poll> candidates) {
    final List<String> rows = new String(source, StandardCharsets.UTF_8).lines().toList();
    final List<Integer> lines = candidates.stream().map(poll -> poll.rowNumber() + 1).toList();
    final StringBuilder identities = new StringBuilder();
    for (final int line : lines) {
      if (!identities.isEmpty()) identities.append('\n');
      identities
          .append(line)
          .append(':')
          .append(sha256(rows.get(line - 1).getBytes(StandardCharsets.UTF_8)));
    }
    return new ReleaseAudit.Selection(
        lines.size(), sha256(identities.toString().getBytes(StandardCharsets.UTF_8)), lines);
  }

  private static NationalAllocationRule allocationRule(int year) {
    return new NationalAllocationRule(
        year,
        349,
        4,
        true,
        1.2,
        "2 * seats_already_allocated + 1",
        List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "FI"),
        false,
        false,
        "lottery",
        "https://www.val.se/download/18.162047b519a91d05331183a9/1761747515752/manual-mandatfordelning-val-v785-05.pdf");
  }

  private static JsonNode required(JsonNode parent, String field) {
    final JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("Incomplete scoring input: missing " + field);
    }
    return value;
  }

  private static void requireEqual(String expected, String actual, String name) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException(name + " does not match the registration");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

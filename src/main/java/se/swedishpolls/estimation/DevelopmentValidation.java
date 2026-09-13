package se.swedishpolls.estimation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Prepares and checks the frozen inputs for the development-only v2 validation run. */
public final class DevelopmentValidation {
  public static final int SUCCESS = 0;
  public static final int REJECTED = 2;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String FROZEN = "frozen";

  private DevelopmentValidation() {}

  public static void main(String[] args) {
    System.exit(run(args));
  }

  /**
   * The operator entry point. Preparation never fits; preflight never creates the evidence path.
   */
  public static int run(String... args) {
    if (args.length != 4 || (!args[0].equals("prepare") && !args[0].equals("preflight"))) {
      System.err.println(
          "Usage: prepare <plan.json> <source.csv> <registration.json> | preflight"
              + " <registration.json> <source.csv> <result.json>");
      return REJECTED;
    }
    final Path first = Path.of(args[1]);
    final Path source = Path.of(args[2]);
    final Path output = Path.of(args[3]);
    try {
      if (args[0].equals("prepare")) prepare(first, source, output);
      else preflight(first, source, output);
      return SUCCESS;
    } catch (RuntimeException e) {
      rejected(output, e.getMessage());
      System.err.println(e.getMessage());
      return REJECTED;
    }
  }

  private static void prepare(Path planFile, Path sourceFile, Path registrationFile) {
    refuseExisting(registrationFile);
    refuseExisting(checksum(registrationFile));
    final JsonNode plan = read(planFile);
    require(
        required(plan, "version").asString().startsWith("v2")
            || required(plan, "version").asString().startsWith("test-"),
        "Development registration must be versioned");
    verifySource(plan, sourceFile);
    verifyIdentities(plan);
    verifyEnvironment(plan);
    verifyOutputLocation(plan);
    grid(plan);
    final ArrayNode folds = manifests(plan, sourceFile);
    compareArchived(plan, folds);

    final ObjectNode registration = JSON.createObjectNode();
    registration.put("status", FROZEN);
    registration.put("version", required(plan, "version").asString());
    registration.put("registeredOn", required(plan, "registeredOn").asString());
    final ObjectNode planIdentity = registration.putObject("planIdentity");
    planIdentity.put("path", planFile.toString());
    planIdentity.put("sha256", digest(planFile));
    registration.set("plan", plan);
    registration.set("folds", folds);
    registration.put("fitEvidence", "not_run");
    final byte[] bytes = pretty(registration);
    writeNew(registrationFile, bytes);
    writeNew(
        checksum(registrationFile),
        (sha256(bytes) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
  }

  private static void preflight(Path registrationFile, Path sourceFile, Path resultFile) {
    refuseExisting(resultFile);
    final byte[] registrationBytes = bytes(registrationFile);
    final String expected = text(checksum(registrationFile)).trim();
    require(expected.equals(sha256(registrationBytes)), "Registration checksum mismatch");
    final JsonNode registration = tree(registrationBytes, registrationFile);
    require(
        FROZEN.equals(required(registration, "status").asString()), "Registration is not frozen");
    final JsonNode plan = required(registration, "plan");
    verifyPlanIdentity(registration);
    verifySource(plan, sourceFile);
    verifyIdentities(plan);
    verifyEnvironment(plan);
    verifyOutputLocation(plan);
    grid(plan);
    final ArrayNode rebuilt = manifests(plan, sourceFile);
    require(rebuilt.equals(required(registration, "folds")), "Eligibility row manifest mismatch");
    compareArchived(plan, rebuilt);

    final ObjectNode result = JSON.createObjectNode();
    result.put("status", "ready");
    result.put("protocolVersion", required(registration, "version").asString());
    result.put("registrationSha256", sha256(registrationBytes));
    result.put("sourceSha256", digest(sourceFile));
    result.put("folds", rebuilt.size());
    result.put(
        "activeFolds",
        rebuilt.valueStream().filter(row -> row.get("active").booleanValue()).count());
    result.put("fitEvidence", "not_run");
    result.putArray("reasons");
    writeNew(resultFile, pretty(result));
  }

  private static ArrayNode manifests(JsonNode plan, Path sourceFile) {
    final List<PollCsv.Poll> polls = PollCsv.parse(bytes(sourceFile));
    final List<DevelopmentTuning.Fold> folds = folds(plan);
    final ArrayNode manifests = JSON.createArrayNode();
    for (JsonNode declared : required(plan, "periods")) {
      final Roster.CoveragePeriod period = period(declared);
      final LocalDate activeFrom = LocalDate.parse(required(declared, "activeFrom").asString());
      final LocalDate activeThrough =
          LocalDate.parse(required(declared, "activeThrough").asString());
      for (DevelopmentTuning.Fold fold : folds) {
        final boolean active =
            !fold.cutoff().isBefore(activeFrom) && !fold.cutoff().isAfter(activeThrough);
        manifests.add(manifest(period, polls, fold, active));
      }
    }
    return manifests;
  }

  private static ObjectNode manifest(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      DevelopmentTuning.Fold fold,
      boolean active) {
    final List<PollCsv.Poll> training = DevelopmentTuning.training(polls, fold);
    final PollObservations.Batch preparedTraining = PollObservations.prepare(period, training);
    final List<PollCsv.Poll> heldOut =
        DevelopmentDiagnostics.heldOut(
            polls,
            fold,
            new DevelopmentDiagnostics.Rules(
                1,
                35,
                10,
                List.of(3),
                List.of(1),
                List.of(1),
                List.of(0.9, 0.98),
                List.of(0.4, 0.6)));
    final PollObservations.Batch preparedHeldOut = PollObservations.prepare(period, heldOut);
    if (active) {
      require(
          !preparedTraining.observations().isEmpty(),
          "Unexpected empty active training fold " + period.id() + " " + fold.cutoff());
      require(
          !preparedHeldOut.observations().isEmpty(),
          "Unexpected empty active scoring fold " + period.id() + " " + fold.cutoff());
    }
    final ObjectNode row = JSON.createObjectNode();
    row.put("periodId", period.id());
    row.put("cutoff", fold.cutoff().toString());
    row.put("scoreThrough", fold.scoreThrough().toString());
    row.put("active", active);
    if (!active)
      row.put(
          "reason",
          preparedTraining.observations().isEmpty()
              ? "no_eligible_training_observation"
              : "no_held_out_composition");
    rows(row.putArray("trainingRows"), training);
    row.put("trainingRowsSha256", rowsSha256(training));
    final List<PollCsv.Poll> trainingObservations =
        preparedTraining.observations().stream().map(PollObservations.Observation::poll).toList();
    rows(row.putArray("trainingObservationRows"), trainingObservations);
    row.put("trainingObservationRowsSha256", rowsSha256(trainingObservations));
    exclusions(row.putArray("trainingExclusions"), preparedTraining.exclusions());
    rows(row.putArray("scoringCandidateRows"), heldOut);
    final List<PollCsv.Poll> scoring =
        preparedHeldOut.observations().stream().map(PollObservations.Observation::poll).toList();
    rows(row.putArray("scoringRows"), scoring);
    row.put("scoringRowsSha256", rowsSha256(scoring));
    exclusions(row.putArray("scoringExclusions"), preparedHeldOut.exclusions());
    return row;
  }

  private static void rows(ArrayNode target, List<PollCsv.Poll> polls) {
    for (PollCsv.Poll poll : polls) target.add(poll.rowNumber());
  }

  private static void exclusions(ArrayNode target, List<PollObservations.Exclusion> exclusions) {
    for (PollObservations.Exclusion exclusion : exclusions) {
      final ObjectNode row = target.addObject();
      row.put("row", exclusion.rowNumber());
      final ArrayNode reasons = row.putArray("reasons");
      for (String reason : exclusion.reasons()) reasons.add(reason);
    }
  }

  private static Roster.CoveragePeriod period(JsonNode declared) {
    final boolean individualFi = required(declared, "individualFi").booleanValue();
    final List<String> roster =
        individualFi
            ? List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "FI")
            : List.of("S", "M", "SD", "V", "C", "KD", "L", "MP");
    final JsonNode to = required(declared, "to");
    return new Roster.CoveragePeriod(
        required(declared, "id").asString(),
        LocalDate.parse(required(declared, "from").asString()),
        to.isNull() ? null : LocalDate.parse(to.asString()),
        roster,
        individualFi,
        !individualFi,
        "development-validation-registration");
  }

  private static List<DevelopmentTuning.Fold> folds(JsonNode plan) {
    final List<DevelopmentTuning.Fold> folds = new ArrayList<>();
    final JsonNode declaredFolds;
    final boolean inherited;
    if (plan.has("folds")) {
      declaredFolds = required(plan, "folds");
      inherited = false;
    } else {
      declaredFolds =
          required(read(Path.of(required(plan, "foldsFrom").asString())), "development_folds");
      inherited = true;
    }
    for (JsonNode declared : declaredFolds) {
      final DevelopmentTuning.Fold fold =
          new DevelopmentTuning.Fold(
              LocalDate.parse(required(declared, "cutoff").asString()),
              LocalDate.parse(
                  required(declared, inherited ? "score_through" : "scoreThrough").asString()));
      require(
          fold.scoreThrough().equals(fold.cutoff().plusDays(35)),
          "Every fold must use the registered 35-day horizon");
      folds.add(fold);
    }
    require(!folds.isEmpty(), "No development folds registered");
    return List.copyOf(folds);
  }

  private static DevelopmentTuning.Grid grid(JsonNode plan) {
    final JsonNode grid = required(plan, "grid");
    return new DevelopmentTuning.Grid(
        doubles(grid, "walkVariances"),
        doubles(grid, "houseScales"),
        doubles(grid, "covarianceMultipliers"));
  }

  private static List<Double> doubles(JsonNode parent, String field) {
    final List<Double> values = new ArrayList<>();
    for (JsonNode value : required(parent, field)) values.add(value.doubleValue());
    return List.copyOf(values);
  }

  private static void compareArchived(JsonNode plan, ArrayNode manifests) {
    final JsonNode archivedPath = plan.get("archivedDiagnostics");
    if (archivedPath == null || archivedPath.isNull()) return;
    final JsonNode archived = read(Path.of(archivedPath.asString()));
    final Map<String, JsonNode> expected = new LinkedHashMap<>();
    for (JsonNode fold : required(archived, "folds")) expected.put(key(fold), fold);
    for (JsonNode manifest : manifests) {
      if (!manifest.get("active").booleanValue()) continue;
      final JsonNode old = expected.get(key(manifest));
      require(old != null, "Active fold missing from archived diagnostics: " + key(manifest));
      require(
          old.get("trainingRowsSha256").equals(manifest.get("trainingRowsSha256")),
          "Active training rows differ from archived diagnostics: " + key(manifest));
      require(
          old.get("scoredRowsSha256").equals(manifest.get("scoringRowsSha256")),
          "Active scoring rows differ from archived diagnostics: " + key(manifest));
    }
  }

  private static String key(JsonNode fold) {
    return required(fold, "periodId").asString() + "|" + required(fold, "cutoff").asString();
  }

  private static void verifyPlanIdentity(JsonNode registration) {
    final JsonNode identity = required(registration, "planIdentity");
    verifyIdentity(identity, "Plan");
  }

  private static void verifySource(JsonNode plan, Path supplied) {
    final JsonNode source = required(plan, "source");
    final Path registered = Path.of(required(source, "path").asString());
    require(
        registered.toAbsolutePath().normalize().equals(supplied.toAbsolutePath().normalize()),
        "Supplied source path differs from registration");
    require(
        required(source, "sha256").asString().equals(digest(supplied)), "Source SHA-256 mismatch");
  }

  private static void verifyIdentities(JsonNode plan) {
    for (JsonNode identity : required(plan, "identities")) verifyIdentity(identity, "Input");
  }

  private static void verifyIdentity(JsonNode identity, String kind) {
    final Path path = Path.of(required(identity, "path").asString());
    require(Files.isRegularFile(path), kind + " identity is missing: " + path);
    require(
        required(identity, "sha256").asString().equals(digest(path)),
        kind + " identity mismatch: " + path);
  }

  private static void verifyEnvironment(JsonNode plan) {
    final JsonNode environment = required(plan, "environment");
    environment(environment, "javaVersion", "java.version");
    environment(environment, "osName", "os.name");
    environment(environment, "osArch", "os.arch");
  }

  private static void environment(JsonNode registered, String field, String property) {
    require(
        required(registered, field).asString().equals(System.getProperty(property)),
        "Environment identity mismatch: " + field);
  }

  private static void verifyOutputLocation(JsonNode plan) {
    final Path output =
        Path.of(required(plan, "outputLocation").asString()).toAbsolutePath().normalize();
    require(!Files.exists(output), "Registered evidence output already exists: " + output);
    for (JsonNode protectedLocation : required(plan, "protectedLocations")) {
      final Path protectedPath = Path.of(protectedLocation.asString()).toAbsolutePath().normalize();
      require(
          !output.startsWith(protectedPath) && !protectedPath.startsWith(output),
          "Registered evidence output overlaps protected path " + protectedPath);
    }
  }

  private static String rowsSha256(List<PollCsv.Poll> polls) {
    final StringBuilder rows = new StringBuilder();
    for (PollCsv.Poll poll : polls)
      rows.append(poll.rowNumber())
          .append(':')
          .append(sha256(String.join(",", poll.raw().values()).getBytes(StandardCharsets.UTF_8)))
          .append('\n');
    return sha256(rows.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void rejected(Path output, String reason) {
    if (Files.exists(output)) return;
    final ObjectNode result = JSON.createObjectNode();
    result.put("status", "rejected");
    result.putArray("reasons").add(reason == null ? "Validation failed" : reason);
    result.put("fitEvidence", "not_run");
    try {
      writeNew(output, pretty(result));
    } catch (RuntimeException ignored) {
      // A rejection must never overwrite or obscure the original failure.
    }
  }

  private static void refuseExisting(Path path) {
    require(!Files.exists(path), "Output already exists: " + path);
  }

  private static Path checksum(Path registration) {
    return registration.resolveSibling(registration.getFileName() + ".sha256");
  }

  private static JsonNode required(JsonNode parent, String field) {
    final JsonNode value = parent == null ? null : parent.get(field);
    if (value == null) throw new IllegalArgumentException("Missing registration field " + field);
    return value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  private static JsonNode read(Path file) {
    return tree(bytes(file), file);
  }

  private static JsonNode tree(byte[] bytes, Path file) {
    try {
      return JSON.readTree(bytes);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid JSON in " + file, e);
    }
  }

  private static byte[] pretty(JsonNode value) {
    return (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)
            + System.lineSeparator())
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bytes(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String text(Path file) {
    return new String(bytes(file), StandardCharsets.UTF_8);
  }

  private static String digest(Path file) {
    return sha256(bytes(file));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void writeNew(Path path, byte[] bytes) {
    try {
      final Path parent = path.toAbsolutePath().normalize().getParent();
      if (parent != null) Files.createDirectories(parent);
      Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

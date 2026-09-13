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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private static final String VERSION = "v2-development-1";

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
    final Path registrationInput = Path.of(args[1]);
    final Path source = Path.of(args[2]);
    final Path output = Path.of(args[3]);
    try {
      if (args[0].equals("prepare")) prepare(registrationInput, source, output);
      else preflight(registrationInput, source, output);
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
    verifyProtocol(plan);
    verifySource(plan, sourceFile);
    verifyIdentities(plan);
    verifyImplementationCommit(plan);
    verifyEnvironment(plan);
    verifyOutputLocation(plan);
    grid(plan);
    final ArrayNode folds = manifests(plan, sourceFile);
    compareArchived(plan, folds);
    verifyApprovedManifest(plan, folds);

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
    verifyProtocol(plan);
    verifyPlanIdentity(registration);
    verifySource(plan, sourceFile);
    verifyIdentities(plan);
    verifyImplementationCommit(plan);
    verifyEnvironment(plan);
    verifyOutputLocation(plan);
    grid(plan);
    final ArrayNode rebuilt = manifests(plan, sourceFile);
    require(rebuilt.equals(required(registration, "folds")), "Eligibility row manifest mismatch");
    compareArchived(plan, rebuilt);
    verifyApprovedManifest(plan, rebuilt);
    verifyCommitted(registrationFile);

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

  private static void verifyProtocol(JsonNode plan) {
    final String version = required(plan, "version").asString();
    if (!VERSION.equals(version)) {
      require(
          version.startsWith("test-") && required(plan, "testFixture").booleanValue(),
          "Unsupported development protocol version");
      return;
    }
    require(
        required(plan, "source")
            .get("sha256")
            .asString()
            .equals("27012c05d1e948133a4a2558ec841df62c518b9122117a461ca1f8f6aa9d1608"),
        "Approved source identity mismatch");
    require(
        required(required(plan, "authority"), "proposal")
            .asString()
            .equals("v2-development-1-proposal-1"),
        "Approved authority mismatch");
    required(plan, "implementationCommit");
    require(
        strings(plan, "electionCycleDates")
            .equals(List.of("2010-09-19", "2014-09-14", "2018-09-09", "2022-09-11")),
        "Approved election cycle dates mismatch");
    final JsonNode seeds = required(plan, "seeds");
    require(required(seeds, "master").intValue() == 20260908, "Approved master seed mismatch");
    require(
        integers(seeds, "precision")
            .equals(
                List.of(
                    20260908, 20260909, 20260910, 20260911, 20260912, 20260913, 20260914,
                    20260915)),
        "Approved precision seeds mismatch");
    require(required(plan, "commands").size() == 2, "Approved commands are incomplete");
    require(
        required(plan, "outputLocation")
            .asString()
            .equals("docs/validation/v2-development-1/evidence/run-1"),
        "Approved output location mismatch");
    require(
        required(plan, "foldsFrom").asString().equals("docs/validation/protocol.json"),
        "Approved fold source mismatch");
    require(
        required(plan, "archivedDiagnostics").asString().equals("docs/validation/diagnostics.json"),
        "Approved diagnostic source mismatch");
    final Set<String> identities = new HashSet<>();
    for (JsonNode identity : required(plan, "identities")) {
      identities.add(required(identity, "path").asString());
    }
    for (String path :
        List.of(
            "src/main/java/se/swedishpolls/estimation/DevelopmentValidation.java",
            "src/main/java/se/swedishpolls/estimation/DevelopmentTuning.java",
            "src/main/java/se/swedishpolls/estimation/DevelopmentDiagnostics.java",
            "src/main/java/se/swedishpolls/estimation/PollObservations.java",
            "docs/validation/protocol.json",
            "docs/validation/diagnostics.json",
            "src/main/resources/publication/model-freeze.json")) {
      require(identities.contains(path), "Missing required identity " + path);
    }
    final Map<String, String> environment =
        Map.of(
            "javaVersion", "25.0.4",
            "osName", "Linux",
            "osArch", "amd64",
            "mavenVersion", "3.9.16",
            "nodeVersion", "24.13.1",
            "npmVersion", "11.8.0",
            "ejmlVersion", "0.46.1",
            "postgresImage",
                "postgres:18.4@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636",
            "runtimeImage",
                "eclipse-temurin@sha256:b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e");
    for (Map.Entry<String, String> entry : environment.entrySet()) {
      require(
          required(required(plan, "environment"), entry.getKey())
              .asString()
              .equals(entry.getValue()),
          "Approved environment identity mismatch: " + entry.getKey());
    }
    require(
        doubles(required(plan, "grid"), "walkVariances")
            .equals(List.of(0.000003, 0.00001, 0.00003, 0.0001, 0.0003, 0.001)),
        "Approved walk-variance grid mismatch");
    require(
        doubles(required(plan, "grid"), "houseScales").equals(List.of(0.01, 0.02, 0.05, 0.1, 0.2)),
        "Approved house-scale grid mismatch");
    require(
        doubles(required(plan, "grid"), "covarianceMultipliers")
            .equals(List.of(0.5, 0.75, 1.0, 1.5, 2.0, 3.0)),
        "Approved covariance grid mismatch");
  }

  private static void verifyApprovedManifest(JsonNode plan, ArrayNode manifests) {
    if (!VERSION.equals(required(plan, "version").asString())) return;
    require(manifests.size() == 96, "Approved protocol must contain 96 fold entries");
    final long active =
        manifests.valueStream().filter(fold -> fold.get("active").booleanValue()).count();
    require(active == 75, "Approved protocol must contain 75 active fold entries");
    final long missingTraining =
        manifests
            .valueStream()
            .filter(fold -> fold.has("reason"))
            .filter(
                fold -> fold.get("reason").asString().equals("no_eligible_training_observation"))
            .count();
    final long missingComposition =
        manifests
            .valueStream()
            .filter(fold -> fold.has("reason"))
            .filter(fold -> fold.get("reason").asString().equals("no_held_out_composition"))
            .count();
    require(missingTraining == 2, "Approved protocol must contain two missing-training folds");
    require(
        missingComposition == 19, "Approved protocol must contain 19 missing-composition folds");
  }

  private static List<Double> doubles(JsonNode parent, String field) {
    final List<Double> values = new ArrayList<>();
    for (JsonNode value : required(parent, field)) values.add(value.doubleValue());
    return List.copyOf(values);
  }

  private static List<String> strings(JsonNode parent, String field) {
    final List<String> values = new ArrayList<>();
    for (JsonNode value : required(parent, field)) values.add(value.asString());
    return List.copyOf(values);
  }

  private static List<Integer> integers(JsonNode parent, String field) {
    final List<Integer> values = new ArrayList<>();
    for (JsonNode value : required(parent, field)) values.add(value.intValue());
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

  private static void verifyImplementationCommit(JsonNode plan) {
    if (!VERSION.equals(required(plan, "version").asString())) return;
    final String commit = required(plan, "implementationCommit").asString();
    final Path root =
        Path.of(git(null, "Cannot locate the repository", "rev-parse", "--show-toplevel").trim());
    git(
        root,
        "Implementation commit is unavailable",
        "rev-parse",
        "--verify",
        commit + "^{commit}");
    for (JsonNode identity : required(plan, "identities")) {
      git(
          root,
          "Identity differs from the implementation commit",
          "diff",
          "--quiet",
          commit,
          "--",
          required(identity, "path").asString());
    }
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

  private static void verifyCommitted(Path registration) {
    final Path absolute = registration.toAbsolutePath().normalize();
    final Path root =
        Path.of(git(null, "Cannot locate the repository", "rev-parse", "--show-toplevel").trim());
    require(absolute.startsWith(root), "Frozen registration is outside the repository");
    final String relative = root.relativize(absolute).toString();
    git(
        root,
        "Frozen registration is not committed",
        "ls-files",
        "--error-unmatch",
        "--",
        relative);
    git(
        root,
        "Frozen registration has uncommitted changes",
        "diff",
        "--quiet",
        "HEAD",
        "--",
        relative);
  }

  private static String git(Path directory, String failure, String... arguments) {
    final List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
      if (directory != null) builder.directory(directory.toFile());
      final Process process = builder.start();
      final String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final int exit = process.waitFor();
      require(exit == 0, failure + (output.isBlank() ? "" : ": " + output.trim()));
      return output;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while checking frozen registration", e);
    }
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

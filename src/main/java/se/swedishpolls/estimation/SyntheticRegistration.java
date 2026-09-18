package se.swedishpolls.estimation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The machine-readable registration of the accepted {@code synthetic-recovery-v1} protocol, and the
 * execution identity the formal run is generated under.
 *
 * <p>A registration pins the protocol document, the code commit, the dependency and toolchain
 * identities, the container image, the architecture, the host, the single-worker configuration, the
 * exact commands, the numerical tolerances, the schedule, the fixed covariance and its factor, and
 * the stream rules. Every operation that produces evidence re-reads it and rejects an identity that
 * has moved, so the implementation cannot silently change the experiment. Preflight needs the
 * registration committed at its registered location; formal generation additionally needs the
 * execution identity committed, which is what binds the run to a feasible preflight.
 *
 * <p>The 24-hour wall clock starts at preflight launch. The execution identity carries that instant
 * and the deadline derived from it, so the cap spans preflight, formal work and reproduction rather
 * than restarting with each command.
 */
final class SyntheticRegistration {
  private SyntheticRegistration() {}

  static final String FROZEN = "frozen";

  /** Where a formal registration and its execution identity have to be committed. */
  static final Path COMMITTED_REGISTRATION =
      Path.of("docs/validation/synthetic-recovery-v1/registration.json");

  static final Path COMMITTED_EXECUTION =
      Path.of("docs/validation/synthetic-recovery-v1/execution.json");

  /** The protocol document a registration of this version has to name. */
  private static final String PROTOCOL_DOCUMENT = "docs/validation/synthetic-recovery-protocol.md";

  private static final List<String> COMMAND_NAMES =
      List.of("register", "preflight", "commit", "experiment", "reproduce", "report");

  private static final List<String> TOLERANCES = List.of("interval", "coverage", "likelihood");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A checked registration: the frozen document, its plan and the identity it was read under. */
  record Checked(JsonNode registration, JsonNode plan, String sha256) {
    int datasets() {
      return required(required(plan, "run"), "datasets").intValue();
    }

    int draws() {
      return required(required(plan, "run"), "draws").intValue();
    }

    String phase() {
      return required(plan, "phase").asString();
    }

    long masterSeed() {
      return required(required(plan, "streams"), "masterSeed").longValue();
    }
  }

  /** The execution identity of one run, and the wall clock it inherits from preflight. */
  record Execution(
      Checked checked,
      JsonNode identity,
      String sha256,
      Instant deadlineStartedAt,
      Instant deadlineAt) {

    /** True once the watchdog has to stop scheduling further work. */
    boolean expired() {
      return !Instant.now().isBefore(deadlineAt);
    }

    long remainingMillis() {
      return Duration.between(Instant.now(), deadlineAt).toMillis();
    }
  }

  /** The sidecar that makes a frozen document's own identity checkable. */
  static Path checksum(Path document) {
    return document.resolveSibling(document.getFileName() + ".sha256");
  }

  /**
   * Validates a registration plan and freezes it, refusing to replace an existing registration. The
   * plan is validated exactly as every later boundary validates it, so a registration that froze is
   * one the rest of the workflow accepts.
   */
  static void register(Path planFile, Path registrationFile) {
    refuseExisting(registrationFile);
    refuseExisting(checksum(registrationFile));
    final JsonNode plan = read(planFile);
    verifyPlan(plan);
    verifyOutputLocation(plan);

    final ObjectNode registration = JSON.createObjectNode();
    registration.put("status", FROZEN);
    registration.put("version", required(plan, "version").asString());
    registration.put("phase", required(plan, "phase").asString());
    registration.put("registeredOn", required(plan, "registeredOn").asString());
    final ObjectNode planIdentity = registration.putObject("planIdentity");
    planIdentity.put("path", planFile.toString());
    planIdentity.put("sha256", digest(planFile));
    registration.set("plan", plan);
    registration.set("covariance", retainedCovariance());
    final byte[] bytes = pretty(registration);
    writeNew(registrationFile, bytes);
    writeNew(
        checksum(registrationFile),
        (sha256(bytes) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Reads a frozen registration and re-verifies every identity it pinned. {@code committed} is the
   * boundary rule: a formal preflight and every later formal operation require the registration at
   * its committed location, while a software-check fixture may sit anywhere.
   */
  static Checked check(Path registrationFile, boolean committed) {
    final byte[] bytes = bytes(registrationFile);
    require(
        sha256(bytes).equals(text(checksum(registrationFile)).trim()),
        "Registration checksum mismatch");
    final JsonNode registration = tree(bytes, registrationFile);
    require(
        FROZEN.equals(required(registration, "status").asString()), "Registration is not frozen");
    final JsonNode plan = required(registration, "plan");
    verifyPlan(plan);
    verifyRetainedCovariance(required(registration, "covariance"));
    final JsonNode identity = required(registration, "planIdentity");
    require(
        digest(Path.of(required(identity, "path").asString()))
            .equals(required(identity, "sha256").asString()),
        "Registration plan identity mismatch");
    if (committed && formal(plan))
      require(
          registrationFile.toAbsolutePath().normalize().equals(absolute(COMMITTED_REGISTRATION)),
          "Formal execution requires the committed registration location");
    return new Checked(registration, plan, sha256(bytes));
  }

  /**
   * Commits the final execution identity from a feasible preflight record. Formal generation reads
   * it back from its committed location, so no formal dataset is generated under a registration
   * whose preflight did not measure the run or found it infeasible.
   */
  static void commit(Path registrationFile, Path preflightFile, Path executionFile) {
    refuseExisting(executionFile);
    refuseExisting(checksum(executionFile));
    final Checked checked = check(registrationFile, true);
    final JsonNode preflight = read(preflightFile);
    require(
        required(preflight, "version")
            .asString()
            .equals(required(checked.plan(), "version").asString()),
        "Preflight protocol identity mismatch");
    require(
        required(preflight, "registrationSha256").asString().equals(checked.sha256()),
        "Preflight registration identity mismatch");
    require(
        required(preflight, "phase").asString().equals(checked.phase()),
        "Preflight phase identity mismatch");
    require(
        required(preflight, "feasibility").asString().equals("feasible"),
        "Formal generation needs a feasible preflight");
    final Instant started = Instant.parse(required(preflight, "deadlineStartedAt").asString());

    final ObjectNode identity = JSON.createObjectNode();
    identity.put("status", FROZEN);
    identity.put("version", required(checked.plan(), "version").asString());
    identity.put("phase", checked.phase());
    identity.put("registrationPath", registrationFile.toString());
    identity.put("registrationSha256", checked.sha256());
    identity.put("preflightPath", preflightFile.toString());
    identity.put("preflightSha256", digest(preflightFile));
    identity.put(
        "implementationCommit", required(checked.plan(), "implementationCommit").asString());
    identity.put("hostname", required(required(checked.plan(), "host"), "hostname").asString());
    identity.put("workers", required(required(checked.plan(), "host"), "workers").intValue());
    identity.put("architecture", System.getProperty("os.arch"));
    identity.put("javaRuntime", Runtime.version().toString());
    identity.put(
        "vm",
        ManagementFactory.getRuntimeMXBean().getVmName()
            + " "
            + ManagementFactory.getRuntimeMXBean().getVmVersion());
    identity.put("datasets", checked.datasets());
    identity.put("draws", checked.draws());
    identity.put("deadlineStartedAt", started.toString());
    identity.put(
        "deadlineAt", started.plusNanos(required(preflight, "capNanos").longValue()).toString());
    identity.put(
        "clock",
        "the 24-hour wall clock starts at preflight launch and covers formal work and reproduction");
    final byte[] bytes = pretty(identity);
    writeNew(executionFile, bytes);
    writeNew(
        checksum(executionFile),
        (sha256(bytes) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
  }

  /** Reads a committed execution identity and re-verifies the registration behind it. */
  static Execution execution(Path executionFile) {
    final byte[] bytes = bytes(executionFile);
    require(
        sha256(bytes).equals(text(checksum(executionFile)).trim()),
        "Execution identity checksum mismatch");
    final JsonNode identity = tree(bytes, executionFile);
    require(
        FROZEN.equals(required(identity, "status").asString()), "Execution identity is not frozen");
    final Checked checked = check(Path.of(required(identity, "registrationPath").asString()), true);
    require(
        required(identity, "registrationSha256").asString().equals(checked.sha256()),
        "Execution registration identity mismatch");
    require(
        required(identity, "implementationCommit")
            .asString()
            .equals(required(checked.plan(), "implementationCommit").asString()),
        "Execution implementation identity mismatch");
    require(
        required(identity, "architecture").asString().equals(System.getProperty("os.arch")),
        "Execution architecture identity mismatch");
    require(
        required(identity, "datasets").intValue() == checked.datasets()
            && required(identity, "draws").intValue() == checked.draws(),
        "Execution run identity mismatch");
    if (formal(checked.plan()))
      require(
          executionFile.toAbsolutePath().normalize().equals(absolute(COMMITTED_EXECUTION)),
          "Formal generation requires the committed execution identity location");
    return new Execution(
        checked,
        identity,
        sha256(bytes),
        Instant.parse(required(identity, "deadlineStartedAt").asString()),
        Instant.parse(required(identity, "deadlineAt").asString()));
  }

  /**
   * Takes the single-worker lock of one run. The registration permits one worker on one host, and
   * the lock is the second, independent signal: a second worker finds the file and stops.
   */
  static void claimWorker(Path directory, Checked checked) {
    require(
        required(required(checked.plan(), "host"), "workers").intValue() == 1,
        "The protocol permits one worker");
    final ObjectNode worker = JSON.createObjectNode();
    worker.put("hostname", hostname());
    worker.put("pid", ProcessHandle.current().pid());
    worker.put("claimedAt", Instant.now().toString());
    worker.put("workers", 1);
    worker.put(
        "rule",
        "one host and one worker; the lock is retained so nothing silently resumes the run");
    writeNew(directory.resolve("worker.lock"), pretty(worker));
  }

  /** Verifies the protected evidence is exactly what the registration pinned. */
  static void verifyProtectedEvidence(JsonNode plan) {
    for (JsonNode location : required(plan, "protectedLocations")) {
      final Path path = Path.of(required(location, "path").asString());
      require(
          treeDigest(path).equals(required(location, "sha256").asString()),
          "Protected evidence changed: " + path);
    }
  }

  /**
   * The digest of one protected location. A directory hashes to its file digests in path order, so
   * an evidence tree carries one identity without archiving its contents.
   */
  static String treeDigest(Path path) {
    if (Files.isRegularFile(path)) return digest(path);
    require(Files.isDirectory(path), "Protected location is missing: " + path);
    final MessageDigest digest = sha256();
    try (final Stream<Path> files = Files.walk(path)) {
      for (Path file :
          files.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList())
        digest.update(
            (path.relativize(file) + "|" + digest(file) + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * The fixed covariance and its noise factor as numbers. The protocol preserves the matrix in the
   * registration, not only its digest, so the covariance a dataset generated through is comparable
   * to the registration without recomputing it from the implementation being checked.
   */
  private static ObjectNode retainedCovariance() {
    final ObjectNode retained = JSON.createObjectNode();
    retained.put("sha256", covarianceSha256());
    final ModelValues covariance = SyntheticScenario.observationCovariance();
    final ArrayNode matrix = retained.putArray("observationCovariance");
    for (int r = 0; r < covariance.getNumRows(); r++) {
      final ArrayNode row = matrix.addArray();
      for (int c = 0; c < covariance.getNumCols(); c++) row.add(covariance.get(r, c));
    }
    final ArrayNode factor = retained.putArray("noiseFactor");
    for (double[] values : SyntheticScenario.noiseFactor()) {
      final ArrayNode row = factor.addArray();
      for (double value : values) row.add(value);
    }
    retained.put(
        "source",
        "R = H diag(1/p) H' / 1000 at the registered reference composition, and the lower Cholesky"
            + " factor of m R; never recomputed from generated shares or latent states");
    return retained;
  }

  /**
   * Rejects a retained covariance that no longer matches the one the scenario generates through.
   */
  private static void verifyRetainedCovariance(JsonNode retained) {
    final ModelValues covariance = SyntheticScenario.observationCovariance();
    require(
        matches(required(retained, "observationCovariance"), covariance),
        "Retained observation covariance mismatch");
    require(
        matches(required(retained, "noiseFactor"), SyntheticScenario.noiseFactor()),
        "Retained noise factor mismatch");
    require(
        required(retained, "sha256").asString().equals(covarianceSha256()),
        "Retained covariance identity mismatch");
  }

  private static boolean matches(JsonNode retained, ModelValues values) {
    final double[][] rows = new double[values.getNumRows()][values.getNumCols()];
    for (int r = 0; r < values.getNumRows(); r++)
      for (int c = 0; c < values.getNumCols(); c++) rows[r][c] = values.get(r, c);
    return matches(retained, rows);
  }

  private static boolean matches(JsonNode retained, double[][] values) {
    if (retained.size() != values.length) return false;
    for (int r = 0; r < values.length; r++) {
      final JsonNode row = retained.get(r);
      if (row.size() != values[r].length) return false;
      for (int c = 0; c < values[r].length; c++)
        if (Double.compare(row.get(c).doubleValue(), values[r][c]) != 0) return false;
    }
    return true;
  }

  /** The identity of the fixed covariance and the noise factor the scenario generates through. */
  static String covarianceSha256() {
    final MessageDigest digest = sha256();
    final ByteBuffer buffer = ByteBuffer.allocate(Double.BYTES);
    final ModelValues covariance = SyntheticScenario.observationCovariance();
    for (int r = 0; r < covariance.getNumRows(); r++)
      for (int c = 0; c < covariance.getNumCols(); c++)
        digest.update(buffer.putDouble(0, covariance.get(r, c)).array());
    for (double[] row : SyntheticScenario.noiseFactor())
      for (double value : row) digest.update(buffer.putDouble(0, value).array());
    return HexFormat.of().formatHex(digest.digest());
  }

  static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      throw new IllegalStateException("The registered host cannot be identified", e);
    }
  }

  private static boolean formal(JsonNode plan) {
    return required(plan, "phase").asString().equals(SyntheticRecovery.FORMAL);
  }

  /** Every identity the protocol requires a registration to carry, checked in one place. */
  private static void verifyPlan(JsonNode plan) {
    require(
        SyntheticRecovery.VERSION.equals(required(plan, "version").asString()),
        "Unregistered protocol version");
    final String phase = required(plan, "phase").asString();
    require(
        phase.equals(SyntheticRecovery.FORMAL) || phase.equals(SyntheticRecovery.SOFTWARE_CHECK),
        "A registration is formal or a software check, not " + phase);
    required(plan, "registeredOn");
    final JsonNode protocol = required(plan, "protocol");
    require(
        required(protocol, "path").asString().equals(PROTOCOL_DOCUMENT),
        "A registration names the accepted protocol document");
    require(
        digest(Path.of(PROTOCOL_DOCUMENT)).equals(required(protocol, "sha256").asString()),
        "Accepted protocol document identity mismatch");
    require(
        required(plan, "implementationCommit").asString().matches("[0-9a-f]{40}"),
        "Invalid implementation commit identity");
    require(required(plan, "dependencies").size() > 0, "A registration pins its dependencies");
    for (JsonNode dependency : required(plan, "dependencies")) {
      final Path path = Path.of(required(dependency, "path").asString());
      require(Files.isRegularFile(path), "Dependency identity is missing: " + path);
      require(
          digest(path).equals(required(dependency, "sha256").asString()),
          "Dependency identity mismatch: " + path);
    }
    final JsonNode toolchain = required(plan, "toolchain");
    property(toolchain, "javaVersion", "java.version");
    property(toolchain, "osName", "os.name");
    property(toolchain, "osArch", "os.arch");
    require(
        required(toolchain, "javaRuntime").asString().equals(Runtime.version().toString()),
        "Toolchain identity mismatch: javaRuntime");
    require(
        required(toolchain, "vm")
            .asString()
            .equals(
                ManagementFactory.getRuntimeMXBean().getVmName()
                    + " "
                    + ManagementFactory.getRuntimeMXBean().getVmVersion()),
        "Toolchain identity mismatch: vm");
    final JsonNode container = required(plan, "container");
    if (formal(plan))
      require(
          text(Path.of("pom.xml"))
              .contains("<runtime.image>" + required(container, "image").asString()),
          "Container identity mismatch: image");
    final JsonNode host = required(plan, "host");
    require(
        required(host, "hostname").asString().equals(hostname()),
        "Host identity mismatch: " + required(host, "hostname").asString());
    require(required(host, "workers").intValue() == 1, "The protocol permits one worker");
    final JsonNode commands = required(plan, "commands");
    for (String name : COMMAND_NAMES)
      require(
          !required(commands, name).asString().isBlank(),
          "A registration records the exact " + name + " command");
    final JsonNode tolerances = required(plan, "numericalTolerances");
    for (String name : TOLERANCES) {
      final double tolerance = required(tolerances, name).doubleValue();
      require(
          Double.isFinite(tolerance) && tolerance >= 0,
          "The " + name + " tolerance is finite and not negative");
    }
    verifySchedule(required(plan, "schedule"));
    require(
        required(plan, "covarianceSha256").asString().equals(covarianceSha256()),
        "Registered covariance and noise factor identity mismatch");
    verifyStreams(required(plan, "streams"), plan);
    verifyRun(required(plan, "run"), plan);
    verifyLimits(required(plan, "limits"));
    verifyProtectedEvidence(plan);
  }

  private static void verifySchedule(JsonNode schedule) {
    require(
        required(schedule, "periodStart")
                .asString()
                .equals(SyntheticScenario.PERIOD_START.toString())
            && required(schedule, "cutoff").asString().equals(SyntheticScenario.CUTOFF.toString())
            && required(schedule, "scoreThrough")
                .asString()
                .equals(SyntheticScenario.HORIZON.toString())
            && required(schedule, "weeks").intValue() == SyntheticScenario.WEEKS
            && required(schedule, "trainingPolls").intValue() == SyntheticScenario.TRAINING_POLLS
            && required(schedule, "scoringPolls").intValue() == SyntheticScenario.SCORING_POLLS,
        "Registered schedule identity mismatch");
    require(
        strings(schedule, "institutes").equals(SyntheticScenario.INSTITUTES),
        "Registered institute identity mismatch");
  }

  private static void verifyStreams(JsonNode streams, JsonNode plan) {
    final boolean formalSeed =
        required(streams, "masterSeed").longValue() == SyntheticRecovery.FORMAL_SEED;
    require(
        formalSeed == formal(plan),
        formal(plan)
            ? "The formal phase uses master seed " + SyntheticRecovery.FORMAL_SEED
            : "A software check uses a master seed of its own, not the formal one");
    require(
        required(streams, "formalPrefix")
                .asString()
                .equals(SyntheticRecovery.VERSION + "|" + SyntheticRecovery.FORMAL)
            && required(streams, "preflightPrefix")
                .asString()
                .equals(SyntheticRecovery.VERSION + "|" + SyntheticRecovery.PREFLIGHT),
        "Registered stream prefix mismatch");
    require(
        strings(streams, "generating").equals(SyntheticScenario.GENERATING_STREAMS),
        "Registered generating stream mismatch");
    require(
        required(streams, "predictive").asString().equals("predictive"),
        "Registered predictive stream mismatch");
    require(
        required(streams, "seedDerivation")
            .asString()
            .equals("SHA-256 of UTF-8 \"stream|masterSeed\"; first eight bytes big-endian signed"),
        "Registered seed derivation mismatch");
  }

  private static void verifyRun(JsonNode run, JsonNode plan) {
    require(
        strings(run, "conventions").equals(SyntheticRecovery.CONVENTIONS),
        "Registered convention mismatch");
    require(
        strings(run, "stages")
            .equals(List.of(SyntheticRecovery.KNOWN_STAGE, SyntheticRecovery.ESTIMATED_STAGE)),
        "Registered stage mismatch");
    require(
        required(run, "gridPoints").intValue() == SyntheticSearch.POINTS,
        "Registered grid identity mismatch");
    if (!formal(plan)) {
      require(required(run, "datasets").intValue() >= 2, "A sample variance needs two datasets");
      require(required(run, "draws").intValue() >= 2, "Coverage needs at least two draws");
      return;
    }
    require(
        required(run, "datasets").intValue() == SyntheticRecovery.FORMAL_DATASETS,
        "The formal phase runs " + SyntheticRecovery.FORMAL_DATASETS + " datasets per convention");
    require(
        required(run, "draws").intValue() == SyntheticRecovery.FORMAL_DRAWS,
        "The formal phase uses " + SyntheticRecovery.FORMAL_DRAWS + " predictive draws");
  }

  private static void verifyLimits(JsonNode limits) {
    require(
        required(limits, "wallClockHours").intValue() == 24
            && Double.compare(
                    required(limits, "projectionMargin").doubleValue(), SyntheticBudget.MARGIN)
                == 0
            && required(limits, "logReserveBytes").longValue() == SyntheticBudget.LOG_RESERVE_BYTES
            && required(limits, "preflightDatasets").intValue()
                == SyntheticRecovery.PREFLIGHT_DATASETS,
        "Registered resource limit mismatch");
  }

  /** Both evidence destinations have to be fresh, separate and clear of every protected path. */
  private static void verifyOutputLocation(JsonNode plan) {
    final Path formal = absolute(Path.of(required(plan, "outputLocation").asString()));
    final Path preflight = absolute(Path.of(required(plan, "preflightLocation").asString()));
    require(
        !formal.equals(preflight), "Preflight and formal evidence need destinations of their own");
    for (Path output : List.of(formal, preflight)) {
      require(!Files.exists(output), "Registered evidence output already exists: " + output);
      for (JsonNode location : required(plan, "protectedLocations")) {
        final Path protectedPath = absolute(Path.of(required(location, "path").asString()));
        require(
            !output.startsWith(protectedPath) && !protectedPath.startsWith(output),
            "Registered evidence output overlaps protected path " + protectedPath);
      }
    }
  }

  /** The registered destination of one phase, so evidence cannot land wherever it is asked to. */
  static void verifyDestination(Checked checked, Path destination, String field) {
    require(
        absolute(destination).equals(absolute(Path.of(required(checked.plan(), field).asString()))),
        "Evidence must use the registered "
            + field
            + " "
            + required(checked.plan(), field).asString());
  }

  static double tolerance(Checked checked, String name) {
    return required(required(checked.plan(), "numericalTolerances"), name).doubleValue();
  }

  private static void property(JsonNode registered, String field, String property) {
    require(
        required(registered, field).asString().equals(System.getProperty(property)),
        "Toolchain identity mismatch: " + field);
  }

  private static List<String> strings(JsonNode parent, String field) {
    final List<String> values = new ArrayList<>();
    for (JsonNode value : required(parent, field)) values.add(value.asString());
    return List.copyOf(values);
  }

  private static Path absolute(Path path) {
    return path.toAbsolutePath().normalize();
  }

  static void refuseExisting(Path path) {
    require(!Files.exists(path), "Output already exists: " + path);
  }

  static JsonNode required(JsonNode parent, String field) {
    final JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || value.isNull())
      throw new IllegalArgumentException("Incomplete registration: missing " + field);
    return value;
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  static JsonNode read(Path file) {
    return tree(bytes(file), file);
  }

  private static JsonNode tree(byte[] bytes, Path file) {
    try {
      return JSON.readTree(bytes);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid JSON in " + file, e);
    }
  }

  static byte[] pretty(JsonNode value) {
    return (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)
            + System.lineSeparator())
        .getBytes(StandardCharsets.UTF_8);
  }

  static byte[] bytes(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String text(Path file) {
    return new String(bytes(file), StandardCharsets.UTF_8);
  }

  static String digest(Path file) {
    return sha256(bytes(file));
  }

  static String sha256(byte[] bytes) {
    return HexFormat.of().formatHex(sha256().digest(bytes));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Writes a document that must not already exist, creating the directories it needs. */
  static void writeNew(Path file, byte[] bytes) {
    try {
      final Path parent = absolute(file).getParent();
      if (parent != null) Files.createDirectories(parent);
      Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

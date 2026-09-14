package se.swedishpolls.estimation;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Retained predictive evidence for a single registered development run. */
final class DevelopmentPredictiveEvidence {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> METHODS = List.of("midpoint", "ilr_window", "recency");
  private final DevelopmentDiagnostics.Rules rules;
  private final Path directory;
  private final Map<String, ObjectNode> drawArtifacts = new LinkedHashMap<>();
  private final Map<String, List<DevelopmentDiagnostics.Folded>> periods = new LinkedHashMap<>();
  private final Map<String, List<String>> components = new LinkedHashMap<>();

  DevelopmentPredictiveEvidence(Path directory) {
    this.directory = directory;
    rules =
        new DevelopmentDiagnostics.Rules(
            20260908,
            35,
            4000,
            List.of(1, 3, 6),
            List.of(1, 2, 3),
            List.of(1, 8, 15),
            List.of(0.90, 0.98),
            List.of(0.40, 0.60));
  }

  void score(
      ObjectNode result,
      JsonNode manifest,
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections) {
    final List<DevelopmentDiagnostics.Folded> retained =
        periods.computeIfAbsent(period.id(), ignored -> new ArrayList<>());
    final JsonNode searches = result.get("methods");
    if (!searches.get(0).get("numericallyAvailable").booleanValue()
        || !searches.get(1).get("numericallyAvailable").booleanValue()) {
      unavailable(result, "Selected training fit is unavailable");
      return;
    }
    try {
      final DevelopmentTuning.Fold fold =
          new DevelopmentTuning.Fold(
              LocalDate.parse(manifest.get("cutoff").asString()),
              LocalDate.parse(manifest.get("scoreThrough").asString()));
      final DevelopmentDiagnostics.Folded scored =
          DevelopmentDiagnostics.fold(
              period,
              polls,
              elections,
              fold,
              parameters(searches.get(0)),
              parameters(searches.get(1)),
              searches.get(1).get("gridBoundaries").valueStream().map(JsonNode::asString).toList(),
              rules,
              this::retainDraws);
      final List<DevelopmentDiagnostics.Scored> candidate = scored.scored().get("midpoint");
      final List<Integer> actual = candidate.stream().map(row -> row.poll().rowNumber()).toList();
      final List<Integer> expected =
          manifest.get("scoringRows").valueStream().map(JsonNode::intValue).toList();
      if (!actual.equals(expected) || actual.stream().distinct().count() != actual.size())
        throw new IllegalArgumentException(
            "Scored rows do not reconcile to the registered manifest");
      final PollObservations.Batch heldOut =
          PollObservations.prepare(period, DevelopmentDiagnostics.heldOut(polls, fold, rules));
      components.put(period.id(), heldOut.components());
      final ArrayNode rows = JSON.createArrayNode();
      for (int index = 0; index < candidate.size(); index++) {
        final DevelopmentDiagnostics.Scored row = candidate.get(index);
        final PollObservations.Observation observed = heldOut.observations().get(index);
        final ObjectNode target = rows.addObject();
        target.put("rowNumber", row.poll().rowNumber());
        target.put("rowSha256", rowDigest(row.poll()));
        target.put("periodId", period.id());
        target.put("cutoff", fold.cutoff().toString());
        target.put("institute", row.poll().institute());
        target.put("methodEra", row.poll().methodEra());
        target.put("publicationDate", row.poll().publicationDate().toString());
        target.put("fieldworkFrom", row.window().from().toString());
        target.put("fieldworkTo", row.window().to().toString());
        target.put("fieldworkDays", row.window().days());
        target.put("fieldworkBand", band(row));
        target.put("sampleSize", row.poll().sampleSize());
        target.put("replacedZeros", observed.replacedZeros());
        target.set(
            "observedComposition",
            JSON.valueToTree(Roster.compose(period, row.poll()).components()));
        target.set(
            "transformedComposition",
            JSON.valueToTree(PollObservations.shares(heldOut, observed.ilr())));
        final ArrayNode methods = target.putArray("methods");
        for (String method : METHODS) {
          final DevelopmentDiagnostics.Scored prediction = scored.scored().get(method).get(index);
          if (prediction.poll().rowNumber() != row.poll().rowNumber())
            throw new IllegalArgumentException("Methods disagree on scored row identity");
          final ObjectNode evidence = methods.addObject();
          evidence.put("method", method);
          evidence.put("jointLogScore", prediction.logScore());
          evidence.put("draws", rules.scoreDraws());
          evidence.put("seed", rules.seed());
          evidence.put("stream", prediction.stream());
          evidence.set("drawArtifact", drawArtifacts.get(prediction.stream()));
          evidence.set("whitenedIlrResiduals", JSON.valueToTree(prediction.whitened()));
          evidence.set("components", JSON.valueToTree(prediction.components()));
        }
      }
      result.set("polls", rows);
      result.put("scoredPolls", rows.size());
      result.set("meanLogScore", JSON.valueToTree(scored.fold().meanLogScore()));
      result.put("diagnosticStatus", "evaluated");
      retained.add(scored);
    } catch (RuntimeException e) {
      unavailable(result, e.getMessage());
    }
  }

  private void retainDraws(String stream, double[][] draws) {
    final MessageDigest digest = sha256();
    final String filename =
        "predictive-draws/"
            + HexFormat.of().formatHex(digest.digest(stream.getBytes(StandardCharsets.UTF_8)))
            + ".bin";
    final Path file = directory.resolve(filename);
    final ObjectNode artifact = JSON.createObjectNode();
    artifact.put("path", filename);
    artifact.put("stream", stream);
    artifact.put("draws", draws[0].length);
    artifact.put("components", draws.length);
    artifact.put("encoding", "big-endian IEEE-754 binary64; draw-major, then component order");
    artifact.put("status", "incomplete");
    drawArtifacts.put(stream, artifact);
    try {
      Files.createDirectories(directory.resolve("predictive-draws"));
      try (final DataOutputStream output =
          new DataOutputStream(
              new BufferedOutputStream(
                  new DigestOutputStream(
                      Files.newOutputStream(file, StandardOpenOption.CREATE_NEW), digest)))) {
        for (int draw = 0; draw < draws[0].length; draw++)
          for (double[] component : draws) output.writeDouble(component[draw]);
      }
      artifact.put("sha256", HexFormat.of().formatHex(digest.digest()));
      artifact.put("bytes", Files.size(file));
      artifact.put("status", "complete");
    } catch (IOException e) {
      artifact.put("reason", e.toString());
      throw new UncheckedIOException(e);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void unavailable(ObjectNode result, String reason) {
    result.put("diagnosticStatus", "unevaluated");
    result.put("diagnosticReason", reason == null ? "Predictive calculation failed" : reason);
  }

  private static DailyStateSpace.Parameters parameters(JsonNode search) {
    final JsonNode point = search.get("selectedParameters");
    return new DailyStateSpace.Parameters(
        point.get("walkVariance").doubleValue(),
        point.get("houseScale").doubleValue(),
        point.get("covarianceMultiplier").doubleValue());
  }

  void finish(ObjectNode result) {
    result.set("drawArtifacts", JSON.valueToTree(drawArtifacts.values()));
    final ArrayNode reasons = (ArrayNode) result.get("reasons");
    boolean complete = true;
    for (JsonNode fold : result.get("folds")) {
      if (fold.get("active").booleanValue()
          && !"evaluated".equals(fold.path("diagnosticStatus").asString())) {
        complete = false;
        reasons.add(
            fold.get("periodId").asString()
                + " "
                + fold.get("cutoff").asString()
                + ": diagnostics unevaluated: "
                + fold.path("diagnosticReason").asString());
      }
    }
    result.put("diagnosticEvidence", complete ? "complete" : "incomplete");
    result.set("diagnosticRules", JSON.valueToTree(rules));
    result.put("releaseAuthorized", false);
    result.put(
        "limitations",
        "Corrected-snapshot publication dates do not reconstruct historical source availability. "
            + "Institute and fieldwork associations do not establish causal attribution. "
            + "Reference subgroups are new diagnostics; the v1 archive has no reference subgroup evidence. "
            + "The old 2022 audit remains development evidence with its original blocked verdict; "
            + "this method missed the 2026-09-12 cutoff.");
    final ArrayNode summaries = result.putArray("diagnostics");
    for (Map.Entry<String, List<DevelopmentDiagnostics.Folded>> entry : periods.entrySet()) {
      final String period = entry.getKey();
      final List<DevelopmentDiagnostics.Folded> folds = entry.getValue();
      final ObjectNode summary = summaries.addObject();
      summary.put("periodId", period);
      summary.set("paired", paired(period, folds, row -> true, reasons, true));
      final ArrayNode misfit = summary.putArray("misfit");
      final ArrayNode pairedGroups = summary.putArray("pairedGroups");
      final Map<String, Predicate<DevelopmentDiagnostics.Scored>> groups = new LinkedHashMap<>();
      groups.put("all|all", row -> true);
      final Map<String, Predicate<DevelopmentDiagnostics.Scored>> institutes = new TreeMap<>();
      final Map<String, Predicate<DevelopmentDiagnostics.Scored>> eras = new TreeMap<>();
      for (DevelopmentDiagnostics.Folded fold : folds)
        for (DevelopmentDiagnostics.Scored row : fold.scored().get("midpoint")) {
          final String institute = row.poll().institute();
          final String era = row.poll().methodEra();
          institutes.put(
              "institute|" + institute, value -> value.poll().institute().equals(institute));
          eras.put(
              "method_era|" + era,
              value -> java.util.Objects.equals(value.poll().methodEra(), era));
        }
      groups.putAll(institutes);
      groups.putAll(eras);
      for (String band : List.of("1-7", "8-14", "15+"))
        groups.put("fieldwork_days|" + band, row -> band(row).equals(band));
      for (Map.Entry<String, Predicate<DevelopmentDiagnostics.Scored>> group : groups.entrySet()) {
        final String[] label = group.getKey().split("\\|", 2);
        if (!label[0].equals("all")) {
          final ObjectNode comparison = paired(period, folds, group.getValue(), reasons, false);
          comparison.put("scope", label[0]);
          comparison.put("name", label[1]);
          pairedGroups.add(comparison);
        }
        for (String method : METHODS) {
          final List<DevelopmentDiagnostics.Scored> rows =
              folds.stream()
                  .flatMap(fold -> fold.scored().get(method).stream())
                  .filter(group.getValue())
                  .toList();
          final boolean registered =
              label[0].equals("all")
                  || label[0].equals("institute")
                  || label[0].equals("fieldwork_days");
          misfit.add(misfit(period, method, label[0], label[1], rows, -1, registered, reasons));
          final List<String> names = components.getOrDefault(period, List.of());
          for (int component = 0; component < names.size(); component++) {
            final ObjectNode party =
                misfit(
                    period,
                    method,
                    label[0].equals("all") ? "party" : label[0] + "_party",
                    names.get(component),
                    rows,
                    component,
                    label[0].equals("all"),
                    reasons);
            party.put("group", label[1]);
            misfit.add(party);
          }
        }
      }
      final ArrayNode autocorrelation = summary.putArray("autocorrelation");
      final ArrayNode dependence = summary.putArray("dependence");
      summary.put(
          "dependenceExplanation",
          "Whitened ilr products measure overlap, disjoint and same-institute dependence within folds. "
              + "The registered lag-three HAC error carries fold dependence; lag one and six are descriptive. "
              + "Absolute residual autocorrelation above 0.45 blocks. A dependence hypothesis does not waive misfit.");
      for (String method : METHODS) {
        final List<List<DevelopmentDiagnostics.Scored>> byFold =
            folds.stream().map(fold -> fold.scored().get(method)).toList();
        for (DevelopmentDiagnostics.Autocorrelation measured :
            DevelopmentDiagnostics.autocorrelation(period, byFold, rules)) {
          final ObjectNode row = JSON.valueToTree(measured);
          row.put("method", method);
          final boolean available = measured.pairs() > 0 && Double.isFinite(measured.correlation());
          final boolean passed = available && Math.abs(measured.correlation()) <= 0.45;
          row.put("status", !available ? "unevaluated" : passed ? "pass" : "fail");
          if (!available) row.putNull("correlation");
          row.put("required", method.equals("midpoint"));
          if (method.equals("midpoint") && !passed)
            reasons.add(
                period
                    + ": residual autocorrelation lag "
                    + measured.lag()
                    + " "
                    + row.get("status").asString());
          autocorrelation.add(row);
        }
        final ObjectNode measured =
            JSON.valueToTree(DevelopmentDiagnostics.dependence(period, byFold));
        measured.put("method", method);
        dependence.add(measured);
      }
    }
  }

  private static ObjectNode paired(
      String period,
      List<DevelopmentDiagnostics.Folded> folds,
      Predicate<DevelopmentDiagnostics.Scored> include,
      ArrayNode reasons,
      boolean required) {
    final ObjectNode result = JSON.createObjectNode();
    final ArrayNode means = result.putArray("folds");
    final List<Double> candidate = new ArrayList<>();
    final List<Double> baseline = new ArrayList<>();
    final List<Double> reference = new ArrayList<>();
    for (DevelopmentDiagnostics.Folded fold : folds) {
      final List<Integer> selected = new ArrayList<>();
      final List<DevelopmentDiagnostics.Scored> rows = fold.scored().get("midpoint");
      for (int i = 0; i < rows.size(); i++) if (include.test(rows.get(i))) selected.add(i);
      if (selected.isEmpty()) continue;
      final ObjectNode mean = means.addObject();
      mean.put("cutoff", fold.fold().cutoff().toString());
      mean.put("polls", selected.size());
      final Map<String, Double> scores = new LinkedHashMap<>();
      for (String method : METHODS) {
        final double value =
            selected.stream()
                .mapToDouble(index -> fold.scored().get(method).get(index).logScore())
                .average()
                .orElseThrow();
        scores.put(method, value);
        mean.put(method, value);
      }
      candidate.add(scores.get("midpoint"));
      baseline.add(scores.get("recency"));
      reference.add(scores.get("ilr_window"));
    }
    result.put("scoredFolds", candidate.size());
    result.put("weighting", "equal fold means within this roster");
    if (!candidate.isEmpty()) {
      result.put("baselineDifference", difference(candidate, baseline));
      result.put("referenceDifference", difference(candidate, reference));
    }
    try {
      final PredictiveComparison.Result comparison =
          PredictiveComparison.evaluate(array(candidate), array(baseline), array(reference));
      result.put("baselineDifference", comparison.baselineDifference());
      result.put("referenceDifference", comparison.referenceDifference());
      final double[] differences = new double[candidate.size()];
      for (int i = 0; i < differences.length; i++)
        differences[i] = candidate.get(i) - reference.get(i);
      final ObjectNode errors = result.putObject("standardErrorByLag");
      for (int lag : List.of(1, 3, 6))
        errors.put(
            Integer.toString(lag), DevelopmentDiagnostics.pairedStandardError(differences, lag));
      result.put("status", comparison.passes() ? "pass" : "fail");
      if (required && !comparison.passes())
        reasons.add(period + ": paired predictive comparison failed");
    } catch (IllegalArgumentException e) {
      result.put("status", candidate.size() < 8 ? "unevaluated" : "fail");
      result.put("reason", e.getMessage());
      if (required) reasons.add(period + ": paired comparison " + e.getMessage());
    }
    return result;
  }

  private static double[] array(List<Double> values) {
    return values.stream().mapToDouble(Double::doubleValue).toArray();
  }

  private static double difference(List<Double> left, List<Double> right) {
    double total = 0;
    for (int i = 0; i < left.size(); i++) total += (left.get(i) - right.get(i)) / left.size();
    return total;
  }

  private static ObjectNode misfit(
      String period,
      String method,
      String scope,
      String name,
      List<DevelopmentDiagnostics.Scored> rows,
      int component,
      boolean registered,
      ArrayNode reasons) {
    int cases = 0;
    int covered95 = 0;
    int covered50 = 0;
    double residual = 0;
    double squared = 0;
    for (DevelopmentDiagnostics.Scored row : rows) {
      final int from = component < 0 ? 0 : component;
      final int through = component < 0 ? row.standardized().size() : component + 1;
      for (int i = from; i < through; i++) {
        cases++;
        if (row.covered95().get(i)) covered95++;
        if (row.covered50().get(i)) covered50++;
        residual += row.standardized().get(i);
        squared += row.standardized().get(i) * row.standardized().get(i);
      }
    }
    final ObjectNode result = JSON.createObjectNode();
    result.put("periodId", period);
    result.put("candidate", method);
    result.put("scope", scope);
    result.put("name", name);
    result.put("polls", rows.size());
    result.put("cases", cases);
    result.put("covered95", covered95);
    result.put("covered50", covered50);
    final boolean required = registered && method.equals("midpoint");
    result.put("required", required);
    if (cases == 0) {
      result.put("status", "unevaluated");
      result.put("reason", "No scored cases");
      return result;
    }
    final double coverage95 = covered95 / (double) cases;
    final double coverage50 = covered50 / (double) cases;
    final double bias = residual / cases;
    final double rms = Math.sqrt(squared / cases);
    result.put("coverage95", coverage95);
    result.put("coverage50", coverage50);
    result.put("meanStandardizedResidual", bias);
    result.put("rootMeanSquareStandardizedResidual", rms);
    if (component < 0)
      result.put(
          "meanLogScore",
          rows.stream()
              .mapToDouble(DevelopmentDiagnostics.Scored::logScore)
              .average()
              .orElseThrow());
    final boolean eligible = scope.equals("all") || cases >= 100;
    final ArrayNode failures = result.putArray("reasons");
    if (!Double.isFinite(coverage95) || coverage95 < 0.90 || coverage95 > 0.98)
      failures.add("95% predictive coverage outside [0.90, 0.98]");
    if (!Double.isFinite(coverage50) || coverage50 < 0.40 || coverage50 > 0.60)
      failures.add("50% predictive coverage outside [0.40, 0.60]");
    if (!Double.isFinite(bias) || Math.abs(bias) > 0.5)
      failures.add("absolute mean standardized residual exceeds 0.5");
    if (!Double.isFinite(rms) || rms < 0.5 || rms > 1.5)
      failures.add("RMS standardized residual outside [0.5, 1.5]");
    result.put("status", !eligible ? "unevaluated" : failures.isEmpty() ? "pass" : "fail");
    if (!eligible) result.put("reason", "Fewer than 100 subgroup cases; adequacy not demonstrated");
    if (required && eligible)
      for (JsonNode failure : failures)
        reasons.add(period + " " + scope + " " + name + ": " + failure.asString());
    return result;
  }

  static void compareHistorical(ObjectNode result, JsonNode archive, JsonNode tuning) {
    final ObjectNode comparison = result.putObject("historicalComparison");
    comparison.put(
        "referenceSubgroups",
        "unavailable in v1; revised reference subgroup summaries are new diagnostics");
    if (archive == null) {
      comparison.put("status", "unevaluated");
      comparison.put("reason", "No archived diagnostics registered");
      return;
    }
    final ArrayNode folds = comparison.putArray("folds");
    for (JsonNode revised : result.get("folds")) {
      if (!revised.get("active").booleanValue()) continue;
      final JsonNode old =
          archive
              .get("folds")
              .valueStream()
              .filter(row -> sameFold(row, revised))
              .findFirst()
              .orElse(null);
      final ObjectNode row = folds.addObject();
      row.set("periodId", revised.get("periodId"));
      row.set("cutoff", revised.get("cutoff"));
      if (old == null || !old.get("scoredRowsSha256").equals(revised.get("scoringRowsSha256"))) {
        row.put("status", "unevaluated");
        row.put("reason", "No archived fold on identical active rows");
        continue;
      }
      row.put("status", revised.has("meanLogScore") ? "compared" : "unevaluated");
      row.set("oldParameters", old.get("candidateParameters"));
      row.set("oldReferenceParameters", old.get("referenceParameters"));
      row.set("newParameters", revised.get("methods").get(0).get("selectedParameters"));
      row.set("newReferenceParameters", revised.get("methods").get(1).get("selectedParameters"));
      row.set(
          "newCandidateTrainingLogLikelihood",
          revised.get("methods").get(0).get("selectedLogLikelihood"));
      row.set(
          "newReferenceTrainingLogLikelihood",
          revised.get("methods").get(1).get("selectedLogLikelihood"));
      row.set("oldMeanLogScore", old.get("meanLogScore"));
      if (revised.has("meanLogScore")) {
        row.set("newMeanLogScore", revised.get("meanLogScore"));
        final ObjectNode changes = row.putObject("scoreChanges");
        for (String method : METHODS)
          changes.put(
              method,
              revised.get("meanLogScore").get(method).doubleValue()
                  - old.get("meanLogScore").get(method).doubleValue());
      }
      final JsonNode oldTune =
          tuning == null
              ? null
              : tuning
                  .get("resolved")
                  .valueStream()
                  .filter(
                      point ->
                          point.get("periodId").equals(revised.get("periodId"))
                              && point.get("fold").get("cutoff").equals(revised.get("cutoff")))
                  .findFirst()
                  .orElse(null);
      if (oldTune != null)
        row.set("oldCandidateTrainingLogLikelihood", oldTune.get("logLikelihood"));
      else row.put("oldCandidateTrainingLikelihoodStatus", "unavailable");
      row.put("oldReferenceTrainingLikelihoodStatus", "unavailable in the v1 archive");
    }
    final ArrayNode summaries = comparison.putArray("midpointSummaries");
    for (JsonNode period : result.get("diagnostics")) {
      final List<JsonNode> oldFolds =
          archive
              .get("folds")
              .valueStream()
              .filter(fold -> fold.get("periodId").equals(period.get("periodId")))
              .toList();
      final List<JsonNode> newFolds =
          result
              .get("folds")
              .valueStream()
              .filter(
                  fold ->
                      fold.get("periodId").equals(period.get("periodId"))
                          && fold.get("active").booleanValue())
              .toList();
      final boolean identicalRows =
          oldFolds.size() == newFolds.size()
              && newFolds.stream()
                  .allMatch(
                      revised ->
                          revised.has("scoredPolls")
                              && oldFolds.stream()
                                  .anyMatch(
                                      old ->
                                          sameFold(old, revised)
                                              && old.get("scoredRowsSha256")
                                                  .equals(revised.get("scoringRowsSha256"))
                                              && old.get("scoredPolls")
                                                  .equals(revised.get("scoredPolls"))));
      for (JsonNode revised : period.get("misfit")) {
        if (!revised.get("candidate").asString().equals("midpoint")) continue;
        final JsonNode old =
            archive
                .get("misfit")
                .valueStream()
                .filter(
                    row ->
                        row.get("periodId").equals(period.get("periodId"))
                            && row.get("candidate").equals(revised.get("candidate"))
                            && row.get("scope").equals(revised.get("scope"))
                            && row.get("name").equals(revised.get("name")))
                .findFirst()
                .orElse(null);
        final ObjectNode row = summaries.addObject();
        row.set("revised", revised);
        if (old != null
            && identicalRows
            && old.get("polls").equals(revised.get("polls"))
            && old.get("cases").equals(revised.get("cases"))) {
          row.put("status", "compared");
          final ObjectNode oldSummary = (ObjectNode) old.deepCopy();
          if (revised.get("scope").asString().contains("party")) oldSummary.remove("meanLogScore");
          row.set("old", oldSummary);
        } else {
          row.put("status", "unevaluated");
          row.put(
              "reason",
              "Archived subgroup absent or scored row set differs; aggregate cannot be restricted without old row evidence");
        }
      }
    }
    comparison.put("status", "available comparisons retained");
  }

  private static boolean sameFold(JsonNode left, JsonNode right) {
    return left.get("periodId").equals(right.get("periodId"))
        && left.get("cutoff").equals(right.get("cutoff"));
  }

  private static String band(DevelopmentDiagnostics.Scored row) {
    return row.window().days() < 8 ? "1-7" : row.window().days() < 15 ? "8-14" : "15+";
  }

  private static String rowDigest(PollCsv.Poll poll) {
    return HexFormat.of()
        .formatHex(
            sha256()
                .digest(String.join(",", poll.raw().values()).getBytes(StandardCharsets.UTF_8)));
  }
}

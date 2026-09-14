package se.swedishpolls.publication;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the shipped model freeze against its registration in {@code
 * docs/validation/publication-settings.json}: every field of the resource, nested fields included,
 * is either copied from registered evidence or follows from a written derivation. A new field with
 * neither is a setting that reached a running publication without a basis.
 *
 * <p>This reads the repository's evidence, which a running application never does, so it lives with
 * the tests rather than beside {@link ModelFreeze}.
 */
final class FreezeRegistration {
  private static final Path EVIDENCE = Path.of("docs", "validation");
  private static final Path REGISTRATION = EVIDENCE.resolve("publication-settings.json");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private FreezeRegistration() {}

  /** One field the registration does not account for, or accounts for with another value. */
  record Failure(String path, String detail) {
    @Override
    public String toString() {
      return path + ": " + detail;
    }
  }

  /** Every unaccounted-for and disagreeing field of {@code freeze}, in the order it is written. */
  static List<Failure> check(JsonNode freeze) {
    final Map<String, List<JsonNode>> shipped = fields(freeze);
    final List<Failure> failures = new ArrayList<>();
    final Set<String> registered = new LinkedHashSet<>();
    for (final JsonNode setting : read(REGISTRATION).get("settings")) {
      final String path = setting.get("path").asString();
      registered.add(path);
      final List<JsonNode> values = shipped.get(path);
      if (values == null) {
        failures.add(new Failure(path, "registered but not shipped"));
      } else if (setting.has("source")) {
        failures.addAll(disagreements(path, values, setting.get("source").asString()));
      } else if (!setting.has("derivation")) {
        failures.add(new Failure(path, "neither a source nor a derivation"));
      }
    }
    for (final String path : shipped.keySet()) {
      if (!registered.contains(path)) {
        failures.add(new Failure(path, "shipped without a registered source or derivation"));
      }
    }
    return List.copyOf(failures);
  }

  /** Compares the shipped values with the evidence the pointer names, element by element. */
  private static List<Failure> disagreements(String path, List<JsonNode> values, String pointer) {
    final String[] parts = pointer.split("#", 2);
    final JsonNode evidence = read(EVIDENCE.resolve(parts[0]));
    final List<Failure> failures = new ArrayList<>();
    for (int index = 0; index < values.size(); index++) {
      final JsonNode registered = evidence.at(parts[1].replace("{}", Integer.toString(index)));
      if (registered.isMissingNode()) {
        failures.add(new Failure(path, "no evidence at " + pointer));
      } else if (!same(values.get(index), registered)) {
        failures.add(
            new Failure(
                path, "ships " + values.get(index) + ", " + pointer + " registers " + registered));
      }
    }
    return failures;
  }

  /**
   * Every leaf of the resource, as a path to the values written there. An array of objects
   * contributes one path per field, carrying one value per element, so a field added inside an
   * element is a leaf of its own rather than part of an opaque array.
   */
  private static Map<String, List<JsonNode>> fields(JsonNode node) {
    final Map<String, List<JsonNode>> leaves = new LinkedHashMap<>();
    collect(node, "", leaves);
    return leaves;
  }

  private static void collect(JsonNode node, String path, Map<String, List<JsonNode>> leaves) {
    if (node.isObject()) {
      for (final Map.Entry<String, JsonNode> field : node.properties()) {
        collect(
            field.getValue(),
            path.isEmpty() ? field.getKey() : path + "/" + field.getKey(),
            leaves);
      }
    } else if (node.isArray() && !node.isEmpty() && node.get(0).isObject()) {
      for (final JsonNode element : node) {
        collect(element, path + "[]", leaves);
      }
    } else {
      leaves.computeIfAbsent(path, key -> new ArrayList<>()).add(node);
    }
  }

  /** Numbers compare by value, so a resource may write 0.0001 where evidence writes 1.0E-4. */
  private static boolean same(JsonNode shipped, JsonNode registered) {
    if (shipped.isNumber() && registered.isNumber()) {
      return shipped.doubleValue() == registered.doubleValue();
    }
    if (shipped.isArray() && registered.isArray()) {
      if (shipped.size() != registered.size()) {
        return false;
      }
      for (int index = 0; index < shipped.size(); index++) {
        if (!same(shipped.get(index), registered.get(index))) {
          return false;
        }
      }
      return true;
    }
    return shipped.equals(registered);
  }

  private static JsonNode read(Path file) {
    try {
      return JSON.readTree(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

package se.swedishpolls.web;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Two disjoint selections in the fixed eight-party roster order. */
public record CoalitionSelection(List<String> a, List<String> b) {
  public static final List<String> ROSTER = se.swedishpolls.source.Roster.COALITION_PARTIES;

  public CoalitionSelection {
    a = canonical(a, "a");
    b = canonical(b, "b");
    if (a.stream().anyMatch(b::contains)) {
      throw new Invalid("b", "A party cannot belong to both coalitions.");
    }
  }

  @Override
  public List<String> a() {
    return List.copyOf(a);
  }

  @Override
  public List<String> b() {
    return List.copyOf(b);
  }

  public static CoalitionSelection parse(String a, String b) {
    return new CoalitionSelection(tokens(a, "a"), tokens(b, "b"));
  }

  public static CoalitionSelection preset() {
    return parse("S,V,C,MP", "M,KD,L,SD");
  }

  private static List<String> tokens(String value, String field) {
    if (value == null) {
      throw new Invalid(field, "Both a and b are required; an empty value selects no parties.");
    }
    return value.isEmpty() ? List.of() : List.of(value.split(",", -1));
  }

  private static List<String> canonical(List<String> parties, String field) {
    final Set<String> selected = new HashSet<>();
    for (final String party : parties) {
      if (!ROSTER.contains(party) || !selected.add(party)) {
        throw new Invalid(
            field,
            "Use each uppercase eight-party code at most once, without spaces or empty tokens.");
      }
    }
    return ROSTER.stream().filter(selected::contains).toList();
  }

  public static int mask(List<String> parties) {
    int mask = 0;
    for (final String party : canonical(parties, "selection")) {
      mask |= 1 << ROSTER.indexOf(party);
    }
    return mask;
  }

  public static final class Invalid extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final String field;

    public Invalid(String field, String message) {
      super(message);
      this.field = field;
    }

    public String field() {
      return field;
    }
  }
}

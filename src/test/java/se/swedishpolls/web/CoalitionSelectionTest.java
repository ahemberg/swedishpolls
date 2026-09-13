package se.swedishpolls.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoalitionSelectionTest {
  @Test
  void all6561ThreeWayAssignmentsRoundTripToCanonicalDisjointSubsets() {
    for (int assignment = 0; assignment < 6561; assignment++) {
      int remaining = assignment;
      final List<String> a = new ArrayList<>();
      final List<String> b = new ArrayList<>();
      for (final String party : CoalitionSelection.ROSTER) {
        if (remaining % 3 == 1) a.add(party);
        if (remaining % 3 == 2) b.add(party);
        remaining /= 3;
      }
      final CoalitionSelection parsed =
          CoalitionSelection.parse(String.join(",", a.reversed()), String.join(",", b.reversed()));
      assertEquals(a, parsed.a());
      assertEquals(b, parsed.b());
      assertEquals(0, CoalitionSelection.mask(parsed.a()) & CoalitionSelection.mask(parsed.b()));
      assertEquals(a.size(), Integer.bitCount(CoalitionSelection.mask(parsed.a())));
      assertEquals(b.size(), Integer.bitCount(CoalitionSelection.mask(parsed.b())));
    }
  }
}

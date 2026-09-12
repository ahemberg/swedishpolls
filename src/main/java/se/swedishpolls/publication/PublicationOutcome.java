package se.swedishpolls.publication;

import java.util.Locale;

/**
 * What one publication attempt did. Storage records the lower-case name, so the value is owned
 * beside the publication rather than by the worker that produces it.
 */
public enum PublicationOutcome {
  PUBLISHED,
  UNCHANGED,
  BLOCKED,
  FAILED,
  BUSY;

  public String stored() {
    return name().toLowerCase(Locale.ROOT);
  }
}

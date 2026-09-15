import { ALL_PARTIES } from "./timeline-controls";

/**
 * Which parties a chart draws.
 *
 * Both charts offer the same two controls, and they resolve the same way: isolation wins over the
 * toggles, so choosing one party shows that party whatever is hidden underneath, and clearing the
 * isolation restores the toggles rather than resetting them.
 */

/** A party toggled in or out of the hidden set. */
function withoutParty(hidden: readonly string[], component: string): readonly string[] {
  if (hidden.includes(component)) {
    return hidden.filter((entry) => entry !== component);
  }
  return [...hidden, component];
}

/** The components left after the isolation and the toggles, in the order they were offered. */
function visibleParties(
  components: readonly string[],
  isolated: string,
  hidden: readonly string[],
): readonly string[] {
  if (isolated !== ALL_PARTIES) {
    return components.filter((component) => component === isolated);
  }
  return components.filter((component) => !hidden.includes(component));
}

export { visibleParties, withoutParty };

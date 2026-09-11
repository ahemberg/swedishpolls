import type { Bootstrap } from "./bootstrap";

/**
 * The display name of a published key.
 *
 * The server resolves every label once, into the bootstrap, so the page and the markup Spring
 * already rendered name a party the same way. Falling back to the key keeps a roster change
 * legible instead of blank: an unlabelled component reads as its key rather than disappearing.
 */

/** A modelled component: a party, the OTHER aggregate, or the cross-period remainder. */
function componentName(page: Bootstrap, component: string): string {
  return page.labels[component] ?? component;
}

/** One of the ten approved coalition memberships. */
function coalitionName(page: Bootstrap, id: string): string {
  return componentName(page, `coalition.${id}`);
}

/** The parties a coalition counts, named rather than left as keys. */
function memberNames(page: Bootstrap, parties: readonly string[]): string {
  return parties.map((party) => componentName(page, party)).join(", ");
}

export { coalitionName, componentName, memberNames };

import type { Bootstrap } from "./bootstrap";
import { named } from "./text";

/**
 * The display name of a published key.
 *
 * The names live in the page-text catalogue, beside the chrome that surrounds them, so one lookup
 * answers both. Falling back to the key keeps a roster change legible instead of blank: an
 * unlabelled component reads as its key rather than disappearing.
 */

/** A modelled component: a party, the OTHER aggregate, or the cross-period remainder. */
function componentName(page: Bootstrap, component: string): string {
  return named(page.language, `component.${component}`, component);
}

/** One of the ten approved coalition memberships. */
function coalitionName(page: Bootstrap, id: string): string {
  return named(page.language, `coalition.${id}`, id);
}

/** The parties a coalition counts, named rather than left as keys. */
function memberNames(page: Bootstrap, parties: readonly string[]): string {
  return parties.map((party) => componentName(page, party)).join(", ");
}

export { coalitionName, componentName, memberNames };

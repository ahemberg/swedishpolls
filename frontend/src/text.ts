import type { Language } from "./bootstrap";
import en from "./text.en.json" with { type: "json" };
import sv from "./text.sv.json" with { type: "json" };

/**
 * The page chrome of both languages: navigation, section titles, readouts and the method footer,
 * together with the display names of components and coalitions.
 *
 * The wording lives with the pages that word it, and the key union is derived from the catalogue
 * itself, so a key no language carries is a compile error rather than a blank page. The two
 * assignments below pin the catalogues to each other's key sets: a key present in one language and
 * missing in the other fails `tsc --noEmit`.
 *
 * There is no i18n library behind this. The catalogue has no plurals, flat keys and plain
 * `{token}` substitution; language comes from the URL path, so nothing detects or switches at
 * runtime. If a third language or real plural rules arrive, `react-i18next` with
 * `i18next-cli types --ci` is the replacement, and flat keys migrate into it mechanically.
 */

type PageTextKey = keyof typeof sv;

const SWEDISH: Record<keyof typeof en, string> = sv;
const ENGLISH: Record<PageTextKey, string> = en;

const CATALOGUE: Readonly<Record<Language, Record<PageTextKey, string>>> = {
  sv: SWEDISH,
  en: ENGLISH,
};

/** A `{token}` the caller left unfilled, which types cannot catch and a page must never show. */
const UNFILLED = /\{[a-zA-Z]+\}/;

function fill(template: string, values?: Record<string, string>): string {
  if (values === undefined) {
    return template;
  }
  return Object.entries(values).reduce(
    (text, [token, value]) => text.replaceAll(`{${token}}`, value),
    template,
  );
}

/** One wording, by key, with optional `{token}` substitutions. */
function translate(language: Language, key: PageTextKey, values?: Record<string, string>): string {
  const template = CATALOGUE[language][key];
  if (template === undefined) {
    throw new Error(`No ${language} page text for ${key}`);
  }
  const filled = fill(template, values);
  if (UNFILLED.test(filled)) {
    throw new Error(`Unfilled token in ${language} page text for ${key}: ${filled}`);
  }
  return filled;
}

/**
 * The display name of a published key, which is data rather than a literal a component writes.
 * Falling back to the key keeps a roster change legible: an unlabelled component reads as its key
 * rather than disappearing.
 */
function named(language: Language, key: string, fallback: string): string {
  const names: Readonly<Record<string, string>> = CATALOGUE[language];
  return names[key] ?? fallback;
}

export { named, type PageTextKey, translate };

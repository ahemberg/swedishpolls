import type { Language, Translate } from "./bootstrap";

/**
 * The same number and date wording the server applies. Both sides read the same locale and the
 * same rounding rule, so a figure does not change shape the moment the script mounts.
 */

const SWEDISH: Language = "sv";

/** The zone published timestamps are shown in, the same one the server formats with. */
const SITE_ZONE = "Europe/Stockholm";

/** Probabilities are stored as fractions and published as whole percent. */
const PERCENT_SCALE = 100;
const CERTAIN = 1;
const DECIMALS = 1;
const ALMOST_CERTAIN = 99;
const LOWER_BOUND = 0.01;
const UPPER_BOUND = 0.99;

/**
 * A probability never reads as 0 or 100: a Monte Carlo estimate of zero draws is not proof of
 * impossibility, so the display says less than or greater than.
 */
function bounded(value: number): string {
  if (value < LOWER_BOUND) {
    return `<${CERTAIN}`;
  }
  if (value > UPPER_BOUND) {
    return `>${ALMOST_CERTAIN}`;
  }
  return String(Math.round(value * PERCENT_SCALE));
}

/** A support or seat figure at one decimal, with the language's decimal separator. */
function decimal(value: number, language: Language): string {
  const plain = value.toFixed(DECIMALS);
  if (language === SWEDISH) {
    return plain.replace(".", ",");
  }
  return plain;
}

/** Swedish writes a space before the percent sign; English writes none. */
function percent(number: string, language: Language): string {
  if (language === SWEDISH) {
    return `${number} %`;
  }
  return `${number}%`;
}

/**
 * A probability as whole percent, never rounded into a claim of certainty: below one percent and
 * above ninety-nine read as bounds.
 */
function probability(value: number, language: Language): string {
  return percent(bounded(value), language);
}

/** An interval level as whole percent: the stored 0.95 reads as 95. */
function level(fraction: number): string {
  return String(Math.round(fraction * PERCENT_SCALE));
}

/** A stored ISO date, spelled out in the page's locale. */
function date(iso: string, locale: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString(locale, {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  });
}

/** A stored instant, in the site's own zone rather than the reader's, matching the server. */
function timestamp(iso: string, locale: string): string {
  return new Date(iso).toLocaleString(locale, {
    day: "numeric",
    month: "long",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    timeZone: SITE_ZONE,
  });
}

/** A stored ISO date, short enough for an axis or a readout. */
function shortDate(iso: string, locale: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString(locale, {
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: "UTC",
  });
}

/** A sample size or other count, grouped the way the language groups thousands. */
function count(value: number, locale: string): string {
  return value.toLocaleString(locale);
}

/** The CSS custom property carrying a component's approved colour. */
function colour(component: string): string {
  return `var(--c-${component})`;
}

function interval(
  [mean, lower, upper]: readonly (number | null | undefined)[],
  language: Language,
  t: Translate,
): string {
  if (typeof mean !== "number" || typeof lower !== "number" || typeof upper !== "number") {
    return t("estimate.unavailable");
  }
  return t("coalitionHistory.interval", {
    mean: decimal(mean, language),
    lower: decimal(lower, language),
    upper: decimal(upper, language),
  });
}

function share(value: number | null, language: Language, t: Translate): string {
  if (typeof value === "number") {
    return percent(decimal(value, language), language);
  }
  return t("estimate.unavailable");
}

export {
  colour,
  count,
  date,
  decimal,
  interval,
  level,
  percent,
  probability,
  share,
  shortDate,
  timestamp,
};

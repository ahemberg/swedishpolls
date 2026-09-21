import type { Bootstrap, NavigationEntry } from "./bootstrap";

const OVERVIEW = "OVERVIEW";
const PARTY = "PARTY";
const SEATS = "SEATS";
const COALITIONS = "COALITIONS";
const POLLSTERS = "POLLSTERS";
const POLLS = "POLLS";
const METHOD = "METHOD";

const FAMILIES = [OVERVIEW, PARTY, SEATS, COALITIONS, POLLSTERS, POLLS, METHOD] as const;
const FAMILY_SET: ReadonlySet<string> = new Set(FAMILIES);
const RANGE_IDS: ReadonlySet<string> = new Set(["oneYear", "sinceElection", "fourYears", "all"]);

type Family = (typeof FAMILIES)[number];
type Language = "sv" | "en";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isFamily(value: unknown): value is Family {
  return typeof value === "string" && FAMILY_SET.has(value);
}

function isLanguage(value: unknown): value is Language {
  return value === "sv" || value === "en";
}

function isStringRecord(value: unknown): value is Readonly<Record<string, string>> {
  return isRecord(value) && Object.values(value).every((entry) => typeof entry === "string");
}

function isAlternates(value: unknown): boolean {
  return isStringRecord(value) && typeof value.sv === "string" && typeof value.en === "string";
}

function isRangeId(value: unknown): boolean {
  return typeof value === "string" && RANGE_IDS.has(value);
}

function isNavigation(value: unknown): value is readonly NavigationEntry[] {
  return (
    Array.isArray(value) &&
    value.every(
      (entry) =>
        isRecord(entry) &&
        isFamily(entry.family) &&
        typeof entry.path === "string" &&
        typeof entry.current === "boolean",
    )
  );
}

function isOptional(value: unknown, predicate: (candidate: unknown) => boolean): boolean {
  return value === undefined || predicate(value);
}

function isNullableString(value: unknown): boolean {
  return value === null || typeof value === "string";
}

function isApi(value: unknown): boolean {
  return (
    isRecord(value) &&
    typeof value.base === "string" &&
    typeof value.publication === "string" &&
    isLanguage(value.language)
  );
}

function isRange(value: unknown): boolean {
  return (
    isRecord(value) &&
    isRangeId(value.id) &&
    typeof value.from === "string" &&
    typeof value.to === "string" &&
    typeof value.step === "number" &&
    (typeof value.year === "number" || value.year === null)
  );
}

function isPageData(value: unknown, family: Family): boolean {
  if (value === undefined) {
    return true;
  }
  if (!isRecord(value)) {
    return false;
  }
  if (family === POLLSTERS) {
    return isRecord(value.pollsters);
  }
  if (family === METHOD) {
    return isRecord(value.latest) && isRecord(value.method);
  }
  if (family === POLLS) {
    return false;
  }
  return (
    isRecord(value.latest) &&
    isRecord(value.seats) &&
    isRecord(value.coalitions) &&
    isOptional(value.coalitionHistory, isRecord) &&
    isOptional(value.elections, isRecord) &&
    isOptional(value.history, isRecord) &&
    isOptional(value.polls, isRecord) &&
    isOptional(value.party, isRecord)
  );
}

function hasValidOptionalFields(value: Record<string, unknown>, family: Family): boolean {
  return (
    isOptional(value.permanent, (entry) => typeof entry === "boolean") &&
    isOptional(value.headlineDate, (entry) => typeof entry === "string") &&
    isOptional(value.lastSourceCheck, isNullableString) &&
    isOptional(value.publication, isRecord) &&
    isOptional(value.source, isRecord) &&
    isOptional(value.noSource, (entry) => typeof entry === "boolean") &&
    isOptional(value.sourcePolls, isRecord) &&
    isOptional(value.sourceChart, isRecord) &&
    isOptional(value.api, isApi) &&
    isOptional(value.approximatedElection, (entry) => typeof entry === "number") &&
    isPageData(value.data, family) &&
    isOptional(value.pollTable, isRecord) &&
    isOptional(value.coalitionLinkError, isRecord) &&
    isOptional(value.coalitionShare, (entry) => typeof entry === "string") &&
    isOptional(value.customCoalitionSelection, (entry) => typeof entry === "boolean") &&
    isOptional(value.ranges, (entry) => Array.isArray(entry) && entry.every(isRange)) &&
    isOptional(value.defaultRange, isRangeId)
  );
}

function isBootstrap(value: unknown): value is Bootstrap {
  if (!(isRecord(value) && isRecord(value.route) && isRecord(value.site))) {
    return false;
  }
  if (
    !(
      isLanguage(value.language) &&
      typeof value.locale === "string" &&
      isFamily(value.route.family) &&
      typeof value.route.path === "string" &&
      (typeof value.route.parameter === "string" || value.route.parameter === null) &&
      isAlternates(value.alternates) &&
      isNavigation(value.navigation) &&
      typeof value.site.name === "string" &&
      typeof value.site.origin === "string" &&
      isStringRecord(value.partyPaths)
    )
  ) {
    return false;
  }
  return hasValidOptionalFields(value, value.route.family);
}

/** Parse the server bootstrap and reject an incompatible shell before React reads it. */
export function parseBootstrap(text: string): Bootstrap {
  const value: unknown = JSON.parse(text);
  if (!isBootstrap(value)) {
    throw new Error("Invalid page bootstrap");
  }
  return value;
}

export type { Family, Language };
export { COALITIONS, METHOD, OVERVIEW, PARTY, POLLS, POLLSTERS, SEATS };

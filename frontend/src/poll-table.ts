/**
 * The browsable poll table: one filtered page of the publication's pinned source snapshot.
 *
 * These are archived observations, not model output, which is why they are not part of the
 * `PageData` every results page carries. A share the source never reported is null here and stays
 * null all the way to the cell: nothing fills it with a zero or a neighbouring institute's figure.
 */

/** A list filter, or null when the reader declared none. */
function joined(values: readonly string[]): string | null {
  if (values.length === 0) {
    return null;
  }
  return values.join(",");
}

/** A flag only travels when it is on; its absence already means false. */
function flag(on: boolean): string | null {
  if (on) {
    return "true";
  }
  return null;
}

/** The first page is the default, so it is never written into the query. */
function beyondFirst(page: number): string | null {
  if (page > 1) {
    return String(page);
  }
  return null;
}

/** One archived poll row, at the precision the source published it. */
export interface PollRow {
  readonly pollId: string;
  readonly institute: string;
  readonly company: string | null;
  readonly methodEra: string | null;
  readonly methodEvidence: string | null;
  readonly surveyType: string | null;
  readonly publicationDate: string | null;
  readonly collectionFrom: string | null;
  readonly collectionTo: string | null;
  readonly approximatePeriod: boolean;
  readonly sampleSize: number | null;
  readonly denominatorNote: string | null;
  readonly coveragePeriod: string | null;
  readonly shares: Readonly<Record<string, number | null>>;
  readonly displayShares: Readonly<Record<string, string | null>>;
  readonly other: number | null;
  readonly displayOther: string | null;
  readonly eligible: boolean;
  readonly exclusionReasons: readonly string[];
}

/** The filter one poll request declares. An absent bound or an empty list is not a filter. */
export interface PollFilterState {
  readonly from: string | null;
  readonly to: string | null;
  readonly institute: readonly string[];
  readonly party: readonly string[];
  readonly coveragePeriod: string | null;
  readonly includeExcluded: boolean;
}

/** One rejected query parameter, named so the page can say which filter it ignored. */
export interface InvalidFilter {
  readonly name: string;
  readonly reason: string;
}

/** What the controls may offer, taken from the publication this page already resolved. */
export interface PollOptions {
  readonly institutes: readonly string[];
  readonly coveragePeriods: readonly {
    readonly id: string;
    readonly roster: readonly string[];
  }[];
  readonly parties: readonly string[];
}

/**
 * One filtered page of the pinned snapshot.
 *
 * It is not part of {@link PageData}: this page publishes source observations rather than the
 * estimate, the allocation and the memberships that every results page carries.
 */
export interface PollTable {
  readonly total: number;
  readonly page: number;
  readonly pageSize: number;
  readonly pages: number;
  readonly csv: string;
  readonly columns: readonly string[];
  readonly filters: PollFilterState;
  readonly invalid: readonly InvalidFilter[];
  readonly options: PollOptions;
  readonly labels: Readonly<Record<string, string>>;
  readonly polls: readonly PollRow[];
}

/** A filter state as query parameters, leaving out every filter the reader did not declare. */
export function pollQuery(filters: PollFilterState, page: number): URLSearchParams {
  const declared: readonly (readonly [string, string | null])[] = [
    ["from", filters.from],
    ["to", filters.to],
    ["institute", joined(filters.institute)],
    ["party", joined(filters.party)],
    ["coveragePeriod", filters.coveragePeriod],
    ["includeExcluded", flag(filters.includeExcluded)],
    ["page", beyondFirst(page)],
  ];
  const parameters = new URLSearchParams();
  for (const [name, value] of declared) {
    if (value !== null) {
      parameters.set(name, value);
    }
  }
  return parameters;
}

/**
 * Whether a reported share sits outside the modeled roster of its own coverage period.
 *
 * The frozen poll response carries no such field, so the rule is applied here against the rosters
 * the page was given. It is the same rule the server writes into its own markup: the number is a
 * real observation either way, and the page has to say which of the two it is.
 */
export function outsideRoster(row: PollRow, options: PollOptions, component: string): boolean {
  if (row.shares[component] === undefined || row.shares[component] === null) {
    return false;
  }
  const period = options.coveragePeriods.find((entry) => entry.id === row.coveragePeriod);
  if (period === undefined) {
    return true;
  }
  return !period.roster.includes(component);
}

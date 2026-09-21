import type { Family, Language } from "./bootstrap-parser";
import { parseBootstrap } from "./bootstrap-parser";

import type { CoalitionLinkError } from "./coalition-history";
import type { PageData } from "./page-data";
import type { PollTable } from "./poll-table";
import type { SourceChartData } from "./source-chart";
import { type PageTextKey, translate } from "./text";

/**
 * The page's resolved publication, as Spring wrote it into the document.
 *
 * Reading it is the whole data layer for the first paint: the server already pinned one
 * publication, so nothing here has to resolve one again, and every later request carries that
 * publication id. The wording comes from the same object, so the rendered page and the server's
 * own HTML cannot word a number differently.
 */

/** The element Spring writes the bootstrap into. */
const BOOTSTRAP_ELEMENT = "site-bootstrap";

export type { Family, Language } from "./bootstrap-parser";

/** The windows a timeline offers, as the server names them. */
export type RangeId = "oneYear" | "sinceElection" | "fourYears" | "all";

/** One wording lookup, with optional {token} substitutions. */
export type Translate = (key: PageTextKey, values?: Record<string, string>) => string;

export interface Interval {
  readonly component: string;
  readonly mean: number | null;
  readonly lower: number | null;
  readonly upper: number | null;
}

export interface CoveragePeriod {
  readonly id: string;
  readonly from: string;
  readonly to: string | null;
  readonly roster: readonly string[];
  readonly otherMembers: readonly string[];
  readonly individualFi: boolean;
  readonly supportValidated: boolean;
  readonly decision: string | null;
  readonly otherAlsoIncludes: string;
}

export interface Latest {
  readonly lastFieldworkDate: string;
  readonly intervalLevel: number;
  readonly coveragePeriod: string;
  readonly coveragePeriods: readonly CoveragePeriod[];
  readonly components: readonly Interval[];
  readonly unavailable: Readonly<Record<string, { readonly reason: string }>>;
}

export interface Series {
  readonly component: string;
  readonly mean: readonly (number | null)[];
  readonly lower: readonly (number | null)[];
  readonly upper: readonly (number | null)[];
}

export interface Boundary {
  readonly kind: string;
  readonly date: string;
  readonly periodId: string;
  readonly supportValidated: boolean;
  readonly note: string;
}

export interface History {
  readonly intervalLevel: number;
  readonly range: {
    readonly from: string;
    readonly to: string;
    readonly step: number;
  };
  readonly dates: readonly string[];
  readonly coveragePeriodByDate: readonly (string | null)[];
  readonly series: readonly Series[];
  readonly boundaries: readonly Boundary[];
}

export interface Election {
  readonly electionDate: string;
  readonly electionYear: number;
  readonly validVotes: number;
  readonly results: Readonly<Record<string, { readonly votes: number }>>;
}

export interface Elections {
  readonly note: string;
  readonly elections: readonly Election[];
}

export interface Poll {
  readonly pollId: string;
  readonly institute: string;
  readonly methodEra: string;
  readonly collectionFrom: string | null;
  readonly collectionTo: string | null;
  readonly approximatePeriod: boolean;
  readonly sampleSize: number | null;
  readonly coveragePeriod: string;
  readonly shares: Readonly<Record<string, number | null>>;
  readonly other: number | null;
}

export interface Polls {
  readonly total: number;
  readonly polls: readonly Poll[];
  readonly labels: Readonly<Record<string, string>>;
}

export interface PartyObservation {
  readonly pollId: string;
  readonly institute: string;
  readonly collectionFrom: string | null;
  readonly collectionTo: string | null;
  readonly approximatePeriod: boolean;
  readonly sampleSize: number | null;
  readonly share: number;
  readonly coveragePeriod: string | null;
  readonly eligible: boolean;
  readonly modeled: boolean;
  readonly exclusionReasons: readonly string[];
}

export interface HouseEffect {
  readonly institute: string;
  readonly mean: number;
  readonly lower: number;
  readonly upper: number;
  readonly shrunk: boolean;
}

export interface PartyData {
  readonly component: string;
  readonly historicalOnly: boolean;
  readonly estimate: Interval;
  readonly thresholdProbability: number | null;
  readonly pointSeats: number | null;
  readonly observations: readonly PartyObservation[];
  readonly houseEffects: {
    readonly reference: string;
    readonly electionCycle: string | null;
    readonly effects: readonly HouseEffect[];
  };
}

export interface Publication {
  readonly publicationId: string;
  readonly publishedAt: string;
  readonly sourceCheckedAt: string;
  readonly lastFieldworkDate: string;
  readonly stale: boolean;
  readonly staleSince: string | null;
  readonly permalink: string;
  readonly history: string;
  readonly modelRun: {
    readonly runId: string;
    readonly codeVersion: string;
    readonly seed: number;
  };
  readonly snapshot: {
    readonly snapshotId: number;
    readonly sha256: string;
    readonly sourceUrl: string;
    readonly capturedAt: string;
  };
  readonly assets: Readonly<Record<string, Readonly<Record<Language, string | null>>>>;
}

export interface SourceSnapshot {
  readonly snapshotId: number;
  readonly sha256: string;
  readonly capturedAt: string;
}

export interface RangeSpec {
  readonly id: RangeId;
  readonly from: string;
  readonly to: string;
  readonly step: number;
  readonly year: number | null;
}

export interface NavigationEntry {
  readonly family: Family;
  readonly path: string;
  readonly current: boolean;
}

/**
 * What a results page carries.
 *
 * The estimate, the allocation and the memberships come from one publication and are on every
 * page that shows numbers. The timeline, the election dots and the poll list belong to the
 * overview alone, so they are absent rather than empty on the seats and coalitions pages.
 */
export interface Bootstrap {
  readonly language: Language;
  readonly locale: string;
  readonly route: {
    readonly family: Family;
    readonly path: string;
    readonly parameter: string | null;
  };
  readonly alternates: Readonly<Record<Language, string>>;
  readonly navigation: readonly NavigationEntry[];
  readonly site: { readonly name: string; readonly origin: string };
  readonly partyPaths: Readonly<Record<string, string>>;
  readonly permanent?: boolean;
  readonly headlineDate?: string;
  readonly lastSourceCheck?: string | null;
  readonly publication?: Publication;
  readonly source?: SourceSnapshot;
  readonly noSource?: boolean;
  readonly sourcePolls?: Polls;
  readonly sourceChart?: SourceChartData;
  readonly api?: {
    readonly base: string;
    readonly publication: string;
    readonly language: Language;
  };
  readonly approximatedElection?: number;
  readonly data?: PageData;
  readonly pollTable?: PollTable;
  readonly coalitionLinkError?: CoalitionLinkError;
  readonly coalitionShare?: string;
  readonly customCoalitionSelection?: boolean;
  readonly ranges?: readonly RangeSpec[];
  readonly defaultRange?: RangeId;
}

/**
 * The publication-wide summary cards a page can offer. A party page is not here: it offers its
 * own party's card, whose kind is built from the component rather than chosen from a fixed set.
 */
export type CardKind = "overview" | "seats" | "coalitions";

/** The element the script replaces. Its server-rendered children are the pre-script page. */
export const MOUNT_ELEMENT = "site-root";

/**
 * The day the page's numbers claim. The server resolves it from the estimate's own last day, which
 * is not always the snapshot's last fieldwork date, and it is absent only when nothing is published.
 */
export function headlineDate(page: Bootstrap): string {
  return page.headlineDate ?? "";
}

export function readBootstrap(): Bootstrap {
  const element = document.getElementById(BOOTSTRAP_ELEMENT);
  if (element === null || element.textContent === null) {
    throw new Error("The page carries no bootstrap");
  }
  return parseBootstrap(element.textContent);
}

/** One wording, by key, in the language the page was routed to. */
export function translator(page: Bootstrap): Translate {
  return (key, values) => translate(page.language, key, values);
}

export { COALITIONS, METHOD, OVERVIEW, PARTY, POLLS, POLLSTERS, SEATS } from "./bootstrap-parser";
export type { PageData, ResultsPageData } from "./page-data";
export { isResultsData, latestData, methodData, pollstersData, resultsData } from "./page-data";

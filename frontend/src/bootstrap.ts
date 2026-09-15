import type { CoalitionResults, Seats } from "./chamber";
import type { CoalitionHistoryData, CoalitionLinkError } from "./coalition-history";
import type { PollstersData } from "./institutes";
import type { MethodData } from "./method";
import type { PollTable } from "./poll-table";
import type { SourceChartData } from "./source-chart.ts";

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

export type Language = "sv" | "en";

/** One wording lookup, with optional {token} substitutions. */
export type Translate = (key: string, values?: Record<string, string>) => string;

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
  readonly id: string;
  readonly from: string;
  readonly to: string;
  readonly step: number;
  readonly year: number | null;
}

export interface NavigationEntry {
  readonly family: string;
  readonly label: string;
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
export interface PageData {
  readonly coalitionHistory?: CoalitionHistoryData;
  readonly latest: Latest;
  readonly seats: Seats;
  readonly coalitions: CoalitionResults;
  readonly elections?: Elections;
  readonly history?: History;
  readonly polls?: Polls;
  readonly party?: PartyData;
  readonly pollsters?: PollstersData;
  readonly method?: MethodData;
}

export interface Bootstrap {
  readonly language: Language;
  readonly locale: string;
  readonly route: {
    readonly family: string;
    readonly path: string;
    readonly parameter: string | null;
  };
  readonly alternates: Readonly<Record<Language, string>>;
  readonly navigation: readonly NavigationEntry[];
  readonly site: { readonly name: string; readonly origin: string };
  readonly text: Readonly<Record<string, string>>;
  readonly labels: Readonly<Record<string, string>>;
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
  readonly ranges?: readonly RangeSpec[];
  readonly defaultRange?: string;
}

/** The page families this build renders, as the server names them. */
export const OVERVIEW = "OVERVIEW";
export const SEATS = "SEATS";
export const COALITIONS = "COALITIONS";

/** The individual party page family. */
export const PARTY = "PARTY";

/** The browsable poll table, which carries source observations rather than published estimates. */
export const POLLS = "POLLS";

/** The pollsters page, which carries institute metadata and house effects rather than estimates. */
export const POLLSTERS = "POLLSTERS";

/** The method page, which carries the explanation and the frozen numbers rather than results. */
export const METHOD = "METHOD";

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
  return JSON.parse(element.textContent) as Bootstrap;
}

/** One wording, by key. A missing key is a bug in the page, not something to paper over. */
export function translator(page: Bootstrap): Translate {
  return (key, values) => {
    const template = page.text[key];
    if (template === undefined) {
      throw new Error(`No ${page.language} page text for ${key}`);
    }
    if (values === undefined) {
      return template;
    }
    return Object.entries(values).reduce(
      (filled, [token, value]) => filled.replaceAll(`{${token}}`, value),
      template,
    );
  };
}

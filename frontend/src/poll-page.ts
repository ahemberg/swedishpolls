import type { Bootstrap, CoveragePeriod, Polls, SourceSnapshot } from "./bootstrap";
import { POLLS } from "./bootstrap";
import type { InvalidFilter, PollFilterState, PollOptions, PollRow, PollTable } from "./poll-table";
import { pollQuery } from "./poll-table";
import type { SourceChartData } from "./source-chart";
import { named } from "./text";
import { fetchJson, RequestError } from "./useFetched";

const COMPONENTS = ["S", "M", "SD", "V", "C", "KD", "L", "MP", "FI"];
const LATEST_POLLS = 8;
const NOT_FOUND = 404;

// The live poll response permits missing source fields. The frozen example fills every field.
type ApiPoll = Omit<PollRow, "displayShares" | "displayOther" | "unmodeled">;
interface PollPageResponse {
  readonly filters: PollFilterState;
  readonly total: number;
  readonly page: number;
  readonly pageSize: number;
  readonly csv: string;
  readonly polls: readonly ApiPoll[];
}
interface SourcePollsResponse extends PollPageResponse {
  readonly snapshot: { readonly id: number; readonly sha256: string; readonly capturedAt: string };
  readonly institutes: readonly string[];
  readonly invalid: readonly InvalidFilter[];
}

function display(value: number | null): string | null {
  if (value === null) {
    return null;
  }
  return String(value);
}

function pollRow(poll: ApiPoll, periods: readonly CoveragePeriod[], source: boolean): PollRow {
  const roster = periods.find((period) => period.id === poll.coveragePeriod)?.roster ?? [];
  return {
    ...poll,
    displayShares: Object.fromEntries(
      Object.entries(poll.shares).map(([key, value]) => [key, display(value)]),
    ),
    displayOther: display(poll.other),
    unmodeled: COMPONENTS.filter(
      (component) =>
        !source &&
        poll.shares[component] !== null &&
        poll.shares[component] !== undefined &&
        !roster.includes(component),
    ),
  };
}

function labels(page: Bootstrap): Readonly<Record<string, string>> {
  return Object.fromEntries(
    COMPONENTS.map((component) => [
      component,
      named(page.language, `component.${component}`, component),
    ]),
  );
}

function table(
  response: PollPageResponse,
  page: Bootstrap,
  context: {
    readonly options: PollOptions;
    readonly invalid: readonly InvalidFilter[];
    readonly source: boolean;
    readonly periods: readonly CoveragePeriod[];
  },
): PollTable {
  const { options, invalid, source, periods } = context;
  let columns = response.filters.party;
  if (columns.length === 0) {
    columns = COMPONENTS;
  }
  return {
    ...response,
    source,
    invalid,
    options,
    labels: labels(page),
    pages: Math.max(1, Math.ceil(response.total / response.pageSize)),
    columns,
    polls: response.polls.map((poll) => pollRow(poll, periods, source)),
  };
}

function recent(response: PollPageResponse, page: Bootstrap): Polls {
  return {
    total: response.total,
    labels: labels(page),
    polls: response.polls.map((poll) => ({
      ...poll,
      institute: poll.institute ?? "",
      methodEra: poll.methodEra ?? "",
      coveragePeriod: poll.coveragePeriod ?? "",
    })),
  };
}

async function sourceChart(
  snapshot: SourceSnapshot,
  filters: PollFilterState,
  signal: AbortSignal,
  range?: string,
): Promise<SourceChartData | undefined> {
  const query = pollQuery(
    { ...filters, party: [], coveragePeriod: null, includeExcluded: false },
    1,
  );
  query.set("snapshot", String(snapshot.snapshotId));
  if (range !== undefined) {
    query.set("range", range);
  }
  try {
    return await fetchJson<SourceChartData>(`/source/chart?${query}`, signal);
  } catch (error) {
    if (error instanceof RequestError && error.status === NOT_FOUND) {
      return undefined;
    }
    throw error;
  }
}

async function sourcePage(
  page: Bootstrap,
  url: URL,
  signal: AbortSignal,
  range?: string,
): Promise<Bootstrap> {
  const query = new URLSearchParams();
  const isTable = page.route.family === POLLS;
  for (const name of [
    "snapshot",
    "from",
    "to",
    "institute",
    "party",
    "coveragePeriod",
    "includeExcluded",
    "page",
  ]) {
    const value = url.searchParams.get(name);
    if (value !== null && (isTable || name === "snapshot")) {
      query.set(name, value);
    }
  }
  if (!isTable) {
    query.set("pageSize", String(LATEST_POLLS));
  }
  let response: SourcePollsResponse;
  try {
    response = await fetchJson<SourcePollsResponse>(`/api/v1/source/polls?${query}`, signal);
  } catch (error) {
    if (error instanceof RequestError && error.status === NOT_FOUND && !query.has("snapshot")) {
      if (page.publication !== undefined) {
        return page;
      }
      return {
        ...page,
        noSource: true,
      };
    }
    throw error;
  }
  const source = {
    snapshotId: response.snapshot.id,
    sha256: response.snapshot.sha256,
    capturedAt: response.snapshot.capturedAt,
  };
  const chart = await sourceChart(source, response.filters, signal, range);
  let loaded: Bootstrap = {
    ...page,
    source,
    sourcePolls: recent(response, page),
  };
  if (chart !== undefined) {
    loaded = { ...loaded, sourceChart: chart };
  }
  if (!isTable) {
    return loaded;
  }
  return {
    ...loaded,
    pollTable: table(response, page, {
      options: { institutes: response.institutes, parties: [], coveragePeriods: [] },
      invalid: response.invalid,
      source: true,
      periods: [],
    }),
  };
}

function rejectedFields(error: unknown, query: URLSearchParams): readonly InvalidFilter[] {
  if (!(error instanceof RequestError) || error.body.invalid === undefined) {
    throw error;
  }
  const rejected = error.body.invalid.filter((entry) => query.has(entry.name));
  if (rejected.length === 0) {
    throw error;
  }
  return rejected;
}

async function publishedPolls({
  page,
  url,
  periods,
  institutes,
  signal,
}: {
  readonly page: Bootstrap;
  readonly url: URL;
  readonly periods: readonly CoveragePeriod[];
  readonly institutes: readonly string[];
  readonly signal: AbortSignal;
}): Promise<Bootstrap> {
  const query = new URLSearchParams(url.search);
  query.set("publication", page.api?.publication ?? "");
  query.set("language", page.language);
  const invalid: InvalidFilter[] = [];
  // Retry only fields the API explicitly rejected, retaining the rest of the declared filter.
  let response: PollPageResponse;
  for (;;) {
    try {
      response = await fetchJson<PollPageResponse>(`/api/v1/polls?${query}`, signal);
      break;
    } catch (error) {
      const rejected = rejectedFields(error, query);
      invalid.push(...rejected);
      for (const entry of rejected) {
        query.delete(entry.name);
      }
    }
  }
  const pages = Math.max(1, Math.ceil(response.total / response.pageSize));
  if (response.page > pages) {
    query.set("page", String(pages));
    response = await fetchJson<PollPageResponse>(`/api/v1/polls?${query}`, signal);
  }
  if (!query.get("party")?.trim()) {
    response = { ...response, filters: { ...response.filters, party: [] } };
  }
  return {
    ...page,
    pollTable: table(response, page, {
      options: { institutes, parties: COMPONENTS, coveragePeriods: periods },
      invalid,
      source: false,
      periods,
    }),
  };
}

export type { ApiPoll, PollPageResponse };
export { COMPONENTS, publishedPolls, recent, sourcePage };

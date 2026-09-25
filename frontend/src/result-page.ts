import type { HistoryResponse, InstitutesResponse } from "./api";
import type {
  Bootstrap,
  Elections,
  Latest,
  PartyData,
  PartyObservation,
  RangeSpec,
  ResultsPageData,
} from "./bootstrap";
import { PARTY } from "./bootstrap";
import type { PollstersData } from "./institutes";
import type { ApiPoll, PollPageResponse } from "./poll-page";
import { COMPONENTS, recent, sourcePage } from "./poll-page";

type ReadDocument = <T>(
  resource: string,
  parameters?: Readonly<Record<string, string>>,
) => Promise<T>;
interface InstitutesDocument extends InstitutesResponse {
  readonly electionCycles: readonly string[];
}

function heat(mean: number): string {
  const halfPoint = 0.5;
  const quarterPoint = 0.25;
  const maxSteps = 3;
  if (Math.abs(mean) < quarterPoint) {
    return "z";
  }
  const steps = Math.min(maxSteps, Math.floor((Math.abs(mean) + quarterPoint) / halfPoint));
  let sign = "n";
  if (mean > 0) {
    sign = "p";
  }
  return `${sign}${steps}`;
}

async function pollsters(read: ReadDocument): Promise<PollstersData> {
  const selected = await read<InstitutesDocument>("institutes");
  const cycles = selected.electionCycles ?? [selected.electionCycle];
  const documents = await Promise.all(
    cycles.map((cycle) => {
      if (cycle === selected.electionCycle) {
        return selected;
      }
      return read<InstitutesDocument>("institutes", { electionCycle: cycle });
    }),
  );
  const seen = new Set(
    documents.flatMap((document) =>
      document.institutes.flatMap((institute) =>
        institute.houseEffects.map((effect) => effect.component),
      ),
    ),
  );
  return {
    reference: selected.reference,
    institutes: selected.institutes,
    components: [
      ...COMPONENTS.filter((component) => seen.has(component)),
      ...[...seen].filter((component) => !COMPONENTS.includes(component)),
    ],
    matrices: documents.map((document) => ({
      cycle: document.electionCycle,
      rows: document.institutes.map((institute) => ({
        institute: institute.institute,
        cells: institute.houseEffects.map((effect) => ({ ...effect, heat: heat(effect.mean) })),
      })),
    })),
  };
}

function yearsBefore(iso: string, years: number): string {
  const date = new Date(`${iso}T00:00:00Z`);
  const month = date.getUTCMonth();
  date.setUTCFullYear(date.getUTCFullYear() - years);
  if (date.getUTCMonth() !== month) {
    date.setUTCDate(0);
  }
  return date.toISOString().slice(0, "YYYY-MM-DD".length);
}

function ranges(first: string, last: string, elections: Elections): readonly RangeSpec[] {
  const election = elections.elections
    .map((entry) => entry.electionDate)
    .filter((date) => date <= last)
    .sort()
    .at(-1);
  const longStep = 7;
  const yearStep = 3;
  const fourYears = 4;
  const later = (from: string) => {
    if (from < first) {
      return first;
    }
    return from;
  };
  const offered: RangeSpec[] = [
    { id: "oneYear", from: later(yearsBefore(last, 1)), to: last, step: yearStep, year: null },
  ];
  if (election !== undefined) {
    offered.push({
      id: "sinceElection",
      from: later(election),
      to: last,
      step: longStep,
      year: Number(election.slice(0, "YYYY".length)),
    });
  }
  offered.push(
    {
      id: "fourYears",
      from: later(yearsBefore(last, fourYears)),
      to: last,
      step: longStep,
      year: null,
    },
    { id: "all", from: first, to: last, step: longStep, year: null },
  );
  return offered;
}

function observation(
  poll: ApiPoll,
  component: string,
  latest: Latest,
): readonly PartyObservation[] {
  const share = poll.shares[component];
  if (share === undefined || share === null) {
    return [];
  }
  return [
    {
      ...poll,
      institute: poll.institute ?? "",
      share,
      modeled:
        poll.eligible &&
        (latest.coveragePeriods
          .find((period) => period.id === poll.coveragePeriod)
          ?.roster.includes(component) ??
          false),
    },
  ];
}

async function party(
  page: Bootstrap,
  data: ResultsPageData,
  read: ReadDocument,
): Promise<PartyData> {
  const component = page.route.parameter ?? "";
  const parameters = { party: component, includeExcluded: "true", pageSize: "200" };
  const [first, institutes] = await Promise.all([
    read<PollPageResponse>("polls", parameters),
    read<InstitutesResponse>("institutes"),
  ]);
  const observations = first.polls.flatMap((poll) => observation(poll, component, data.latest));
  for (let number = 2; number <= Math.ceil(first.total / first.pageSize); number += 1) {
    const next = await read<PollPageResponse>("polls", { ...parameters, page: String(number) });
    observations.push(...next.polls.flatMap((poll) => observation(poll, component, data.latest)));
  }
  const estimate = data.latest.components.find((entry) => entry.component === component);
  const seats = data.seats.parties.find((entry) => entry.component === component);
  return {
    component,
    historicalOnly: estimate === undefined,
    estimate: estimate ?? { component, mean: null, lower: null, upper: null },
    thresholdProbability: seats?.thresholdProbability ?? null,
    pointSeats: seats?.pointSeats ?? null,
    observations,
    houseEffects: {
      reference: institutes.reference,
      electionCycle: institutes.electionCycle,
      effects: institutes.institutes.flatMap((institute) =>
        institute.houseEffects
          .filter((effect) => effect.component === component)
          .map((effect) => ({ ...effect, institute: institute.institute })),
      ),
    },
  };
}

async function overview({
  page,
  data,
  read,
  url,
  signal,
}: {
  readonly page: Bootstrap;
  readonly data: ResultsPageData;
  readonly read: ReadDocument;
  readonly url: URL;
  readonly signal: AbortSignal;
}): Promise<Bootstrap> {
  const [history, elections, polls] = await Promise.all([
    read<HistoryResponse>("estimates/history", { step: "7" }),
    read<Elections>("elections"),
    read<PollPageResponse>("polls", { pageSize: "8" }),
  ]);
  const last = page.publication?.lastFieldworkDate ?? data.latest.lastFieldworkDate;
  const offered = ranges(history.dates[0] ?? last, last, elections);
  const selected =
    offered.find((range) => range.id === "sinceElection") ??
    offered.find((range) => range.id === "fourYears");
  if (selected === undefined) {
    throw new Error("Missing timeline range");
  }
  const sampled = await read<HistoryResponse>("estimates/history", {
    from: selected.from,
    to: selected.to,
    step: String(selected.step),
  });
  let loaded: Bootstrap = { ...page, ranges: offered, defaultRange: selected.id };
  let results: ResultsPageData = {
    ...data,
    history: sampled,
    elections,
    polls: recent(polls, page),
  };
  if (page.route.family === PARTY) {
    const detail = await party(page, results, read);
    results = { ...results, party: detail };
    if (detail.historicalOnly) {
      const lastObservation = detail.observations
        .map((entry) => entry.collectionTo ?? entry.collectionFrom)
        .filter((date): date is string => date !== null)
        .sort()
        .at(-1);
      if (lastObservation !== undefined) {
        loaded = { ...loaded, headlineDate: lastObservation };
      }
    }
  }
  if (!page.permanent) {
    loaded = await sourcePage(loaded, url, signal, selected.id);
    if (loaded.sourcePolls !== undefined && page.route.family !== PARTY) {
      results = { ...results, polls: loaded.sourcePolls };
    }
  }
  return { ...loaded, data: results };
}

export type { ReadDocument };
export { overview, pollsters };

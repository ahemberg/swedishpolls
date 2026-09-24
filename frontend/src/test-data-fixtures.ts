import coalitionsResponse from "../../src/main/resources/api/v1/examples/coalitions.json" with {
  type: "json",
};
import latestResponse from "../../src/main/resources/api/v1/examples/estimates-latest.json" with {
  type: "json",
};
import institutesResponse from "../../src/main/resources/api/v1/examples/institutes.json" with {
  type: "json",
};
import pollsResponse from "../../src/main/resources/api/v1/examples/polls.json" with {
  type: "json",
};
import publicationResponse from "../../src/main/resources/api/v1/examples/publication.json" with {
  type: "json",
};
import seatsResponse from "../../src/main/resources/api/v1/examples/seats.json" with {
  type: "json",
};
import freeze from "../../src/main/resources/publication/model-freeze.json" with { type: "json" };
import type { Latest, PartyData, Publication, ResultsPageData } from "./bootstrap";
import type { CoalitionResults, Seats } from "./chamber";
import type { PollstersData } from "./institutes";
import type { MethodData } from "./method";
import type { PollTable } from "./poll-table";

const COMPONENTS = ["S", "M", "SD", "V", "C", "KD", "L", "MP", "FI"] as const;

function pair(values: readonly number[]): readonly [number, number] {
  const [lower, upper] = values;
  if (lower === undefined || upper === undefined) {
    throw new Error("Expected a two-value interval in the API example");
  }
  return [lower, upper];
}

const latest: Latest = latestResponse;
const seats: Seats = {
  totalSeats: seatsResponse.totalSeats,
  intervalLevel: seatsResponse.intervalLevel,
  note: seatsResponse.note,
  allocationRule: {
    electionYear: seatsResponse.allocationRule.electionYear,
    tieNote: seatsResponse.allocationRule.officialTieRule,
  },
  parties: seatsResponse.parties.map((party) => ({
    ...party,
    seatInterval: pair(party.seatInterval),
  })),
  excludedFromAllocation: seatsResponse.excludedFromAllocation,
  unavailable: seatsResponse.unavailable,
  sensitivity: seatsResponse.sensitivity,
};
const coalitions: CoalitionResults = {
  majoritySeats: coalitionsResponse.majoritySeats,
  note: coalitionsResponse.note,
  overviewDefaults: coalitionsResponse.overviewDefaults,
  coalitions: coalitionsResponse.coalitions.map((coalition) => ({
    ...coalition,
    seatInterval: pair(coalition.seatInterval),
  })),
  comparison: coalitionsResponse.comparison,
  sensitivity: coalitionsResponse.sensitivity,
};
const publication: Publication = {
  publicationId: publicationResponse.publicationId,
  publishedAt: publicationResponse.publishedAt,
  sourceCheckedAt: publicationResponse.sourceCheckedAt,
  lastFieldworkDate: publicationResponse.lastFieldworkDate,
  stale: publicationResponse.stale,
  staleSince: publicationResponse.staleSince,
  permalink: publicationResponse.permalink,
  history: publicationResponse.history,
  modelRun: publicationResponse.modelRun,
  snapshot: publicationResponse.snapshot,
};

const results: ResultsPageData = { latest, seats, coalitions };

function party(): PartyData {
  const estimate = latest.components.find((component) => component.component === "S");
  const allocation = seats.parties.find((entry) => entry.component === "S");
  if (estimate === undefined || allocation === undefined) {
    throw new Error("The API examples have no Social Democrat result");
  }
  return {
    component: "S",
    historicalOnly: false,
    estimate,
    thresholdProbability: allocation.thresholdProbability,
    pointSeats: allocation.pointSeats,
    observations: pollsResponse.polls.map((poll) => ({
      pollId: poll.pollId,
      institute: poll.institute,
      collectionFrom: poll.collectionFrom,
      collectionTo: poll.collectionTo,
      approximatePeriod: poll.approximatePeriod,
      sampleSize: poll.sampleSize,
      share: poll.shares.S,
      coveragePeriod: poll.coveragePeriod,
      eligible: poll.eligible,
      modeled: poll.eligible,
      exclusionReasons: poll.exclusionReasons,
    })),
    houseEffects: {
      reference: institutesResponse.reference,
      electionCycle: institutesResponse.electionCycle,
      effects: institutesResponse.institutes.flatMap((institute) =>
        institute.houseEffects
          .filter((effect) => effect.component === "S")
          .map((effect) => ({ institute: institute.institute, ...effect })),
      ),
    },
  };
}

function displayed(value: number | null): string | null {
  if (value === null) {
    return null;
  }
  return String(value);
}

function unmodeled(fi: number | null): readonly string[] {
  if (fi === null) {
    return [];
  }
  return ["FI"];
}

function pollTable(): PollTable {
  return {
    source: false,
    total: pollsResponse.total,
    page: pollsResponse.page,
    pageSize: pollsResponse.pageSize,
    pages: Math.max(1, Math.ceil(pollsResponse.total / pollsResponse.pageSize)),
    csv: pollsResponse.csv,
    columns: COMPONENTS,
    filters: {
      from: pollsResponse.filters.from,
      to: pollsResponse.filters.to,
      institute: pollsResponse.filters.institute,
      party: [],
      coveragePeriod: pollsResponse.coveragePeriod,
      includeExcluded: pollsResponse.filters.includeExcluded,
    },
    invalid: [],
    options: {
      institutes: institutesResponse.institutes.map((institute) => institute.institute),
      coveragePeriods: latest.coveragePeriods.map(({ id }) => ({ id })),
      parties: COMPONENTS,
    },
    labels: Object.fromEntries(COMPONENTS.map((component) => [component, component])),
    polls: pollsResponse.polls.map((poll) => ({
      ...poll,
      displayShares: Object.fromEntries(
        Object.entries(poll.shares).map(([component, share]) => [component, displayed(share)]),
      ),
      displayOther: displayed(poll.other),
      unmodeled: unmodeled(poll.shares.FI),
    })),
  };
}

function pollsters(): PollstersData {
  const effects = institutesResponse.institutes.flatMap((institute) => institute.houseEffects);
  return {
    reference: institutesResponse.reference,
    components: [...new Set(effects.map((effect) => effect.component))],
    institutes: institutesResponse.institutes,
    matrices: [
      {
        cycle: institutesResponse.electionCycle,
        rows: institutesResponse.institutes.map((institute) => ({
          institute: institute.institute,
          cells: institute.houseEffects.map((effect) => ({ ...effect, heat: "z" })),
        })),
      },
    ],
  };
}

const method: MethodData = {
  verdict: {
    status: freeze.release.status,
    released: freeze.release.status === "released",
    failedGates: freeze.release.failedBlockingGates,
  },
  estimator: {
    version: freeze.estimatorVersion,
    numericalLibrary: freeze.numericalLibrary,
    developmentProtocol: freeze.developmentProtocolVersion,
    releaseProtocol: freeze.releaseProtocolVersion,
  },
  draws: {
    seed: freeze.seed,
    count: freeze.draws,
    decimals: freeze.publicationDecimals,
    intervalLevels: freeze.intervalLevels,
  },
  coverage: {
    developmentThrough: freeze.coverageValidation.development_through,
    minObservations: freeze.coverageValidation.min_observations,
    minInstitutes: freeze.coverageValidation.min_institutes,
    maxInternalGapDays: freeze.coverageValidation.max_internal_gap_days,
    boundaryShiftDays: freeze.coverageValidation.boundary_shift_days,
    stabilityBurnInDays: freeze.coverageValidation.stability_burn_in_days,
    maxStabilityShiftPoints: freeze.coverageValidation.max_stability_shift_points,
  },
};

export { COMPONENTS, latest, method, party, pollsters, pollTable, publication, results, seats };

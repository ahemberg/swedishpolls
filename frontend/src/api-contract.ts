import coalitions from "../../src/main/resources/api/v1/examples/coalitions.json";
import elections from "../../src/main/resources/api/v1/examples/elections.json";
import history from "../../src/main/resources/api/v1/examples/estimates-history.json";
import latest from "../../src/main/resources/api/v1/examples/estimates-latest.json";
import institutes from "../../src/main/resources/api/v1/examples/institutes.json";
import polls from "../../src/main/resources/api/v1/examples/polls.json";
import publication from "../../src/main/resources/api/v1/examples/publication.json";
import seats from "../../src/main/resources/api/v1/examples/seats.json";
import type {
  AllocationRuleResponse,
  CoalitionComparisonResponse,
  CoalitionResponse,
  CoalitionsResponse,
  ComparableGroupingResponse,
  CoveragePeriodResponse,
  ElectionResponse,
  ElectionResultResponse,
  ElectionsResponse,
  EstimateResponse,
  EstimateSummaryResponse,
  HistoryBoundaryResponse,
  HistoryChangeResponse,
  HistoryRangeResponse,
  HistoryResponse,
  HistorySeriesResponse,
  HouseEffectResponse,
  InstituteResponse,
  InstitutesResponse,
  LatestResponse,
  MethodEraFromResponse,
  MethodEraToResponse,
  ModelRunResponse,
  PollFiltersResponse,
  PollResponse,
  PollsResponse,
  PublicationIdentityResponse,
  PublicationResponse,
  SeatPartyResponse,
  SeatsResponse,
  SnapshotResponse,
  UnavailableEstimateResponse,
  UnavailableSeatsResponse,
} from "./api";

type Exact<Expected, Actual extends Expected> = Actual &
  Record<Exclude<keyof Actual, keyof Expected>, never> &
  Record<Exclude<keyof Expected, keyof Actual>, never>;

function exact<Expected>() {
  return <Actual extends Expected>(value: Exact<Expected, Actual>): Expected => value;
}

function exactIdentity<Actual extends PublicationIdentityResponse>(
  value: Exact<PublicationIdentityResponse, Actual>,
): void {
  exact<PublicationIdentityResponse>()(value);
}

exact<PublicationResponse>()(publication);
exact<ModelRunResponse>()(publication.modelRun);
exact<SnapshotResponse>()(publication.snapshot);
exact<PublicationResponse["assets"]>()(publication.assets);

exact<LatestResponse>()(latest);
exactIdentity(latest.publication);
for (const period of latest.coveragePeriods) {
  exact<CoveragePeriodResponse>()(period);
}
for (const component of latest.components) {
  exact<EstimateResponse>()(component);
}
exact<EstimateSummaryResponse>()(latest.comparableRemainder);
exact<UnavailableEstimateResponse>()(latest.unavailable.FI);

exact<HistoryResponse>()(history);
exactIdentity(history.publication);
exact<HistoryRangeResponse>()(history.range);
for (const series of history.series) {
  exact<HistorySeriesResponse>()(series);
}
for (const boundary of history.boundaries) {
  exact<HistoryBoundaryResponse>()(boundary);
}
exact<HistoryChangeResponse>()(history.change30d);

exact<PollsResponse>()(polls);
exactIdentity(polls.publication);
exact<PollFiltersResponse>()(polls.filters);
for (const poll of polls.polls) {
  exact<PollResponse>()(poll);
}

exact<InstitutesResponse>()(institutes);
exactIdentity(institutes.publication);
for (const institute of institutes.institutes) {
  exact<InstituteResponse>()(institute);
  for (const era of institute.methodEras) {
    if ("from" in era) {
      exact<MethodEraFromResponse>()(era);
    } else {
      exact<MethodEraToResponse>()(era);
    }
  }
  for (const effect of institute.houseEffects) {
    exact<HouseEffectResponse>()(effect);
  }
}

exact<ElectionsResponse>()(elections);
exactIdentity(elections.publication);
for (const election of elections.elections) {
  exact<ElectionResponse>()(election);
  exact<ComparableGroupingResponse>()(election.comparableGrouping);
  for (const result of Object.values(election.results)) {
    exact<ElectionResultResponse>()(result);
  }
}

exact<SeatsResponse>()(seats);
exactIdentity(seats.publication);
exact<AllocationRuleResponse>()(seats.allocationRule);
for (const party of seats.parties) {
  exact<SeatPartyResponse>()(party);
}
exact<UnavailableSeatsResponse>()(seats.unavailable.FI);

exact<CoalitionsResponse>()(coalitions);
exactIdentity(coalitions.publication);
for (const coalition of coalitions.coalitions) {
  exact<CoalitionResponse>()(coalition);
}
exact<CoalitionComparisonResponse>()(coalitions.comparison);

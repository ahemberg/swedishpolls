export interface PublicationIdentityResponse {
  readonly publicationId: string;
  readonly runId: string;
  readonly snapshotId: number;
}

export interface ModelRunResponse {
  readonly runId: string;
  readonly codeVersion: string;
  readonly estimatorVersion: string;
  readonly seed: number;
  readonly runtime: string;
  readonly numericalLibrary: string;
}

export interface SnapshotResponse {
  readonly snapshotId: number;
  readonly sha256: string;
  readonly sourceUrl: string;
  readonly capturedAt: string;
}

export interface PublicationResponse {
  readonly publicationId: string;
  readonly publishedAt: string;
  readonly sourceCheckedAt: string;
  readonly lastFieldworkDate: string;
  readonly stale: boolean;
  readonly staleSince: string | null;
  readonly permalink: string;
  readonly modelRun: ModelRunResponse;
  readonly snapshot: SnapshotResponse;
  readonly assets: {
    readonly note: string;
    readonly overview: Readonly<Record<"sv" | "en", string>>;
    readonly seats: Readonly<Record<"sv" | "en", string>>;
  };
  readonly history: string;
}

export interface CoveragePeriodResponse {
  readonly id: string;
  readonly from: string;
  readonly to: string | null;
  readonly roster: readonly string[];
  readonly otherMembers: readonly string[];
  readonly individualFi: boolean;
  readonly supportValidated: boolean;
  readonly decision: string;
  readonly otherAlsoIncludes: string;
}

export interface EstimateResponse {
  readonly component: string;
  readonly mean: number;
  readonly lower: number;
  readonly upper: number;
}

export interface EstimateSummaryResponse {
  readonly mean: number;
  readonly lower: number;
  readonly upper: number;
  readonly definition: string;
}

export interface UnavailableEstimateResponse {
  readonly mean: null;
  readonly lower: null;
  readonly upper: null;
  readonly reason: string;
}

export interface LatestResponse {
  readonly publication: PublicationIdentityResponse;
  readonly lastFieldworkDate: string;
  readonly intervalLevel: number;
  readonly coveragePeriod: string;
  readonly coveragePeriods: readonly CoveragePeriodResponse[];
  readonly components: readonly EstimateResponse[];
  readonly comparableRemainder: EstimateSummaryResponse;
  readonly unavailable: Readonly<Record<string, UnavailableEstimateResponse>>;
}

export interface HistoryRangeResponse {
  readonly from: string;
  readonly to: string;
  readonly step: number;
  readonly inclusive: boolean;
  readonly requestedTo: string;
}

export interface HistorySeriesResponse {
  readonly component: string;
  readonly mean: readonly (number | null)[];
  readonly lower: readonly (number | null)[];
  readonly upper: readonly (number | null)[];
}

export interface HistoryBoundaryResponse {
  readonly kind: string;
  readonly date: string;
  readonly periodId: string;
  readonly supportValidated: boolean;
  readonly note: string;
}

export interface HistoryChangeResponse {
  readonly available: boolean;
  readonly comparisonDate: string;
  readonly change: Readonly<Record<string, number>> | null;
  readonly reason: string;
}

export interface HistoryResponse {
  readonly publication: PublicationIdentityResponse;
  readonly intervalLevel: number;
  readonly range: HistoryRangeResponse;
  readonly dates: readonly string[];
  readonly coveragePeriodByDate: readonly string[];
  readonly series: readonly HistorySeriesResponse[];
  readonly boundaries: readonly HistoryBoundaryResponse[];
  readonly change30d: HistoryChangeResponse;
}

export interface PollFiltersResponse {
  readonly from: string;
  readonly to: string;
  readonly institute: readonly string[];
  readonly includeExcluded: boolean;
}

export interface PollResponse {
  readonly pollId: string;
  readonly institute: string;
  readonly company: string;
  readonly methodEra: string | null;
  readonly methodEvidence: string | null;
  readonly surveyType: string;
  readonly publicationDate: string;
  readonly collectionFrom: string;
  readonly collectionTo: string;
  readonly approximatePeriod: boolean;
  readonly sampleSize: number;
  readonly denominatorNote: string;
  readonly shares: Readonly<Record<string, number>>;
  readonly other: number;
  readonly uncertain: number;
  readonly coveragePeriod: string;
  readonly eligible: boolean;
  readonly exclusionReasons: readonly string[];
}

export interface PollsResponse {
  readonly publication: PublicationIdentityResponse;
  readonly filters: PollFiltersResponse;
  readonly coveragePeriod: string;
  readonly total: number;
  readonly page: number;
  readonly pageSize: number;
  readonly csv: string;
  readonly polls: readonly PollResponse[];
}

export interface MethodEraFromResponse {
  readonly id: string;
  readonly from: string;
  readonly evidence: string;
}

export interface MethodEraToResponse {
  readonly id: string;
  readonly to: string;
  readonly evidence: string;
}

export type MethodEraResponse = MethodEraFromResponse | MethodEraToResponse;

export interface HouseEffectResponse {
  readonly electionCycle: string;
  readonly component: string;
  readonly mean: number;
  readonly lower: number;
  readonly upper: number;
  readonly shrunk: boolean;
}

export interface InstituteResponse {
  readonly institute: string;
  readonly companies: readonly string[];
  readonly polls: number;
  readonly firstCollection: string;
  readonly lastCollection: string;
  readonly methodEras: readonly MethodEraResponse[];
  readonly houseEffects: readonly HouseEffectResponse[];
}

export interface InstitutesResponse {
  readonly publication: PublicationIdentityResponse;
  readonly electionCycle: string;
  readonly reference: string;
  readonly institutes: readonly InstituteResponse[];
}

export interface ComparableGroupingResponse {
  readonly coveragePeriod: string;
  readonly other: readonly string[];
  readonly note: string;
}

export interface ElectionResultResponse {
  readonly votes: number;
  readonly officialSeats: number;
}

export interface ElectionResponse {
  readonly electionDate: string;
  readonly electionYear: number;
  readonly validVotes: number;
  readonly sourceUrl: string;
  readonly officialSeatsSourceUrl: string;
  readonly retrievedOn: string;
  readonly comparableGrouping: ComparableGroupingResponse;
  readonly results: Readonly<Record<string, ElectionResultResponse>>;
}

export interface ElectionsResponse {
  readonly publication: PublicationIdentityResponse;
  readonly note: string;
  readonly elections: readonly ElectionResponse[];
}

export interface AllocationRuleResponse {
  readonly electionYear: number;
  readonly seats: number;
  readonly firstDivisor: number;
  readonly subsequentDivisorFormula: string;
  readonly thresholdPercent: number;
  readonly thresholdInclusive: boolean;
  readonly otherReceivesSeats: boolean;
  readonly tieOrder: readonly string[];
  readonly officialTieRule: string;
  readonly constituencyExceptionsIncluded: boolean;
  readonly sourceUrl: string;
}

export interface SeatPartyResponse {
  readonly component: string;
  readonly pointSeats: number;
  readonly meanSeats: number;
  readonly seatInterval: readonly number[];
  readonly thresholdProbability: number;
}

export interface UnavailableSeatsResponse {
  readonly pointSeats: null;
  readonly meanSeats: null;
  readonly thresholdProbability: null;
  readonly reason: string;
}

export interface SeatsResponse {
  readonly publication: PublicationIdentityResponse;
  readonly lastFieldworkDate: string;
  readonly totalSeats: number;
  readonly intervalLevel: number;
  readonly note: string;
  readonly allocationRule: AllocationRuleResponse;
  readonly parties: readonly SeatPartyResponse[];
  readonly unavailable: Readonly<Record<string, UnavailableSeatsResponse>>;
  readonly excludedFromAllocation: readonly string[];
  readonly sensitivity: string;
}

export interface CoalitionResponse {
  readonly id: string;
  readonly parties: readonly string[];
  readonly pointSeats: number;
  readonly meanSeats: number;
  readonly seatInterval: readonly number[];
  readonly majorityProbability: number;
}

export interface CoalitionComparisonResponse {
  readonly pairs: string;
  readonly tie: string;
}

export interface CoalitionsResponse {
  readonly publication: PublicationIdentityResponse;
  readonly lastFieldworkDate: string;
  readonly majoritySeats: number;
  readonly intervalLevel: number;
  readonly overviewDefaults: readonly string[];
  readonly note: string;
  readonly coalitions: readonly CoalitionResponse[];
  readonly comparison: CoalitionComparisonResponse;
  readonly sensitivity: string;
}

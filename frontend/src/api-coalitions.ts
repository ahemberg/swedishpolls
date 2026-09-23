import type { PublicationIdentityResponse } from "./api";

export interface CoalitionResponse {
  readonly id: string;
  readonly parties: readonly string[];
  readonly pointSeats: number;
  readonly meanSeats: number;
  readonly seatInterval: readonly number[];
  readonly majorityProbability: number;
}

export interface CoalitionPairResponse {
  readonly left: string;
  readonly right: string;
  readonly leftLeads: number;
  readonly rightLeads: number;
  readonly tied: number;
}

export interface CoalitionComparisonResponse {
  readonly pairs: readonly CoalitionPairResponse[];
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

/**
 * The published shape of the chamber: how one estimate becomes 349 seats, and what those seats
 * mean for the party combinations the site publishes.
 *
 * Two quantities run through all of it and are never the same number. The integer allocation is
 * one seat-by-seat division of the estimated mean support, so it fills the chamber exactly. The
 * posterior summaries are averages and quantiles over the model's draws, and rounding those would
 * not add up to 349. Every type here keeps them apart.
 */

/** One party's seats: the allocation, the posterior summaries, and its chance of qualifying. */
export interface SeatsParty {
  readonly component: string;
  readonly pointSeats: number | null;
  readonly meanSeats: number | null;
  readonly seatInterval: readonly [number, number] | null;
  readonly thresholdProbability: number | null;
}

/**
 * The election-era rule one allocation was made under, as far as a page reads it.
 *
 * The published document carries the whole rule: divisors, the threshold, the tie order and its
 * source. A page says which election's rules these were and what the deterministic tie order
 * stands in for, so only those two are declared here.
 */
export interface AllocationRule {
  readonly electionYear: number;
  readonly tieNote: string;
}

export interface Seats {
  readonly totalSeats: number;
  readonly intervalLevel: number;
  readonly note: string;
  readonly allocationRule: AllocationRule;
  readonly parties: readonly SeatsParty[];
  readonly excludedFromAllocation: readonly string[];
  readonly unavailable: Readonly<Record<string, { readonly reason: string }>>;
  readonly sensitivity?: string;
}

/** One preset coalition: an explicit membership, and the seats that membership takes together. */
export interface Coalition {
  readonly id: string;
  readonly parties: readonly string[];
  readonly pointSeats: number;
  readonly meanSeats: number;
  readonly seatInterval: readonly [number, number];
  readonly majorityProbability: number;
}

/**
 * One pair of the comparison view. The three probabilities are counted over the same draws and
 * sum to one; an exact tie is its own outcome rather than a win credited to either side.
 */
export interface ComparisonPair {
  readonly left: string;
  readonly right: string;
  readonly leftLeads: number;
  readonly rightLeads: number;
  readonly tied: number;
}

export interface Comparison {
  readonly pairs: readonly ComparisonPair[];
  readonly tie: string;
}

export interface CoalitionResults {
  readonly majoritySeats: number;
  readonly note: string;
  readonly overviewDefaults: readonly string[];
  readonly coalitions: readonly Coalition[];
  readonly comparison: Comparison;
  readonly sensitivity?: string;
}

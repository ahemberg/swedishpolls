/**
 * The pollsters page's data, as the server wrote it into the bootstrap.
 *
 * One heat cell per institute, component and election cycle, with the colour step the server
 * computed from the effect's size. The interval travels with the cell, so the heat table and its
 * table alternative read the same numbers.
 */

/** One heatmap cell: an institute's effect on one component, in one election cycle. */
export interface PollstersCell {
  readonly component: string;
  readonly mean: number;
  readonly lower: number;
  readonly upper: number;
  readonly shrunk: boolean;
  readonly heat: string;
}

export interface PollstersRow {
  readonly institute: string;
  readonly cells: readonly PollstersCell[];
}

export interface PollstersMatrix {
  readonly cycle: string;
  readonly rows: readonly PollstersRow[];
}

export interface InstituteMeta {
  readonly institute: string;
  readonly companies: readonly string[];
  readonly polls: number;
  readonly firstCollection: string | null;
  readonly lastCollection: string | null;
  readonly methodEras: readonly {
    readonly id: string;
    readonly evidence: string | null;
  }[];
}

export interface PollstersData {
  readonly reference: string;
  readonly components: readonly string[];
  readonly institutes: readonly InstituteMeta[];
  readonly matrices: readonly PollstersMatrix[];
}

/**
 * The method page's data: the shipped estimator contract, as the server wrote it into the
 * bootstrap. The verdict, the frozen draws and tolerances, and the registered per-period
 * requirements a page that explains the method presents beside its wording.
 */

export interface MethodData {
  readonly verdict: {
    readonly status: string;
    readonly released: boolean;
    readonly failedGates: readonly string[];
  };
  readonly estimator: {
    readonly version: string;
    readonly numericalLibrary: string;
    readonly developmentProtocol: string;
    readonly releaseProtocol: string;
  };
  readonly draws: {
    readonly seed: number;
    readonly count: number;
    readonly decimals: number;
    readonly intervalLevels: readonly number[];
    readonly maxDriftPoints: number;
  };
  readonly coverage: {
    readonly developmentThrough: string;
    readonly minObservations: number;
    readonly minInstitutes: number;
    readonly maxInternalGapDays: number;
    readonly boundaryShiftDays: readonly number[];
    readonly stabilityBurnInDays: number;
    readonly maxStabilityShiftPoints: number;
  };
}

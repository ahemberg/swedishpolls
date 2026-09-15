import { useMemo } from "react";
import type { Bootstrap, History, Series } from "./bootstrap";
import { PLOT } from "./chart";
import { visibleParties } from "./parties";
import type { SourceChartData, SourceMark, SourceObservation, SourceWindow } from "./source-chart";
import { reportedShares, sourceMarks } from "./source-chart";
import { useFetched } from "./useFetched";
import type { MarkCursor } from "./useSourceChart";
import { ordered, sourceChartRequest, useMarkCursor } from "./useSourceChart";

interface SourceOverlay {
  readonly components: readonly { readonly component: string }[];
  readonly drawnComponents: readonly string[];
  readonly values: readonly number[];
  readonly window: SourceWindow | undefined;
  readonly source: {
    readonly observations: readonly SourceObservation[];
    readonly marks: readonly SourceMark[];
    readonly cursor: MarkCursor;
    readonly loading: boolean;
    readonly failed: boolean;
  } | null;
}

function chartWindow(
  history: History | undefined,
  source: SourceChartData | undefined,
): SourceWindow | undefined {
  if (source === undefined) {
    return;
  }
  if (history === undefined) {
    return source.range;
  }
  return {
    ...source.range,
    from: earlier(history.range.from, source.range.from),
    to: later(history.range.to, source.range.to),
  };
}

function earlier(left: string, right: string): string {
  if (left < right) {
    return left;
  }
  return right;
}

function later(left: string, right: string): string {
  if (left > right) {
    return left;
  }
  return right;
}

function useSourceData(page: Bootstrap, rangeId: string) {
  const initial = page.sourceChart;
  let request = null;
  if (initial !== undefined) {
    request = sourceChartRequest(initial, rangeId);
  }
  return useFetched<SourceChartData | undefined>(initial, request);
}

function componentNames(series: readonly Series[], source: SourceChartData | undefined) {
  return [...new Set([...series.map((entry) => entry.component), ...(source?.components ?? [])])];
}

function observations(source: SourceChartData | undefined): readonly SourceObservation[] {
  if (source === undefined) {
    return [];
  }
  return ordered(source);
}

function marks(
  source: SourceChartData | undefined,
  window: SourceWindow | undefined,
  drawn: readonly string[],
): readonly SourceMark[] {
  if (source === undefined || window === undefined) {
    return [];
  }
  return sourceMarks(source.observations, window, drawn, PLOT);
}

function values(source: SourceChartData | undefined, drawn: readonly string[]): readonly number[] {
  if (source === undefined) {
    return [];
  }
  return reportedShares(source.observations, drawn);
}

interface Options {
  readonly page: Bootstrap;
  readonly rangeId: string;
  readonly history: History | undefined;
  readonly series: readonly Series[];
  readonly isolated: string;
  readonly hidden: readonly string[];
  readonly svg: React.RefObject<SVGSVGElement | null>;
}

function useSourceOverlay(options: Options): SourceOverlay {
  const { page, rangeId, history, series, isolated, hidden, svg } = options;
  const fetched = useSourceData(page, rangeId);
  const data = fetched.value;
  const window = chartWindow(history, data);
  const names = useMemo(() => componentNames(series, data), [series, data]);
  const drawn = useMemo(() => visibleParties(names, isolated, hidden), [names, isolated, hidden]);
  const entries = useMemo(() => observations(data), [data]);
  const cursor = useMarkCursor(entries, window, svg);
  const drawnMarks = useMemo(() => marks(data, window, drawn), [data, window, drawn]);
  const shownValues = useMemo(() => values(data, drawn), [data, drawn]);
  let source: SourceOverlay["source"] = null;
  if (data !== undefined) {
    source = {
      observations: entries,
      marks: drawnMarks,
      cursor,
      loading: fetched.loading,
      failed: fetched.failed,
    };
  }
  return {
    components: names.map((component) => ({ component })),
    drawnComponents: drawn,
    values: shownValues,
    window,
    source,
  };
}

export type { SourceOverlay };
export { useSourceOverlay };

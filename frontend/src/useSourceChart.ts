import { useCallback, useMemo, useRef, useState } from "react";
import { axisMaximum, LEFT, PLOT, PLOT_WIDTH, WIDTH } from "./chart";
import { visibleParties, withoutParty } from "./parties";
import type { SourceChartData, SourceMark, SourceObservation, SourceWindow } from "./source-chart";
import { reportedShares, sourceMarks } from "./source-chart";
import { ALL_PARTIES } from "./timeline-controls";
import { useFetched } from "./useFetched";

/**
 * The source chart's state: which window, which parties, and which observation is being read.
 *
 * The bootstrap already carries the opening window, so the first paint fetches nothing. Every later
 * window is fetched with the snapshot the visit resolved and the table's own filters, which is what
 * keeps a window change on the same retained data even when a correction lands meanwhile.
 */

interface SourceChartState {
  readonly data: SourceChartData;
  readonly loading: boolean;
  readonly failed: boolean;
  readonly rangeId: string;
  readonly isolated: string;
  readonly hidden: readonly string[];
  readonly drawn: readonly string[];
  readonly marks: readonly SourceMark[];
  readonly maximum: number;
  readonly observations: readonly SourceObservation[];
  readonly index: number;
  readonly lastIndex: number;
  readonly svg: React.RefObject<SVGSVGElement | null>;
  readonly chooseRange: (id: string) => void;
  readonly isolate: (component: string) => void;
  readonly toggle: (component: string) => void;
  readonly setCursor: (index: number) => void;
  readonly scrub: (clientX: number) => void;
}

interface Loaded {
  readonly data: SourceChartData;
  readonly loading: boolean;
  readonly failed: boolean;
}

interface Parties {
  readonly isolated: string;
  readonly hidden: readonly string[];
  readonly isolate: (component: string) => void;
  readonly toggle: (component: string) => void;
  readonly drawn: (components: readonly string[]) => readonly string[];
}

function url(data: SourceChartData, rangeId: string): string {
  const parameters = new URLSearchParams({ snapshot: String(data.snapshotId), range: rangeId });
  if (data.query === "") {
    return `/source/chart?${parameters.toString()}`;
  }
  return `/source/chart?${parameters.toString()}&${data.query}`;
}

/** The window to fetch, or null for the one the page already carries. */
function window(data: SourceChartData, rangeId: string): string | null {
  if (rangeId === data.defaultRange) {
    return null;
  }
  return url(data, rangeId);
}

/** One window's observations, fetched only when the reader leaves the one the page arrived with. */
function useWindow(initial: SourceChartData, rangeId: string): Loaded {
  const { value, loading, failed } = useFetched(initial, window(initial, rangeId));
  return { data: value, loading, failed };
}

/** Which parties are drawn. Isolation wins over the toggles, as it does on the estimate timeline. */
function useParties(): Parties {
  const [isolated, isolate] = useState(ALL_PARTIES);
  const [hidden, setHidden] = useState<readonly string[]>([]);
  const toggle = useCallback((component: string) => {
    setHidden((current) => withoutParty(current, component));
  }, []);
  const drawn = useCallback(
    (components: readonly string[]) => visibleParties(components, isolated, hidden),
    [isolated, hidden],
  );
  return { isolated, hidden, isolate, toggle, drawn };
}

/** The window's observations in interview order, which is the order the cursor steps through. */
function ordered(data: SourceChartData): readonly SourceObservation[] {
  return [...data.observations].sort((left, right) => left.from.localeCompare(right.from));
}

function middleOf(observation: SourceObservation): number {
  return (
    (Date.parse(`${observation.from}T00:00:00Z`) + Date.parse(`${observation.to}T00:00:00Z`)) / 2
  );
}

/** The observation whose interview period sits closest to a fraction across the window. */
function nearest(
  observations: readonly SourceObservation[],
  window: SourceWindow,
  fraction: number,
): number {
  const start = Date.parse(`${window.from}T00:00:00Z`);
  const end = Date.parse(`${window.to}T00:00:00Z`);
  const wanted = start + (end - start) * fraction;
  let match = 0;
  let distance = Number.POSITIVE_INFINITY;
  for (const [position, observation] of observations.entries()) {
    const candidate = Math.abs(middleOf(observation) - wanted);
    if (candidate < distance) {
      match = position;
      distance = candidate;
    }
  }
  return match;
}

interface MarkCursor {
  readonly index: number;
  readonly lastIndex: number;
  readonly svg: React.RefObject<SVGSVGElement | null>;
  readonly setCursor: (index: number) => void;
  readonly scrub: (clientX: number) => void;
  readonly reset: () => void;
}

/**
 * The observation being read. One position drives the readout, the highlight and the slider, so a
 * drag with a mouse, a drag with a finger and a press of an arrow key all move the same thing.
 */
function useMarkCursor(
  observations: readonly SourceObservation[],
  window: SourceWindow,
): MarkCursor {
  const [cursor, setCursor] = useState<number | null>(null);
  const svg = useRef<SVGSVGElement | null>(null);
  const lastIndex = Math.max(0, observations.length - 1);
  const scrub = useCallback(
    (clientX: number) => {
      const element = svg.current;
      if (element === null || observations.length === 0) {
        return;
      }
      const box = element.getBoundingClientRect();
      const inside = ((clientX - box.left) / box.width) * WIDTH;
      const wanted = Math.max(0, Math.min(1, (inside - LEFT) / PLOT_WIDTH));
      setCursor(nearest(observations, window, wanted));
    },
    [observations, window],
  );
  return {
    lastIndex,
    svg,
    setCursor,
    scrub,
    index: Math.min(cursor ?? lastIndex, lastIndex),
    reset: useCallback(() => setCursor(null), []),
  };
}

function useSourceChart(initial: SourceChartData): SourceChartState {
  const [rangeId, setRangeId] = useState(initial.defaultRange);
  const { data, loading, failed } = useWindow(initial, rangeId);
  const parties = useParties();
  const observations = useMemo(() => ordered(data), [data]);
  const cursor = useMarkCursor(observations, data.range);

  // Memoised because the drawn set feeds the marker and axis derivations below: rebuilding it on
  // every render would rebuild eleven thousand marks with it on the whole-history window.
  const { drawn: chosen } = parties;
  const drawn = useMemo(() => chosen(data.components), [chosen, data.components]);
  const marks = useMemo(
    () => sourceMarks(data.observations, data.range, drawn, PLOT),
    [data.observations, data.range, drawn],
  );
  const maximum = useMemo(
    () => axisMaximum([], reportedShares(data.observations, drawn)),
    [data.observations, drawn],
  );
  const { reset } = cursor;
  const chooseRange = useCallback(
    (id: string) => {
      setRangeId(id);
      reset();
    },
    [reset],
  );

  return {
    data,
    loading,
    failed,
    rangeId,
    drawn,
    marks,
    maximum,
    observations,
    chooseRange,
    isolated: parties.isolated,
    hidden: parties.hidden,
    isolate: parties.isolate,
    toggle: parties.toggle,
    index: cursor.index,
    lastIndex: cursor.lastIndex,
    svg: cursor.svg,
    setCursor: cursor.setCursor,
    scrub: cursor.scrub,
  };
}

export type { SourceChartState };
export { useSourceChart };

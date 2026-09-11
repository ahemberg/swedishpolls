import { useCallback, useMemo, useState } from "react";
import type { Bootstrap, History, Series } from "./bootstrap";
import { axisMaximum, xAt, yAt } from "./chart";
import { ALL_PARTIES } from "./timeline-controls";
import { useCursor } from "./useCursor";
import { useHistory } from "./useHistory";

/** The timeline's state: which range, which parties, and which day the cursor is reading. */

type Scale = (value: number) => number;

interface TimelineState {
  readonly history: History | undefined;
  readonly loading: boolean;
  readonly failed: boolean;
  readonly series: readonly Series[];
  readonly dates: readonly string[];
  readonly drawn: readonly Series[];
  readonly maximum: number;
  readonly index: number;
  readonly lastIndex: number;
  readonly rangeId: string;
  readonly isolated: string;
  readonly hidden: readonly string[];
  readonly svg: React.RefObject<SVGSVGElement | null>;
  readonly x: Scale;
  readonly y: Scale;
  readonly scrub: (clientX: number) => void;
  readonly setCursor: (index: number) => void;
  readonly chooseRange: (id: string) => void;
  readonly isolate: (component: string) => void;
  readonly toggle: (component: string) => void;
}

/** Isolation wins over the toggles, and a series with no estimate in range is not drawn at all. */
function shown(
  series: readonly Series[],
  isolated: string,
  hidden: readonly string[],
): readonly Series[] {
  const chosen = series.filter((entry) => {
    if (isolated !== ALL_PARTIES) {
      return entry.component === isolated;
    }
    return !hidden.includes(entry.component);
  });
  return chosen.filter((entry) => entry.mean.some((value) => value !== null));
}

function without(hidden: readonly string[], component: string): readonly string[] {
  if (hidden.includes(component)) {
    return hidden.filter((entry) => entry !== component);
  }
  return [...hidden, component];
}

function seriesOf(history: History | undefined): readonly Series[] {
  if (history === undefined) {
    return [];
  }
  return history.series;
}

function datesOf(history: History | undefined): readonly string[] {
  if (history === undefined) {
    return [];
  }
  return history.dates;
}

function useTimeline(page: Bootstrap): TimelineState {
  const [rangeId, setRangeId] = useState(page.defaultRange ?? "");
  const [isolated, isolate] = useState(ALL_PARTIES);
  const [hidden, setHidden] = useState<readonly string[]>([]);
  const { history, loading, failed } = useHistory(page, rangeId);

  const series = seriesOf(history);
  const dates = datesOf(history);
  const days = dates.length;
  const cursor = useCursor(days);
  const drawn = useMemo(() => shown(series, isolated, hidden), [series, isolated, hidden]);
  const maximum = useMemo(() => axisMaximum(drawn), [drawn]);
  const x = useCallback((position: number) => xAt(position, days), [days]);
  const y = useCallback((value: number) => yAt(value, maximum), [maximum]);
  const { reset } = cursor;
  const chooseRange = useCallback(
    (id: string) => {
      setRangeId(id);
      reset();
    },
    [reset],
  );
  const toggle = useCallback((component: string) => {
    setHidden((current) => without(current, component));
  }, []);

  return {
    history,
    loading,
    failed,
    series,
    dates,
    drawn,
    maximum,
    rangeId,
    isolated,
    hidden,
    x,
    y,
    chooseRange,
    isolate,
    toggle,
    index: cursor.index,
    lastIndex: cursor.lastIndex,
    svg: cursor.svg,
    scrub: cursor.scrub,
    setCursor: cursor.setCursor,
  };
}

export type { TimelineState };
export { useTimeline };

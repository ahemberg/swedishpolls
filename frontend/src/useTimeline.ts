import { useCallback, useMemo, useState } from "react";
import type { Bootstrap, History, PartyObservation, Series } from "./bootstrap";
import { axisMaximum, xAt, yAt } from "./chart";
import { visibleParties, withoutParty } from "./parties";
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
  const visible = visibleParties(
    series.map((entry) => entry.component),
    isolated,
    hidden,
  );
  return series.filter(
    (entry) => visible.includes(entry.component) && entry.mean.some((value) => value !== null),
  );
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

function observationValues(
  observations: readonly PartyObservation[],
  dates: readonly string[],
): readonly number[] {
  const [first] = dates;
  const last = dates.at(-1);
  if (first === undefined || last === undefined) {
    return [];
  }
  return observations
    .filter((entry) => {
      const day = entry.collectionTo ?? entry.collectionFrom;
      return day !== null && day >= first && day <= last;
    })
    .map((entry) => entry.share);
}

function useTimeline(
  page: Bootstrap,
  component = ALL_PARTIES,
  observations: readonly PartyObservation[] = [],
): TimelineState {
  const [rangeId, setRangeId] = useState(page.defaultRange ?? "");
  const [isolated, isolate] = useState(component);
  const [hidden, setHidden] = useState<readonly string[]>([]);
  const { history, loading, failed } = useHistory(page, rangeId);

  const series = seriesOf(history);
  const dates = datesOf(history);
  const days = dates.length;
  const cursor = useCursor(days);
  const drawn = useMemo(() => shown(series, isolated, hidden), [series, isolated, hidden]);
  const maximum = useMemo(
    () => axisMaximum(drawn, observationValues(observations, dates)),
    [drawn, observations, dates],
  );
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
    setHidden((current) => withoutParty(current, component));
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

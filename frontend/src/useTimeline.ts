import { useCallback, useMemo, useState } from "react";
import type { Bootstrap, History, PartyObservation, Series } from "./bootstrap";
import { axisMaximum, PLOT, xAt, yAt } from "./chart";
import { visibleParties, withoutParty } from "./parties";
import type { SourceWindow } from "./source-chart";
import { dateX } from "./source-chart";
import { ALL_PARTIES } from "./timeline-controls";
import { useCursor } from "./useCursor";
import { useHistory } from "./useHistory";
import type { SourceOverlay } from "./useSourceOverlay";
import { useSourceOverlay } from "./useSourceOverlay";

/** The timeline's state: which range, which parties, and which day the cursor is reading. */

type Scale = (value: number) => number;

interface TimelineState {
  readonly history: History | undefined;
  readonly loading: boolean;
  readonly failed: boolean;
  readonly series: readonly Series[];
  readonly components: readonly { readonly component: string }[];
  readonly dates: readonly string[];
  readonly drawn: readonly Series[];
  readonly drawnComponents: readonly string[];
  readonly maximum: number;
  readonly source: SourceOverlay["source"];
  readonly window: SourceWindow | undefined;
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

function axisValues(
  overlay: SourceOverlay,
  observations: readonly PartyObservation[],
  dates: readonly string[],
): readonly number[] {
  if (overlay.source !== null) {
    return overlay.values;
  }
  return observationValues(observations, dates);
}

function useAxes({
  drawn,
  overlay,
  observations,
  dates,
}: {
  readonly drawn: readonly Series[];
  readonly overlay: SourceOverlay;
  readonly observations: readonly PartyObservation[];
  readonly dates: readonly string[];
}) {
  const maximum = useMemo(
    () => axisMaximum(drawn, axisValues(overlay, observations, dates)),
    [drawn, observations, dates, overlay],
  );
  const x = useCallback(
    (position: number) => {
      const date = dates[position];
      if (overlay.window === undefined || date === undefined) {
        return xAt(position, dates.length);
      }
      return dateX(date, overlay.window, PLOT);
    },
    [dates, overlay.window],
  );
  const y = useCallback((value: number) => yAt(value, maximum), [maximum]);
  return { maximum, x, y };
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
  const cursor = useCursor(dates.length);
  const overlay = useSourceOverlay({
    page,
    rangeId,
    history,
    series,
    isolated,
    hidden,
    svg: cursor.svg,
  });
  const drawn = useMemo(() => shown(series, isolated, hidden), [series, isolated, hidden]);
  const axes = useAxes({ drawn, overlay, observations, dates });
  const { reset } = cursor;
  const chooseRange = useCallback(
    (id: string) => {
      setRangeId(id);
      reset();
      overlay.source?.cursor.reset();
    },
    [reset, overlay.source?.cursor.reset],
  );
  const toggle = useCallback((component: string) => {
    setHidden((current) => withoutParty(current, component));
  }, []);

  return {
    history,
    loading,
    failed,
    series,
    ...overlay,
    dates,
    drawn,
    ...axes,
    rangeId,
    isolated,
    hidden,
    chooseRange,
    isolate,
    toggle,
    ...cursor,
  };
}

export type { TimelineState };
export { useTimeline };

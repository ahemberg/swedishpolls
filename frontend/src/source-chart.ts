import { axisMaximum, LEFT, PLOT_WIDTH } from "./chart.ts";

/**
 * The source chart's geometry: a real date axis, and one horizontal marker per reported share.
 *
 * <p>Nothing here snaps an observation to a sampled estimate day. A marker starts on the first
 * interview date and ends after the last one, so its width is the fieldwork it actually covers and
 * a one-day poll is a short mark rather than a point. A period reaching past the chosen window is
 * drawn to the edge and flagged, because the dates a reader is shown in the details are the ones
 * the institute reported, not the ones the window happened to allow.
 */

/** One archived observation, as a source chart draws it: real interview dates, reported shares. */
interface SourceObservation {
  readonly pollId: string;
  readonly institute: string;
  readonly from: string;
  readonly to: string;
  readonly approximatePeriod: boolean;
  readonly sampleSize: number | null;
  readonly shares: Readonly<Record<string, number | null>>;
}

/** One offered window of the source chart's date axis. Both bounds are interview dates. */
interface SourceWindow {
  readonly id: string;
  readonly from: string;
  readonly to: string;
}

/**
 * The source chart's own payload. It carries the window it draws rather than the whole archive, so
 * a range change fetches the next window against the same snapshot the visit resolved.
 */
interface SourceChartData {
  readonly snapshotId: number;
  readonly defaultRange: string;
  readonly query: string;
  readonly ranges: readonly SourceWindow[];
  readonly range: SourceWindow;
  readonly components: readonly string[];
  readonly observations: readonly SourceObservation[];
}

/** Milliseconds in a day, for turning two ISO dates into a count of days between them. */
const DAY = 86_400_000;

/** The narrowest a marker is drawn, so a one-day period stays visible and selectable. */
const MINIMUM_SPAN = 6;

const YEAR_LENGTH = 4;
const PLOT_RIGHT = LEFT + PLOT_WIDTH;

/** One drawn marker: where it sits, what it reports, and the observation it came from. */
interface SourceMark {
  readonly key: string;
  readonly component: string;
  readonly share: number;
  readonly x1: number;
  readonly x2: number;
  readonly clippedFrom: boolean;
  readonly clippedTo: boolean;
  readonly observation: SourceObservation;
}

interface Span {
  readonly x1: number;
  readonly x2: number;
  readonly clippedFrom: boolean;
  readonly clippedTo: boolean;
}

interface YearTick {
  readonly year: string;
  readonly x: number;
}

function day(iso: string): number {
  return Date.parse(`${iso}T00:00:00Z`) / DAY;
}

/** The window's width in days, counting its last day rather than stopping at its start. */
function width(window: SourceWindow): number {
  return day(window.to) - day(window.from) + 1;
}

/** Where a day boundary falls, unclamped, so a clipped marker can still be measured. */
function xAtDay(value: number, window: SourceWindow): number {
  return LEFT + (PLOT_WIDTH * (value - day(window.from))) / width(window);
}

function clamp(value: number): number {
  return Math.min(PLOT_RIGHT, Math.max(LEFT, value));
}

/** A marker widened about its own midpoint until it is selectable, without leaving the plot. */
function selectable(x1: number, x2: number): readonly [number, number] {
  if (x2 - x1 >= MINIMUM_SPAN) {
    return [x1, x2];
  }
  const middle = (x1 + x2) / 2;
  const start = Math.min(PLOT_RIGHT - MINIMUM_SPAN, Math.max(LEFT, middle - MINIMUM_SPAN / 2));
  return [start, start + MINIMUM_SPAN];
}

/** One observation's horizontal extent in the window, clipped to the plot but not re-dated. */
function markerSpan(observation: SourceObservation, window: SourceWindow): Span {
  const from = day(observation.from);
  const to = day(observation.to) + 1;
  const [x1, x2] = selectable(clamp(xAtDay(from, window)), clamp(xAtDay(to, window)));
  return { x1, x2, clippedFrom: from < day(window.from), clippedTo: to > day(window.to) + 1 };
}

/**
 * Every marker the window draws. A share the institute did not report has no marker at all, and a
 * component outside the reported nine has none either: the comparable remainder is not a party.
 */
function sourceMarks(
  observations: readonly SourceObservation[],
  window: SourceWindow,
  components: readonly string[],
): readonly SourceMark[] {
  return observations.flatMap((observation) => {
    const span = markerSpan(observation, window);
    return components.flatMap((component) => {
      const share = observation.shares[component];
      if (share === undefined || share === null) {
        return [];
      }
      return [
        {
          key: `${observation.pollId}-${component}`,
          component,
          share,
          x1: span.x1,
          x2: span.x2,
          clippedFrom: span.clippedFrom,
          clippedTo: span.clippedTo,
          observation,
        },
      ];
    });
  });
}

/** The top of the axis: the highest share the drawn parties reported, on the timeline's grid. */
function sourceMaximum(
  observations: readonly SourceObservation[],
  components: readonly string[],
): number {
  const shares = observations.flatMap((observation) =>
    components.flatMap((component) => {
      const share = observation.shares[component];
      if (share === undefined || share === null) {
        return [];
      }
      return [share];
    }),
  );
  return axisMaximum([], shares);
}

/** Where each calendar year begins inside the window. Its own first day is not a boundary. */
function yearTicks(window: SourceWindow): readonly YearTick[] {
  const first = Number(window.from.slice(0, YEAR_LENGTH));
  const last = Number(window.to.slice(0, YEAR_LENGTH));
  const ticks: YearTick[] = [];
  for (let year = first + 1; year <= last; year += 1) {
    const start = `${year}-01-01`;
    if (start > window.from && start <= window.to) {
      ticks.push({ year: String(year), x: xAtDay(day(start), window) });
    }
  }
  return ticks;
}

export type { SourceChartData, SourceMark, SourceObservation, SourceWindow, Span, YearTick };
export { MINIMUM_SPAN, markerSpan, PLOT_RIGHT, sourceMarks, sourceMaximum, xAtDay, yearTicks };

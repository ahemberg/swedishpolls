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

/**
 * One offered window of the source chart's date axis. Both bounds are interview dates, and the year
 * is the one the window's own label names, which only the election window has.
 */
interface SourceWindow {
  readonly id: string;
  readonly from: string;
  readonly to: string;
  readonly year: number | null;
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

/** The horizontal box a chart draws days in. */
interface Plot {
  readonly left: number;
  readonly width: number;
}

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
function xAtDay(value: number, window: SourceWindow, plot: Plot): number {
  return plot.left + (plot.width * (value - day(window.from))) / width(window);
}

/** Where a dated point sits on the real source window, including both edge dates. */
function dateX(iso: string, window: SourceWindow, plot: Plot): number {
  const days = Math.max(1, day(window.to) - day(window.from));
  return plot.left + (plot.width * (day(iso) - day(window.from))) / days;
}

function clamp(value: number, plot: Plot): number {
  return Math.min(plot.left + plot.width, Math.max(plot.left, value));
}

/** A marker widened about its own midpoint until it is selectable, without leaving the plot. */
function selectable(x1: number, x2: number, plot: Plot): readonly [number, number] {
  if (x2 - x1 >= MINIMUM_SPAN) {
    return [x1, x2];
  }
  const middle = (x1 + x2) / 2;
  const right = plot.left + plot.width - MINIMUM_SPAN;
  const start = Math.min(right, Math.max(plot.left, middle - MINIMUM_SPAN / 2));
  return [start, start + MINIMUM_SPAN];
}

/** One observation's horizontal extent in the window, clipped to the plot but not re-dated. */
function markerSpan(observation: SourceObservation, window: SourceWindow, plot: Plot): Span {
  const from = day(observation.from);
  const to = day(observation.to) + 1;
  const [x1, x2] = selectable(
    clamp(xAtDay(from, window, plot), plot),
    clamp(xAtDay(to, window, plot), plot),
    plot,
  );
  return { x1, x2, clippedFrom: from < day(window.from), clippedTo: to > day(window.to) + 1 };
}

/**
 * The shares one observation actually reports for the drawn parties, in roster order. A share the
 * institute did not report is absent rather than zero, and a component outside the reported nine
 * is absent too: the comparable remainder is not a party.
 */
function reported(
  observation: SourceObservation,
  components: readonly string[],
): readonly { readonly component: string; readonly share: number }[] {
  return components.flatMap((component) => {
    const share = observation.shares[component];
    if (share === undefined || share === null) {
      return [];
    }
    return [{ component, share }];
  });
}

/** Every marker the window draws: one per share an observation reports for a drawn party. */
function sourceMarks(
  observations: readonly SourceObservation[],
  window: SourceWindow,
  components: readonly string[],
  plot: Plot,
): readonly SourceMark[] {
  return observations.flatMap((observation) => {
    const span = markerSpan(observation, window, plot);
    return reported(observation, components).map(({ component, share }) => ({
      key: `${observation.pollId}-${component}`,
      component,
      share,
      x1: span.x1,
      x2: span.x2,
      clippedFrom: span.clippedFrom,
      clippedTo: span.clippedTo,
      observation,
    }));
  });
}

/** Every share the drawn parties reported, which is what the axis has to reach. */
function reportedShares(
  observations: readonly SourceObservation[],
  components: readonly string[],
): readonly number[] {
  return observations.flatMap((observation) =>
    reported(observation, components).map((entry) => entry.share),
  );
}

/** Where each calendar year begins inside the window. Its own first day is not a boundary. */
function yearTicks(window: SourceWindow, plot: Plot): readonly YearTick[] {
  const first = Number(window.from.slice(0, YEAR_LENGTH));
  const last = Number(window.to.slice(0, YEAR_LENGTH));
  const ticks: YearTick[] = [];
  for (let year = first + 1; year <= last; year += 1) {
    const start = `${year}-01-01`;
    if (start > window.from && start <= window.to) {
      ticks.push({ year: String(year), x: xAtDay(day(start), window, plot) });
    }
  }
  return ticks;
}

export type { Plot, SourceChartData, SourceMark, SourceObservation, SourceWindow, Span, YearTick };
export { dateX, MINIMUM_SPAN, markerSpan, reportedShares, sourceMarks, yearTicks };

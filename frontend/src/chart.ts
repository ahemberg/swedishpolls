import type { Series } from "./bootstrap";

/**
 * The timeline's geometry and its paths.
 *
 * A run of consecutive estimated days becomes one path. A null breaks the run, so a period without
 * a supported estimate leaves a gap, and nothing is drawn across it. A day that merely has no new
 * poll still carries an estimate, so it never breaks anything.
 */

const WIDTH = 960;
const HEIGHT = 380;
const LEFT = 40;
const RIGHT = 64;
const TOP = 14;
const BOTTOM = 26;
const THRESHOLD_PERCENT = 4;
const GRID_STEP = 5;
const AXIS_STEP = 10;
const PLOT_WIDTH = WIDTH - LEFT - RIGHT;
const PLOT_HEIGHT = HEIGHT - BOTTOM - TOP;
const BASELINE = HEIGHT - BOTTOM;
const MINIMUM_MAXIMUM = 10;
const DECIMALS = 1;
const YEAR_LENGTH = 4;
const MOVE = "M";
const DRAW = "L";

interface Span {
  readonly x: number;
  readonly top: number;
  readonly bottom: number;
}

interface YearMark {
  readonly year: string;
  readonly index: number;
}

function isNumber(value: number | null): value is number {
  return value !== null;
}

/** The horizontal position of one sampled day. */
function xAt(index: number, days: number): number {
  if (days < 2) {
    return LEFT;
  }
  return LEFT + (PLOT_WIDTH * index) / (days - 1);
}

/** The vertical position of a percentage on an axis running from zero to the maximum. */
function yAt(value: number, maximum: number): number {
  return BASELINE - (PLOT_HEIGHT * value) / maximum;
}

/** The highest estimated or upper-interval value one series reaches in the sampled range. */
function peak(series: Series): number {
  const values = [...series.upper, ...series.mean].filter(isNumber);
  return Math.max(THRESHOLD_PERCENT, ...values);
}

/** The top of the axis: the highest drawn interval, rounded up, never below the threshold line. */
function axisMaximum(drawn: readonly Series[], observations: readonly number[] = []): number {
  const highest = Math.max(THRESHOLD_PERCENT, ...drawn.map(peak), ...observations);
  return Math.max(MINIMUM_MAXIMUM, Math.ceil(highest / GRID_STEP) * GRID_STEP);
}

/** The mean line, broken wherever the estimate is unavailable. */
function linePath(
  values: readonly (number | null)[],
  x: (index: number) => number,
  y: (value: number) => number,
): string {
  let path = "";
  let pen = false;
  for (const [index, value] of values.entries()) {
    if (value === null) {
      pen = false;
    } else {
      let command = MOVE;
      if (pen) {
        command = DRAW;
      }
      path += `${command}${x(index).toFixed(DECIMALS)},${y(value).toFixed(DECIMALS)}`;
      pen = true;
    }
  }
  return path;
}

function area(spans: readonly Span[]): string {
  let up = "";
  for (const [position, span] of spans.entries()) {
    let command = MOVE;
    if (position > 0) {
      command = DRAW;
    }
    up += `${command}${span.x.toFixed(DECIMALS)},${span.top.toFixed(DECIMALS)}`;
  }
  let down = "";
  for (const span of [...spans].reverse()) {
    down += `L${span.x.toFixed(DECIMALS)},${span.bottom.toFixed(DECIMALS)}`;
  }
  return `${up}${down}Z`;
}

/** A value the series actually carries for that day, or null where it carries none. */
function valueAt(values: readonly (number | null)[], index: number): number | null {
  return values[index] ?? null;
}

/** One day's band, or null where the interval is unavailable and the band has to break. */
function spanAt(
  series: Series,
  index: number,
  x: (index: number) => number,
  y: (value: number) => number,
): Span | null {
  const upper = valueAt(series.upper, index);
  const lower = valueAt(series.lower, index);
  if (upper === null || lower === null) {
    return null;
  }
  return { x: x(index), top: y(upper), bottom: y(lower) };
}

/** Consecutive days that all have an interval. A single day is too short to fill. */
function runs(spans: readonly (Span | null)[]): readonly Span[][] {
  const found: Span[][] = [];
  let run: Span[] = [];
  for (const span of spans) {
    if (span === null) {
      found.push(run);
      run = [];
    } else {
      run.push(span);
    }
  }
  found.push(run);
  return found.filter((entry) => entry.length > 1);
}

/** One filled interval band per unbroken run, so a gap in the estimate is a gap in the band. */
function bandPath(
  series: Series,
  x: (index: number) => number,
  y: (value: number) => number,
): string {
  const spans = series.upper.map((_, index) => spanAt(series, index, x, y));
  return runs(spans).map(area).join("");
}

/** The index of the last estimated day, or -1 when the series has none in this range. */
function lastEstimated(series: Series): number {
  let last = -1;
  for (const [index, value] of series.mean.entries()) {
    if (value !== null) {
      last = index;
    }
  }
  return last;
}

/** Where each calendar year begins inside the sampled range. */
function yearMarks(dates: readonly string[]): readonly YearMark[] {
  return dates
    .map((day, index) => ({ year: day.slice(0, YEAR_LENGTH), index }))
    .filter((mark) => mark.index > 0 && mark.year !== yearOf(dates, mark.index - 1));
}

function yearOf(dates: readonly string[], index: number): string {
  return (dates[index] ?? "").slice(0, YEAR_LENGTH);
}

/** The axis levels a reader sees labelled. */
function axisLevels(maximum: number): readonly number[] {
  const levels: number[] = [];
  for (let value = 0; value <= maximum; value += AXIS_STEP) {
    levels.push(value);
  }
  return levels;
}

export type { YearMark };
export {
  axisLevels,
  axisMaximum,
  BASELINE,
  bandPath,
  HEIGHT,
  LEFT,
  lastEstimated,
  linePath,
  PLOT_WIDTH,
  RIGHT,
  THRESHOLD_PERCENT,
  TOP,
  WIDTH,
  xAt,
  yAt,
  yearMarks,
};

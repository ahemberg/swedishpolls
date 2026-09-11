import type { JSX } from "react";
import type { Bootstrap, Boundary, Series, Translate } from "./bootstrap";
import { BASELINE, HEIGHT, TOP, WIDTH } from "./chart";
import { colour, decimal, percent, shortDate } from "./format";
import {
  Bands,
  Boundaries,
  ElectionDots,
  EndLabels,
  Gridlines,
  Lines,
  YearLines,
} from "./timeline-chart";
import type { TimelineState } from "./useTimeline";

/** The drawn figure and the two ways to move its cursor: dragging it, or stepping the range input. */

interface FigureProps {
  readonly page: Bootstrap;
  readonly state: TimelineState;
  readonly boundaries: readonly Boundary[];
  readonly summary: string;
  readonly t: Translate;
}

interface ReadoutProps {
  readonly page: Bootstrap;
  readonly drawn: readonly Series[];
  readonly index: number;
  readonly day: string;
  readonly t: Translate;
}

interface ScrubberProps {
  readonly id: string;
  readonly state: TimelineState;
  readonly day: string;
  readonly locale: string;
  readonly t: Translate;
}

/** A missing day reads as missing: never a zero, never the neighbouring day's value. */
function reading(page: Bootstrap, series: Series, index: number, t: Translate): string {
  const value = series.mean[index];
  const name = page.labels[series.component] ?? series.component;
  if (value === undefined || value === null) {
    return `${name} ${t("estimate.unavailable")}`;
  }
  return `${name} ${percent(decimal(value, page.language), page.language)}`;
}

function TimelineReadout({ page, drawn, index, day, t }: ReadoutProps): JSX.Element {
  return (
    <p className="readout" aria-live="polite">
      <b>{shortDate(day, page.locale)}</b>
      {drawn.map((entry) => (
        <span key={entry.component}>
          <span className="swatch" style={{ background: colour(entry.component) }} />
          {reading(page, entry, index, t)}
        </span>
      ))}
    </p>
  );
}

function TimelineFigure({ page, state, boundaries, summary, t }: FigureProps): JSX.Element {
  const { drawn, dates, maximum, index, x, y, scrub, svg } = state;
  return (
    <svg
      ref={svg}
      className="chart"
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      role="img"
      aria-label={summary}
      onPointerDown={(event) => scrub(event.clientX)}
      onPointerMove={(event) => {
        if (event.buttons > 0) {
          scrub(event.clientX);
        }
      }}
    >
      <title>{summary}</title>
      <Gridlines maximum={maximum} y={y} page={page} thresholdLabel={t("timeline.threshold")} />
      <YearLines dates={dates} x={x} />
      <Boundaries boundaries={boundaries} dates={dates} x={x} />
      <Bands drawn={drawn} x={x} y={y} />
      <Lines drawn={drawn} x={x} y={y} />
      <ElectionDots page={page} dates={dates} x={x} y={y} />
      <EndLabels page={page} drawn={drawn} y={y} />
      <line
        x1={x(index)}
        x2={x(index)}
        y1={TOP}
        y2={BASELINE}
        stroke="var(--muted)"
        strokeWidth="1"
      />
    </svg>
  );
}

function TimelineScrubber({ id, state, day, locale, t }: ScrubberProps): JSX.Element {
  return (
    <p>
      <label htmlFor={id}>{t("timeline.readout.label")}</label>
      <input
        id={id}
        type="range"
        min={0}
        max={state.lastIndex}
        step={1}
        value={state.index}
        aria-valuetext={shortDate(day, locale)}
        onChange={(event) => state.setCursor(Number(event.target.value))}
      />
    </p>
  );
}

export { TimelineFigure, TimelineReadout, TimelineScrubber };

import type { JSX } from "react";
import type { Bootstrap, Boundary, PartyObservation, Series, Translate } from "./bootstrap";
import { BASELINE, HEIGHT, TOP, WIDTH } from "./chart";
import { colour, decimal, percent, shortDate } from "./format";
import { componentName } from "./labels";
import { Markers, Selection, YearRules } from "./source-chart-figure";
import {
  Bands,
  Boundaries,
  ElectionDots,
  EndLabels,
  Gridlines,
  Lines,
  PollDots,
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
  readonly component: string | undefined;
  readonly observations?: readonly PartyObservation[];
}

interface ReadoutProps {
  readonly page: Bootstrap;
  readonly drawn: readonly Series[];
  readonly index: number;
  readonly day: string;
  readonly t: Translate;
  readonly coveragePeriod: string | null;
}

interface ScrubberProps {
  readonly id: string;
  readonly state: TimelineState;
  readonly day: string;
  readonly locale: string;
  readonly t: Translate;
}

function Years({
  state,
  dates,
  x,
}: {
  readonly state: TimelineState;
  readonly dates: readonly string[];
  readonly x: (value: number) => number;
}): JSX.Element {
  if (state.window !== undefined) {
    return <YearRules window={state.window} />;
  }
  return <YearLines dates={dates} x={x} />;
}

/** A missing day reads as missing: never a zero, never the neighbouring day's value. */
function reading(page: Bootstrap, series: Series, index: number, t: Translate): string {
  const value = series.mean[index];
  const name = componentName(page, series.component);
  if (value === undefined || value === null) {
    return `${name} ${t("estimate.unavailable")}`;
  }
  return `${name} ${percent(decimal(value, page.language), page.language)}`;
}

function TimelineReadout({
  page,
  drawn,
  index,
  day,
  t,
  coveragePeriod,
}: ReadoutProps): JSX.Element {
  const coverage = page.data?.latest.coveragePeriods.find((entry) => entry.id === coveragePeriod);
  let coverageReading = t("timeline.noCoverage");
  if (coverage !== undefined) {
    coverageReading = t("timeline.coverage", {
      period: coverage.id,
      roster: coverage.roster.map((entry) => componentName(page, entry)).join(", "),
    });
  }
  return (
    <p className="readout" aria-live="polite">
      <b>{shortDate(day, page.locale)}</b>
      {drawn.map((entry) => (
        <span key={entry.component}>
          <span className="swatch" style={{ background: colour(entry.component) }} />
          {reading(page, entry, index, t)}
        </span>
      ))}
      <span>{coverageReading}</span>
    </p>
  );
}

function SourceMarks({ state }: { readonly state: TimelineState }): JSX.Element | null {
  if (state.source === null || state.window === undefined) {
    return null;
  }
  return (
    <>
      <Selection
        observation={state.source.observations[state.source.cursor.index]}
        window={state.window}
      />
      <Markers
        marks={state.source.marks}
        maximum={state.maximum}
        selected={selectedPoll(state.source.observations, state.source.cursor.index)}
      />
    </>
  );
}

function selectedPoll(
  observations: readonly { readonly pollId: string }[],
  index: number,
): string | null {
  const observation = observations[index];
  if (observation === undefined) {
    return null;
  }
  return observation.pollId;
}

function scrubAt(state: TimelineState, clientX: number, estimate: boolean): void {
  if (estimate) {
    state.scrub(clientX);
  }
  state.source?.cursor.scrub(clientX);
}

function TimelineFigure({
  page,
  state,
  boundaries,
  summary,
  t,
  component,
  observations = [],
}: FigureProps): JSX.Element {
  const { drawn, dates, maximum, index, x, y, svg } = state;
  return (
    <svg
      ref={svg}
      className="chart"
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      role="img"
      aria-label={summary}
      onPointerDown={(event) => scrubAt(state, event.clientX, true)}
      onPointerMove={(event) => scrubAt(state, event.clientX, event.buttons > 0)}
    >
      <title>{summary}</title>
      <Gridlines maximum={maximum} y={y} page={page} thresholdLabel={t("timeline.threshold")} />
      <Years state={state} dates={dates} x={x} />
      <Boundaries boundaries={boundaries} dates={dates} x={x} />
      <Bands drawn={drawn} x={x} y={y} />
      <Lines drawn={drawn} x={x} y={y} />
      <SourceMarks state={state} />
      <PollDots
        observations={observations}
        component={component ?? null}
        dates={dates}
        x={x}
        y={y}
      />
      <ElectionDots page={page} dates={dates} x={x} y={y} component={component} />
      <EndLabels page={page} drawn={drawn} x={x} y={y} />
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

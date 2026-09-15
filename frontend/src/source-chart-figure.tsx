import type { JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { BASELINE, HEIGHT, PLOT, TOP, WIDTH, yAt } from "./chart";
import { colour } from "./format";
import type { SourceMark, SourceObservation, SourceWindow } from "./source-chart";
import { markerSpan, yearTicks } from "./source-chart";
import { Gridlines } from "./timeline-chart";
import type { SourceChartState } from "./useSourceChart";

/**
 * The drawn layers of the source chart: the year rules, the selection band and the markers.
 *
 * A pointer moving over the figure reads a marker out without being pressed, so hovering reveals
 * the details a press or the slider would. On a touch screen a move only arrives while a finger is
 * down, which makes the same handler the drag.
 */

const DASH_APPROXIMATE = "4 3";
const SOLID = "none";
const MARK_WIDTH = 3;
const SELECTED_WIDTH = 5;
const YEAR_LABEL_Y = 14;

function YearRules({ window }: { readonly window: SourceWindow }): JSX.Element {
  return (
    <g>
      {yearTicks(window, PLOT).map((tick) => (
        <g key={tick.year}>
          <line x1={tick.x} x2={tick.x} y1={TOP} y2={BASELINE} stroke="var(--line)" />
          <text
            x={tick.x}
            y={BASELINE + YEAR_LABEL_Y}
            fontSize="11"
            fill="var(--faint)"
            textAnchor="middle"
          >
            {tick.year}
          </text>
        </g>
      ))}
    </g>
  );
}

/** Where the observation being read sits, so the readout below names something visible. */
function Selection({
  observation,
  window,
}: {
  readonly observation: SourceObservation | undefined;
  readonly window: SourceWindow;
}): JSX.Element | null {
  if (observation === undefined) {
    return null;
  }
  const span = markerSpan(observation, window, PLOT);
  return (
    <rect
      x={span.x1}
      y={TOP}
      width={Math.max(1, span.x2 - span.x1)}
      height={BASELINE - TOP}
      fill="var(--muted)"
      opacity="0.18"
    />
  );
}

/** A selected mark is drawn heavier, so the readout below names something a reader can see. */
function markWidth(mark: SourceMark, selected: string | null): number {
  if (mark.observation.pollId === selected) {
    return SELECTED_WIDTH;
  }
  return MARK_WIDTH;
}

/** An approximate interview period is dashed; a reported one is solid. */
function markDash(mark: SourceMark): string {
  if (mark.observation.approximatePeriod) {
    return DASH_APPROXIMATE;
  }
  return SOLID;
}

/**
 * One horizontal mark per reported share, over the dates the institute actually interviewed on. An
 * approximate period is dashed, so a reader can tell a reported period from a reconstructed one
 * without opening the details.
 */
function Markers({
  marks,
  maximum,
  selected,
}: {
  readonly marks: readonly SourceMark[];
  readonly maximum: number;
  readonly selected: string | null;
}): JSX.Element {
  return (
    <g>
      {marks.map((mark) => (
        <line
          key={mark.key}
          x1={mark.x1}
          x2={mark.x2}
          y1={yAt(mark.share, maximum)}
          y2={yAt(mark.share, maximum)}
          stroke={colour(mark.component)}
          strokeWidth={markWidth(mark, selected)}
          strokeLinecap="round"
          strokeDasharray={markDash(mark)}
        />
      ))}
    </g>
  );
}

function selectedId(observation: SourceObservation | undefined): string | null {
  if (observation === undefined) {
    return null;
  }
  return observation.pollId;
}

function SourceChartFigure({
  page,
  state,
  summary,
  t,
}: {
  readonly page: Bootstrap;
  readonly state: SourceChartState;
  readonly summary: string;
  readonly t: Translate;
}): JSX.Element {
  const selected = state.observations[state.index];
  return (
    <svg
      ref={state.svg}
      className="chart"
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      role="img"
      aria-label={summary}
      onPointerDown={(event) => state.scrub(event.clientX)}
      onPointerMove={(event) => state.scrub(event.clientX)}
    >
      <title>{summary}</title>
      <Gridlines
        maximum={state.maximum}
        y={(value) => yAt(value, state.maximum)}
        page={page}
        thresholdLabel={t("timeline.threshold")}
      />
      <YearRules window={state.data.range} />
      <Selection observation={selected} window={state.data.range} />
      <Markers marks={state.marks} maximum={state.maximum} selected={selectedId(selected)} />
    </svg>
  );
}

export { Markers, Selection, SourceChartFigure, YearRules };

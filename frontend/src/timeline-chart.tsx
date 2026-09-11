import type { JSX } from "react";
import type { Bootstrap, Boundary, PartyObservation, Series } from "./bootstrap";
import {
  axisLevels,
  BASELINE,
  bandPath,
  LEFT,
  lastEstimated,
  linePath,
  RIGHT,
  THRESHOLD_PERCENT,
  TOP,
  WIDTH,
  yearMarks,
} from "./chart";
import { colour, decimal, percent } from "./format";

/** The drawn layers of the timeline, each one small enough to read on its own. */

const DASH_THRESHOLD = "5 4";
const DASH_BOUNDARY = "2 4";
const LABEL_OFFSET = 6;
const TEXT_BASELINE = 4;
const AXIS_LABEL_GAP = 6;
const YEAR_LABEL_Y = 8;
const ELECTION_RADIUS = 4;
const POLL_RADIUS = 3;
const PERCENT_SCALE = 100;

type Scale = (value: number) => number;

interface LayerProps {
  readonly page: Bootstrap;
  readonly drawn: readonly Series[];
  readonly x: Scale;
  readonly y: Scale;
}

function Gridlines({
  maximum,
  y,
  page,
  thresholdLabel,
}: {
  readonly maximum: number;
  readonly y: Scale;
  readonly page: Bootstrap;
  readonly thresholdLabel: string;
}): JSX.Element {
  return (
    <g>
      {axisLevels(maximum).map((value) => (
        <g key={value}>
          <line x1={LEFT} x2={WIDTH - RIGHT} y1={y(value)} y2={y(value)} stroke="var(--line)" />
          <text
            x={LEFT - AXIS_LABEL_GAP}
            y={y(value) + TEXT_BASELINE}
            textAnchor="end"
            fontSize="11"
            fill="var(--faint)"
          >
            {percent(String(value), page.language)}
          </text>
        </g>
      ))}
      <line
        x1={LEFT}
        x2={WIDTH - RIGHT}
        y1={y(THRESHOLD_PERCENT)}
        y2={y(THRESHOLD_PERCENT)}
        stroke="var(--faint)"
        strokeDasharray={DASH_THRESHOLD}
      />
      <text
        x={LEFT + TEXT_BASELINE}
        y={y(THRESHOLD_PERCENT) - AXIS_LABEL_GAP}
        fontSize="10.5"
        fill="var(--faint)"
      >
        {thresholdLabel}
      </text>
    </g>
  );
}

function YearLines({
  dates,
  x,
}: {
  readonly dates: readonly string[];
  readonly x: Scale;
}): JSX.Element {
  return (
    <g>
      {yearMarks(dates).map((mark) => (
        <g key={mark.year}>
          <line x1={x(mark.index)} x2={x(mark.index)} y1={TOP} y2={BASELINE} stroke="var(--line)" />
          <text
            x={x(mark.index)}
            y={BASELINE + YEAR_LABEL_Y + AXIS_LABEL_GAP}
            fontSize="11"
            fill="var(--faint)"
            textAnchor="middle"
          >
            {mark.year}
          </text>
        </g>
      ))}
    </g>
  );
}

/** A separate fit starts here. Nothing is drawn across the rule, and a break is not movement. */
function Boundaries({
  boundaries,
  dates,
  x,
}: {
  readonly boundaries: readonly Boundary[];
  readonly dates: readonly string[];
  readonly x: Scale;
}): JSX.Element {
  const inside = boundaries.filter((boundary) => dates.indexOf(boundary.date) > 0);
  return (
    <g>
      {inside.map((boundary) => (
        <line
          key={`${boundary.kind}-${boundary.date}`}
          x1={x(dates.indexOf(boundary.date))}
          x2={x(dates.indexOf(boundary.date))}
          y1={TOP}
          y2={BASELINE}
          stroke="var(--muted)"
          strokeDasharray={DASH_BOUNDARY}
        />
      ))}
    </g>
  );
}

function Bands({ drawn, x, y }: Omit<LayerProps, "page">): JSX.Element {
  return (
    <g>
      {drawn.map((series) => (
        <path
          key={series.component}
          d={bandPath(series, x, y)}
          fill={colour(series.component)}
          opacity="var(--band-alpha)"
        />
      ))}
    </g>
  );
}

function Lines({ drawn, x, y }: Omit<LayerProps, "page">): JSX.Element {
  return (
    <g>
      {drawn.map((series) => (
        <path
          key={series.component}
          d={linePath(series.mean, x, y)}
          fill="none"
          stroke={colour(series.component)}
          strokeWidth="2"
          strokeLinejoin="round"
        />
      ))}
    </g>
  );
}

/** Actual election results, drawn as open rings so they never read as modelled estimates. */
function ElectionDots({
  page,
  dates,
  x,
  y,
  component,
}: {
  readonly page: Bootstrap;
  readonly dates: readonly string[];
  readonly x: Scale;
  readonly y: Scale;
  readonly component: string | undefined;
}): JSX.Element {
  const elections = page.data?.elections.elections ?? [];
  return (
    <g>
      {elections.flatMap((election) => {
        const index = dates.findIndex((day) => day >= election.electionDate);
        if (index < 0) {
          return [];
        }
        return Object.entries(election.results).flatMap(([key, result]) => {
          if (component !== undefined && component !== key) {
            return [];
          }
          return [
            <circle
              key={`${election.electionDate}-${key}`}
              cx={x(index)}
              cy={y((result.votes / election.validVotes) * PERCENT_SCALE)}
              r={ELECTION_RADIUS}
              fill="none"
              stroke={colour(key)}
              strokeWidth="2"
            />,
          ];
        });
      })}
    </g>
  );
}

function observationDay(observation: PartyObservation): string | null {
  const from = observation.collectionFrom;
  const to = observation.collectionTo;
  if (from === null) {
    return to;
  }
  if (to === null) {
    return from;
  }
  const midpoint = (Date.parse(`${from}T00:00:00Z`) + Date.parse(`${to}T00:00:00Z`)) / 2;
  return new Date(midpoint).toISOString().slice(0, 10);
}

function closest(dates: readonly string[], day: string): number {
  let match = 0;
  let distance = Number.POSITIVE_INFINITY;
  for (const [index, date] of dates.entries()) {
    const candidate = Math.abs(Date.parse(date) - Date.parse(day));
    if (candidate < distance) {
      match = index;
      distance = candidate;
    }
  }
  return match;
}

/** Source observations are solid dots, distinct from both the estimate and election rings. */
function PollDots({
  observations,
  component,
  dates,
  x,
  y,
}: {
  readonly observations: readonly PartyObservation[];
  readonly component: string | null;
  readonly dates: readonly string[];
  readonly x: Scale;
  readonly y: Scale;
}): JSX.Element | null {
  const [first] = dates;
  const last = dates.at(-1);
  if (component === null || first === undefined || last === undefined) {
    return null;
  }
  return (
    <g>
      {observations.flatMap((observation) => {
        const day = observationDay(observation);
        if (day === null || day < first || day > last) {
          return [];
        }
        return [
          <circle
            key={observation.pollId}
            cx={x(closest(dates, day))}
            cy={y(observation.share)}
            r={POLL_RADIUS}
            fill={colour(component)}
            stroke="var(--ground)"
          >
            <title>{dotTitle(observation)}</title>
          </circle>,
        ];
      })}
    </g>
  );
}

function dotTitle(observation: PartyObservation): string {
  const from = observation.collectionFrom ?? "?";
  const to = observation.collectionTo ?? "?";
  return `${observation.institute}, ${from} - ${to}: ${observation.share}`;
}

/** The identity cue that is not colour: the key and the value, written at the end of the line. */
function EndLabels({ page, drawn, y }: Omit<LayerProps, "x">): JSX.Element {
  const labelled = drawn.flatMap((series) => {
    const index = lastEstimated(series);
    const value = series.mean[index];
    if (index < 0 || value === undefined || value === null) {
      return [];
    }
    return [{ component: series.component, value }];
  });
  return (
    <g>
      {labelled.map((entry) => (
        <text
          key={entry.component}
          x={WIDTH - RIGHT + LABEL_OFFSET}
          y={y(entry.value) + TEXT_BASELINE}
          fontSize="12"
          fontWeight="700"
          fill={colour(entry.component)}
        >
          {`${entry.component} ${decimal(entry.value, page.language)}`}
        </text>
      ))}
    </g>
  );
}

export { Bands, Boundaries, ElectionDots, EndLabels, Gridlines, Lines, PollDots, YearLines };

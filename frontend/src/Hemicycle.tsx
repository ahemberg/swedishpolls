import type { JSX } from "react";
import type { Seats } from "./bootstrap";
import { colour } from "./format";
import {
  CENTRE_X,
  CENTRE_Y,
  MARKER_OVERHANG,
  majorityMarker,
  RADIUS,
  STROKE,
  segments,
  VIEW_HEIGHT,
  VIEW_WIDTH,
} from "./hemicycle-geometry";

/** The seat arc itself: integer point seats, and the marker at the majority line. */

const TITLE_GAP = 10;
const TOTAL_GAP = 16;

interface Props {
  readonly seats: Seats;
  readonly majority: number;
  readonly label: string;
  readonly majorityLabel: string;
  readonly totalLabel: string;
}

function Hemicycle({ seats, majority, label, majorityLabel, totalLabel }: Props): JSX.Element {
  const marker = majorityMarker(majority, seats.totalSeats);
  return (
    <svg
      viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
      className="chart hemicycle"
      role="img"
      aria-label={label}
    >
      <title>{label}</title>
      {segments(seats).map((segment) => (
        <path
          key={segment.component}
          d={segment.path}
          fill="none"
          stroke={colour(segment.component)}
          strokeWidth={STROKE}
        />
      ))}
      <line
        x1={marker.from.x}
        y1={marker.from.y}
        x2={marker.to.x}
        y2={marker.to.y}
        stroke="var(--ink)"
        strokeWidth="2"
      />
      <text
        x={CENTRE_X}
        y={CENTRE_Y - RADIUS - STROKE / 2 - TITLE_GAP}
        textAnchor="middle"
        fontSize="11"
        fill="var(--faint)"
      >
        {majorityLabel}
      </text>
      <text
        x={CENTRE_X}
        y={CENTRE_Y + TOTAL_GAP}
        textAnchor="middle"
        fontSize="11"
        fill="var(--faint)"
      >
        {totalLabel}
      </text>
      <line
        x1={CENTRE_X - RADIUS - MARKER_OVERHANG}
        y1={CENTRE_Y}
        x2={CENTRE_X + RADIUS + MARKER_OVERHANG}
        y2={CENTRE_Y}
        stroke="var(--line)"
      />
    </svg>
  );
}

export { Hemicycle };

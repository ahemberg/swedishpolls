import type { Seats } from "./chamber";

/**
 * The seat arc's geometry.
 *
 * Segments come from integer point seats, not from rounded posterior means, and OTHER never
 * appears: it is an aggregate rather than a party and receives no seats as a group.
 */

const VIEW_WIDTH = 340;
const VIEW_HEIGHT = 176;
const CENTRE_X = 170;
const CENTRE_Y = 150;
const RADIUS = 92;
const STROKE = 44;
const SEGMENT_GAP = 0.012;
const MARKER_OVERHANG = 4;
const DECIMALS = 1;

/** A fixed left-to-right display order, so the arc reads the same in every publication. */
const ORDER = ["V", "FI", "S", "MP", "C", "L", "KD", "M", "SD"];

interface Segment {
  readonly component: string;
  readonly seats: number;
  readonly path: string;
}

interface Point {
  readonly x: number;
  readonly y: number;
}

function point(angle: number, radius: number): Point {
  return { x: CENTRE_X + radius * Math.cos(angle), y: CENTRE_Y - radius * Math.sin(angle) };
}

function arc(from: number, to: number): string {
  const start = point(from, RADIUS);
  const end = point(to, RADIUS);
  const startX = start.x.toFixed(DECIMALS);
  const startY = start.y.toFixed(DECIMALS);
  const endX = end.x.toFixed(DECIMALS);
  const endY = end.y.toFixed(DECIMALS);
  return `M${startX},${startY} A${RADIUS},${RADIUS} 0 0 1 ${endX},${endY}`;
}

/** The parties that actually hold seats. A party with none occupies no arc. */
function allocated(seats: Seats): ReadonlyMap<string, number> {
  const held = new Map<string, number>();
  for (const party of seats.parties) {
    if (party.pointSeats !== null && party.pointSeats > 0) {
      held.set(party.component, party.pointSeats);
    }
  }
  return held;
}

/** The allocated parties, in display order, each with the arc it occupies. */
function segments(seats: Seats): readonly Segment[] {
  const held = allocated(seats);
  const drawn: Segment[] = [];
  let angle = Math.PI;
  for (const component of ORDER) {
    const seatsHeld = held.get(component);
    if (seatsHeld !== undefined) {
      const end = angle - (Math.PI * seatsHeld) / seats.totalSeats;
      drawn.push({
        component,
        seats: seatsHeld,
        path: arc(angle - SEGMENT_GAP, end + SEGMENT_GAP),
      });
      angle = end;
    }
  }
  return drawn;
}

/** The 175-seat marker, drawn straight across the band rather than alongside it. */
function majorityMarker(
  majority: number,
  total: number,
): { readonly from: Point; readonly to: Point } {
  const angle = Math.PI - (Math.PI * majority) / total;
  return {
    from: point(angle, RADIUS - STROKE / 2 - MARKER_OVERHANG),
    to: point(angle, RADIUS + STROKE / 2 + MARKER_OVERHANG),
  };
}

export type { Point, Segment };
export {
  CENTRE_X,
  CENTRE_Y,
  MARKER_OVERHANG,
  majorityMarker,
  RADIUS,
  STROKE,
  segments,
  VIEW_HEIGHT,
  VIEW_WIDTH,
};

import type { JSX } from "react";
import type { History } from "./bootstrap";
import { colour } from "./format";

/**
 * The estimate table's trend column: a bare line, no axis, no label. It is decoration beside a
 * number the reader already has, so it is hidden below 640px and hidden from assistive technology.
 */

const WIDTH = 110;
const HEIGHT = 26;
const PADDING = 2;
const FLOOR = 3;
const HEADROOM = 8;
const DECIMALS = 1;
const MOVE = "M";
const DRAW = "L";

interface Props {
  readonly history: History;
  readonly component: string;
}

function path(values: readonly (number | null)[], highest: number): string {
  let drawn = "";
  let pen = false;
  const span = Math.max(1, values.length - 1);
  for (const [index, value] of values.entries()) {
    if (value === null) {
      pen = false;
    } else {
      let command = MOVE;
      if (pen) {
        command = DRAW;
      }
      const x = PADDING + ((WIDTH - PADDING * 2) * index) / span;
      const y = HEIGHT - FLOOR - ((HEIGHT - HEADROOM) * value) / highest;
      drawn += `${command}${x.toFixed(DECIMALS)},${y.toFixed(DECIMALS)}`;
      pen = true;
    }
  }
  return drawn;
}

function Sparkline({ history, component }: Props): JSX.Element | null {
  const series = history.series.find((entry) => entry.component === component);
  if (series === undefined) {
    return null;
  }
  const estimated = series.mean.filter((value): value is number => value !== null);
  if (estimated.length === 0) {
    return null;
  }
  const highest = Math.max(1, ...estimated);
  return (
    <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} width={WIDTH} height={HEIGHT} aria-hidden="true">
      <path
        d={path(series.mean, highest)}
        fill="none"
        stroke={colour(component)}
        strokeWidth="1.8"
      />
    </svg>
  );
}

export { Sparkline };

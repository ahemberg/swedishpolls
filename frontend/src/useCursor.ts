import { useCallback, useRef, useState } from "react";
import { LEFT, PLOT_WIDTH, WIDTH } from "./chart";

/**
 * The scrubbed day.
 *
 * One position drives the readout, the cursor line and the range input, so a drag with a mouse, a
 * drag with a finger and a press of an arrow key all move the same thing. With no position chosen
 * the cursor rests on the last day in the range.
 */

interface Cursor {
  readonly svg: React.RefObject<SVGSVGElement | null>;
  readonly index: number;
  readonly lastIndex: number;
  readonly setCursor: (index: number) => void;
  readonly reset: () => void;
  readonly scrub: (clientX: number) => void;
}

function useCursor(days: number): Cursor {
  const [cursor, setCursor] = useState<number | null>(null);
  const svg = useRef<SVGSVGElement | null>(null);
  const lastIndex = Math.max(0, days - 1);
  const index = Math.min(cursor ?? lastIndex, lastIndex);

  const scrub = useCallback(
    (clientX: number) => {
      const element = svg.current;
      if (element === null || days === 0) {
        return;
      }
      const box = element.getBoundingClientRect();
      const inside = ((clientX - box.left) / box.width) * WIDTH;
      const position = Math.round(((inside - LEFT) / PLOT_WIDTH) * (days - 1));
      setCursor(Math.max(0, Math.min(days - 1, position)));
    },
    [days],
  );
  const reset = useCallback(() => setCursor(null), []);

  return { svg, index, lastIndex, setCursor, reset, scrub };
}

export type { Cursor };
export { useCursor };

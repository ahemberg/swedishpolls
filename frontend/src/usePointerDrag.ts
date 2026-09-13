import { type PointerEvent as ReactPointerEvent, useRef, useState } from "react";
import type { Destination, Party } from "./coalition-history";

const DESTINATIONS = ["a", "b", "unassigned"] as const;
const DRAG_DISTANCE = 5;

interface Drag {
  readonly party: Party;
  readonly pointerId: number;
  readonly startX: number;
  readonly startY: number;
  readonly active: boolean;
  readonly over?: Destination;
}

function dragAt(current: Drag, event: ReactPointerEvent<HTMLButtonElement>): Drag {
  const active =
    current.active ||
    Math.hypot(event.clientX - current.startX, event.clientY - current.startY) >= DRAG_DISTANCE;
  const element = document.elementFromPoint(event.clientX, event.clientY);
  const value = element?.closest<HTMLElement>("[data-destination]")?.dataset.destination;
  const over = DESTINATIONS.find((candidate) => candidate === value);
  const next: Drag = {
    party: current.party,
    pointerId: current.pointerId,
    startX: current.startX,
    startY: current.startY,
    active,
  };
  if (over === undefined) {
    return next;
  }
  return { ...next, over };
}

function finishDrop(
  finished: Drag,
  onDrop: (party: Party, destination: Destination) => void,
): void {
  if (finished.active && finished.over !== undefined) {
    onDrop(finished.party, finished.over);
  }
}

function usePointerDrag(onDrop: (party: Party, destination: Destination) => void) {
  const [drag, setDrag] = useState<Drag | null>(null);
  const current = useRef<Drag | null>(null);
  const dragged = useRef(false);
  const store = (next: Drag | null): void => {
    current.current = next;
    setDrag(next);
  };
  const start = (event: ReactPointerEvent<HTMLButtonElement>, party: Party): void => {
    if (event.button !== 0) {
      return;
    }
    event.currentTarget.setPointerCapture(event.pointerId);
    dragged.current = false;
    store({
      party,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      active: false,
    });
  };
  const move = (event: ReactPointerEvent<HTMLButtonElement>): void => {
    if (current.current === null || current.current.pointerId !== event.pointerId) {
      return;
    }
    const next = dragAt(current.current, event);
    dragged.current ||= next.active;
    store(next);
  };
  const finish = (event: ReactPointerEvent<HTMLButtonElement>): void => {
    const existing = current.current;
    if (existing === null || existing.pointerId !== event.pointerId) {
      return;
    }
    const finished = dragAt(existing, event);
    store(null);
    finishDrop(finished, onDrop);
  };
  const cancel = (): void => {
    dragged.current = false;
    store(null);
  };
  return { cancel, drag, dragged, finish, move, start };
}

export type { Drag };
export { usePointerDrag };

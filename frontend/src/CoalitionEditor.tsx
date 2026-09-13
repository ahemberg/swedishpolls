import { type JSX, type PointerEvent as ReactPointerEvent, useRef, useState } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { CoalitionBalance } from "./CoalitionBalance";
import {
  type Assignment,
  assign,
  type Block,
  blockColors,
  type CoalitionHistoryData,
  type Destination,
  destinationOf,
  emptyAssignment,
  groupsFor,
  PARTIES,
  type Party,
  PRESET,
  tileColor,
  total,
} from "./coalition-history";
import { share } from "./format";

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

interface Props {
  readonly assignment: Assignment;
  readonly initial: CoalitionHistoryData;
  readonly onChange: (assignment: Assignment) => void;
  readonly page: Bootstrap;
  readonly t: Translate;
}

function value(number: number | null, page: Bootstrap, t: Translate): string {
  return share(number, page.language, t);
}

function destinationName(destination: Destination, t: Translate): string {
  return t(`coalitionEditor.${destination}`);
}

interface ZoneProps extends Omit<Props, "onChange"> {
  readonly colors: readonly string[];
  readonly destination: Destination;
  readonly drag: Drag | null;
  readonly onCancel: () => void;
  readonly onFinish: (event: ReactPointerEvent<HTMLButtonElement>) => void;
  readonly onMove: (party: Party, destination: Destination) => void;
  readonly onPointerMove: (event: ReactPointerEvent<HTMLButtonElement>) => void;
  readonly onSelect: (party: Party) => void;
  readonly onStart: (event: ReactPointerEvent<HTMLButtonElement>, party: Party) => void;
  readonly picked: Party | null;
}

function classWhen(base: string, condition: boolean, addition: string): string {
  if (condition) {
    return `${base} ${addition}`;
  }
  return base;
}

function Zone(props: ZoneProps): JSX.Element {
  const { assignment, colors, destination, drag, initial, page, picked, t } = props;
  const parties = PARTIES.filter((party) => destinationOf(assignment, party) === destination);
  let block: Block | undefined;
  if (destination !== "unassigned") {
    block = destination;
  }
  let blockTotal: number | null = null;
  let borderColor: string | undefined;
  if (block !== undefined) {
    blockTotal = total(groupsFor(assignment)[block], initial.latest.partyMeans);
    borderColor = colors[BLOCK_INDEX[block]];
  }
  return (
    <section
      className={classWhen("coalition-zone", drag?.over === destination, "drop-target")}
      data-destination={destination}
      style={{ borderColor }}
      aria-label={destinationName(destination, t)}
    >
      <div className="coalition-zone-title">
        <h4>{destinationName(destination, t)}</h4>
        {block !== undefined && <strong>{value(blockTotal, page, t)}</strong>}
        <button
          type="button"
          disabled={
            picked === null ||
            (picked !== null && destinationOf(assignment, picked) === destination)
          }
          onClick={() => {
            if (picked !== null) {
              props.onMove(picked, destination);
            }
          }}
        >
          {t("coalitionEditor.moveHere", { party: picked ?? "" })}
        </button>
      </div>
      <div className="coalition-tiles">
        {parties.map((party) => (
          <button
            type="button"
            className={classWhen(
              "coalition-tile",
              drag?.active === true && drag.party === party,
              "drag-source",
            )}
            data-party={party}
            key={party}
            aria-pressed={picked === party}
            aria-label={t("coalitionEditor.partyLabel", {
              party: page.labels[party] ?? party,
              destination: destinationName(destination, t),
            })}
            style={{ backgroundColor: tileColor(party) }}
            onClick={() => props.onSelect(party)}
            onPointerDown={(event) => props.onStart(event, party)}
            onPointerMove={props.onPointerMove}
            onPointerUp={props.onFinish}
            onPointerCancel={props.onCancel}
          >
            {party}
            <small>{value(initial.latest.partyMeans[party] ?? null, page, t)}</small>
          </button>
        ))}
        {parties.length === 0 && (
          <p className="coalition-empty-zone">{t("coalitionEditor.emptyZone")}</p>
        )}
      </div>
    </section>
  );
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
    if (finished.active && finished.over !== undefined) {
      onDrop(finished.party, finished.over);
    }
  };
  const cancel = (): void => {
    dragged.current = false;
    store(null);
  };
  return { cancel, drag, dragged, finish, move, start };
}

interface EditorBodyProps extends Props {
  readonly announcement: string;
  readonly drag: Drag | null;
  readonly onCancel: () => void;
  readonly onFinish: (event: ReactPointerEvent<HTMLButtonElement>) => void;
  readonly onEscape: () => void;
  readonly onMove: (party: Party, destination: Destination) => void;
  readonly onPointerMove: (event: ReactPointerEvent<HTMLButtonElement>) => void;
  readonly onSelect: (party: Party) => void;
  readonly onStart: (event: ReactPointerEvent<HTMLButtonElement>, party: Party) => void;
  readonly picked: Party | null;
}

function EditorBody(props: EditorBodyProps): JSX.Element {
  const { assignment, announcement, initial, onChange, page, t } = props;
  const groups = groupsFor(assignment);
  const colors = blockColors([groups.a, groups.b], initial.latest.partyMeans);
  return (
    <section
      className="coalition-editor"
      aria-labelledby="coalition-editor-title"
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          props.onEscape();
        }
      }}
    >
      <h3 id="coalition-editor-title">{t("coalitionEditor.title")}</h3>
      <p className="meta">{t("coalitionEditor.help")}</p>
      <div className="coalition-builder">
        <CoalitionBalance assignment={assignment} initial={initial} page={page} t={t} />
        <div className="coalition-zones">
          {DESTINATIONS.map((destination) => (
            <Zone {...props} colors={colors} destination={destination} key={destination} />
          ))}
        </div>
      </div>
      <p className="coalition-move-status" role="status" aria-live="polite">
        {announcement}
      </p>
      <div className="coalition-editor-actions">
        <button type="button" onClick={() => onChange(PRESET)}>
          {t("coalitionEditor.reset")}
        </button>
        <button type="button" onClick={() => onChange(emptyAssignment())}>
          {t("coalitionEditor.clear")}
        </button>
      </div>
    </section>
  );
}

function CoalitionEditor({ assignment, initial, onChange, page, t }: Props): JSX.Element {
  const [picked, setPicked] = useState<Party | null>(null);
  const [announcement, setAnnouncement] = useState("");

  function move(party: Party, destination: Destination): void {
    if (destinationOf(assignment, party) === destination) {
      setPicked(null);
      return;
    }
    setPicked(null);
    setAnnouncement(
      t("coalitionEditor.moved", { party, destination: destinationName(destination, t) }),
    );
    onChange(assign(assignment, party, destination));
    globalThis.setTimeout(
      () =>
        document
          .querySelector<HTMLElement>(`[data-party="${party}"]`)
          ?.focus({ preventScroll: true }),
      0,
    );
  }
  const pointer = usePointerDrag(move);

  function select(party: Party): void {
    if (pointer.dragged.current) {
      pointer.dragged.current = false;
      return;
    }
    if (picked === party) {
      setPicked(null);
      return;
    }
    setPicked(party);
  }

  return (
    <EditorBody
      {...{ assignment, initial, onChange, page, t }}
      announcement={announcement}
      drag={pointer.drag}
      onCancel={pointer.cancel}
      onEscape={() => {
        setPicked(null);
        pointer.cancel();
      }}
      onFinish={pointer.finish}
      onMove={move}
      onPointerMove={pointer.move}
      onSelect={select}
      onStart={pointer.start}
      picked={picked}
    />
  );
}

const BLOCK_INDEX: Readonly<Record<"a" | "b", number>> = { a: 0, b: 1 };

export { CoalitionEditor };

import { type JSX, type PointerEvent as ReactPointerEvent, useState } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { CoalitionBalance } from "./CoalitionBalance";
import {
  type Assignment,
  assign,
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
import { type Drag, usePointerDrag } from "./usePointerDrag";

const DESTINATIONS = ["a", "b", "unassigned"] as const;

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

interface InteractionProps {
  readonly drag: Drag | null;
  readonly onCancel: () => void;
  readonly onFinish: (event: ReactPointerEvent<HTMLButtonElement>) => void;
  readonly onMove: (party: Party, destination: Destination) => void;
  readonly onPointerMove: (event: ReactPointerEvent<HTMLButtonElement>) => void;
  readonly onSelect: (party: Party) => void;
  readonly onStart: (event: ReactPointerEvent<HTMLButtonElement>, party: Party) => void;
  readonly picked: Party | null;
}

interface ZoneProps extends Omit<Props, "onChange">, InteractionProps {
  readonly colors: readonly string[];
  readonly destination: Destination;
}

function classWhen(base: string, condition: boolean, addition: string): string {
  if (condition) {
    return `${base} ${addition}`;
  }
  return base;
}

function zoneDetails(
  assignment: Assignment,
  destination: Destination,
  shares: Readonly<Record<string, number | null>>,
  colors: readonly string[],
): { readonly blockTotal: number | null; readonly borderColor: string | undefined } {
  if (destination === "unassigned") {
    return { blockTotal: null, borderColor: undefined };
  }
  return {
    blockTotal: total(groupsFor(assignment)[destination], shares),
    borderColor: colors[BLOCK_INDEX[destination]],
  };
}

function movePicked(
  picked: Party | null,
  destination: Destination,
  onMove: (party: Party, destination: Destination) => void,
): void {
  if (picked !== null) {
    onMove(picked, destination);
  }
}

function blockTotalLabel(
  isBlock: boolean,
  blockTotal: number | null,
  page: Bootstrap,
  t: Translate,
): JSX.Element | null {
  if (!isBlock) {
    return null;
  }
  return <strong>{value(blockTotal, page, t)}</strong>;
}

function emptyZoneLabel(parties: readonly Party[], t: Translate): JSX.Element | null {
  if (parties.length > 0) {
    return null;
  }
  return <p className="coalition-empty-zone">{t("coalitionEditor.emptyZone")}</p>;
}

function moveDisabled(
  assignment: Assignment,
  picked: Party | null,
  destination: Destination,
): boolean {
  if (picked === null) {
    return true;
  }
  return destinationOf(assignment, picked) === destination;
}

function pickedName(picked: Party | null): string {
  return picked ?? "";
}

function partyName(page: Bootstrap, party: Party): string {
  return page.labels[party] ?? party;
}

function partyShare(shares: Readonly<Record<string, number | null>>, party: Party): number | null {
  return shares[party] ?? null;
}

function isDragSource(drag: Drag | null, party: Party): boolean {
  return drag?.active === true && drag.party === party;
}

interface PartyTileProps extends Omit<ZoneProps, "colors" | "destination"> {
  readonly destination: Destination;
  readonly party: Party;
}

function PartyTile(props: PartyTileProps): JSX.Element {
  const { destination, drag, initial, page, party, picked, t } = props;
  return (
    <button
      type="button"
      className={classWhen("coalition-tile", isDragSource(drag, party), "drag-source")}
      data-party={party}
      aria-pressed={picked === party}
      aria-label={t("coalitionEditor.partyLabel", {
        party: partyName(page, party),
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
      <small>{value(partyShare(initial.latest.partyMeans, party), page, t)}</small>
    </button>
  );
}

function Zone(props: ZoneProps): JSX.Element {
  const { assignment, colors, destination, drag, initial, page, picked, t } = props;
  const parties = PARTIES.filter((party) => destinationOf(assignment, party) === destination);
  const { blockTotal, borderColor } = zoneDetails(
    assignment,
    destination,
    initial.latest.partyMeans,
    colors,
  );
  const isBlock = destination !== "unassigned";
  return (
    <section
      className={classWhen("coalition-zone", drag?.over === destination, "drop-target")}
      data-destination={destination}
      style={{ borderColor }}
      aria-label={destinationName(destination, t)}
    >
      <div className="coalition-zone-title">
        <h4>{destinationName(destination, t)}</h4>
        {blockTotalLabel(isBlock, blockTotal, page, t)}
        <button
          type="button"
          disabled={moveDisabled(assignment, picked, destination)}
          onClick={() => movePicked(picked, destination, props.onMove)}
        >
          {t("coalitionEditor.moveHere", { party: pickedName(picked) })}
        </button>
      </div>
      <div className="coalition-tiles">
        {parties.map((party) => (
          <PartyTile {...props} destination={destination} key={party} party={party} />
        ))}
        {emptyZoneLabel(parties, t)}
      </div>
    </section>
  );
}

interface EditorBodyProps extends Props, InteractionProps {
  readonly announcement: string;
  readonly onEscape: () => void;
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

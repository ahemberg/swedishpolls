import type { JSX } from "react";
import type { RangeId, Translate } from "./bootstrap";
import { colour } from "./format";

/**
 * The timeline's controls: the offered ranges, the isolation select and the party toggles.
 *
 * They are written against the little each control needs rather than against a whole estimate
 * series, so the source chart drives the same three controls from its own windows and components.
 */

const ALL_PARTIES = "";

/** One offered window, however the page that offers it names the rest of its own range. */
interface RangeOption {
  readonly id: RangeId;
  readonly year: number | null;
}

/** One party a control can isolate or hide. */
interface Toggleable {
  readonly component: string;
}

function rangeLabel(t: Translate, range: RangeOption): string {
  if (range.year === null) {
    return t(`timeline.range.${range.id}`);
  }
  return t(`timeline.range.${range.id}`, { year: String(range.year) });
}

function Ranges({
  ranges,
  selected,
  onSelect,
  t,
}: {
  readonly ranges: readonly RangeOption[];
  readonly selected: string;
  readonly onSelect: (id: string) => void;
  readonly t: Translate;
}): JSX.Element {
  return (
    <fieldset className="chips">
      <legend>{t("timeline.range.label")}</legend>
      {ranges.map((range) => (
        <button
          key={range.id}
          type="button"
          className="chip"
          aria-pressed={range.id === selected}
          onClick={() => onSelect(range.id)}
        >
          {rangeLabel(t, range)}
        </button>
      ))}
    </fieldset>
  );
}

function Isolation({
  id,
  series,
  isolated,
  onIsolate,
  label,
  t,
}: {
  readonly id: string;
  readonly series: readonly Toggleable[];
  readonly isolated: string;
  readonly onIsolate: (component: string) => void;
  readonly label: (component: string) => string;
  readonly t: Translate;
}): JSX.Element {
  return (
    <p className="chips">
      <label htmlFor={id}>{t("timeline.isolate")}</label>
      <select id={id} value={isolated} onChange={(event) => onIsolate(event.target.value)}>
        <option value={ALL_PARTIES}>{t("timeline.isolateAll")}</option>
        {series.map((entry) => (
          <option key={entry.component} value={entry.component}>
            {label(entry.component)}
          </option>
        ))}
      </select>
    </p>
  );
}

function PartyToggles({
  series,
  hidden,
  locked,
  onToggle,
  label,
  t,
}: {
  readonly series: readonly Toggleable[];
  readonly hidden: readonly string[];
  readonly locked: boolean;
  readonly onToggle: (component: string) => void;
  readonly label: (component: string) => string;
  readonly t: Translate;
}): JSX.Element {
  return (
    <fieldset className="chips">
      <legend>{t("estimate.column.party")}</legend>
      {series.map((entry) => (
        <button
          key={entry.component}
          type="button"
          className="chip"
          disabled={locked}
          aria-pressed={!hidden.includes(entry.component)}
          onClick={() => onToggle(entry.component)}
        >
          <span className="swatch" style={{ background: colour(entry.component) }} />
          {label(entry.component)}
        </button>
      ))}
    </fieldset>
  );
}

export { ALL_PARTIES, Isolation, PartyToggles, Ranges };

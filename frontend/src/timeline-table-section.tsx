import type { JSX } from "react";
import { useState } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { TimelineTable } from "./TimelineTable";
import type { TimelineState } from "./useTimeline";

/** The chart's table alternative, folded away until a reader asks for it. */

interface Props {
  readonly id: string;
  readonly page: Bootstrap;
  readonly state: TimelineState;
  readonly label: (component: string) => string;
  readonly t: Translate;
}

function toggleLabel(open: boolean, t: Translate): string {
  if (open) {
    return t("timeline.hideTable");
  }
  return t("timeline.showTable");
}

function TimelineTableSection({ id, page, state, label, t }: Props): JSX.Element {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button
        type="button"
        className="btn"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen(!open)}
      >
        {toggleLabel(open, t)}
      </button>
      <div id={id} className="scroll" hidden={!open}>
        <TimelineTable page={page} dates={state.dates} drawn={state.drawn} label={label} t={t} />
      </div>
    </div>
  );
}

export { TimelineTableSection };

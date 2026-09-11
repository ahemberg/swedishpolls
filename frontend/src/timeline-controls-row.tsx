import type { JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { ALL_PARTIES, Isolation, PartyToggles, Ranges } from "./timeline-controls";
import type { TimelineState } from "./useTimeline";

/** The three controls above the chart, kept together so the chart itself stays readable. */

interface Props {
  readonly ids: string;
  readonly page: Bootstrap;
  readonly state: TimelineState;
  readonly label: (component: string) => string;
  readonly t: Translate;
  readonly fixed?: boolean;
}

function TimelineControlsRow({ ids, page, state, label, t, fixed = false }: Props): JSX.Element {
  return (
    <div>
      <Ranges
        ranges={page.ranges ?? []}
        selected={state.rangeId}
        t={t}
        onSelect={state.chooseRange}
      />
      {!fixed && (
        <>
          <Isolation
            id={`${ids}-isolate`}
            series={state.series}
            isolated={state.isolated}
            onIsolate={state.isolate}
            label={label}
            t={t}
          />
          <PartyToggles
            series={state.series}
            hidden={state.hidden}
            locked={state.isolated !== ALL_PARTIES}
            onToggle={state.toggle}
            label={label}
            t={t}
          />
        </>
      )}
    </div>
  );
}

export { TimelineControlsRow };

import type { JSX } from "react";
import { useId } from "react";
import type { Bootstrap, PartyObservation, Translate } from "./bootstrap";
import { level, shortDate } from "./format";
import { TimelineControlsRow } from "./timeline-controls-row";
import { TimelineFigure, TimelineReadout, TimelineScrubber } from "./timeline-figure";
import { TimelineNotes } from "./timeline-notes";
import { TimelineTableSection } from "./timeline-table-section";
import { useTimeline } from "./useTimeline";

/**
 * The timeline, with interval bands, direct end labels, election reference dots, fit boundaries,
 * one scrubber that a mouse, a finger and the arrow keys all drive, and the table alternative.
 */

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
  readonly component?: string;
  readonly observations?: readonly PartyObservation[];
}

function drawnNames(
  drawn: readonly { readonly component: string }[],
  component: string | undefined,
  naming: (component: string) => string,
): string {
  if (component !== undefined) {
    return naming(component);
  }
  return drawn.map((entry) => naming(entry.component)).join(", ");
}

function Timeline({ page, t, component, observations = [] }: Props): JSX.Element | null {
  const ids = useId();
  const state = useTimeline(page, component, observations);
  const { history, drawn, dates, index } = state;
  if (history === undefined) {
    return null;
  }
  const day = dates[index] ?? history.range.to;
  const naming = (component: string): string => page.labels[component] ?? component;
  const summary = t("timeline.summary", {
    from: shortDate(history.range.from, page.locale),
    to: shortDate(history.range.to, page.locale),
    parties: drawnNames(drawn, component, naming),
  });
  return (
    <section className="sec o-timeline" aria-labelledby={`${ids}-title`}>
      <h2 id={`${ids}-title`}>{t("timeline.title")}</h2>
      <TimelineControlsRow
        ids={ids}
        page={page}
        state={state}
        label={naming}
        t={t}
        fixed={component !== undefined}
      />
      <TimelineReadout
        page={page}
        drawn={drawn}
        index={index}
        day={day}
        t={t}
        coveragePeriod={history.coveragePeriodByDate[index] ?? null}
      />
      <TimelineFigure
        page={page}
        state={state}
        boundaries={history.boundaries}
        summary={summary}
        t={t}
        component={component}
        observations={observations}
      />
      <TimelineScrubber id={`${ids}-scrub`} state={state} day={day} locale={page.locale} t={t} />
      <TimelineNotes
        t={t}
        intervalLevel={level(history.intervalLevel)}
        step={String(history.range.step)}
        loading={state.loading}
        failed={state.failed}
        pollDots={observations.length > 0}
      />
      <TimelineTableSection id={`${ids}-table`} page={page} state={state} label={naming} t={t} />
    </section>
  );
}

export { Timeline };

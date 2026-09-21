import type { JSX } from "react";
import { useId } from "react";
import type { Bootstrap, PartyObservation, Translate } from "./bootstrap";
import { level, shortDate } from "./format";
import { componentName } from "./labels";
import { SourceCursor, SourceDetails } from "./SourceChart";
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

function SourceReading({
  ids,
  page,
  state,
  t,
}: Props & {
  readonly ids: string;
  readonly state: ReturnType<typeof useTimeline>;
}): JSX.Element | null {
  const { source, window } = state;
  const observation = source?.observations[source.cursor.index];
  if (source === null || window === undefined || observation === undefined) {
    return null;
  }
  return (
    <>
      <SourceDetails
        page={page}
        drawn={state.drawnComponents}
        window={window}
        observation={observation}
        t={t}
      />
      <SourceCursor
        id={`${ids}-source-cursor`}
        page={page}
        observations={source.observations}
        index={source.cursor.index}
        lastIndex={source.cursor.lastIndex}
        setCursor={source.cursor.setCursor}
        t={t}
      />
    </>
  );
}

function SourceNotes({
  state,
  t,
}: {
  readonly state: ReturnType<typeof useTimeline>;
  readonly t: Translate;
}): JSX.Element | null {
  if (state.source === null) {
    return null;
  }
  const notes = [
    t("source.chart.note"),
    t("source.chart.approximateNote"),
    t("source.chart.sourceNote"),
  ];
  if (state.source.loading) {
    notes.push(t("source.chart.loading"));
  }
  if (state.source.failed) {
    notes.push(t("source.chart.failed"));
  }
  return (
    <>
      {notes.map((note) => (
        <p className="footnote" key={note}>
          {note}
        </p>
      ))}
    </>
  );
}

function TimelineContent({
  ids,
  page,
  t,
  component,
  observations,
  state,
}: {
  readonly ids: string;
  readonly page: Bootstrap;
  readonly t: Translate;
  readonly component: string | undefined;
  readonly observations: readonly PartyObservation[];
  readonly state: ReturnType<typeof useTimeline> & {
    readonly history: NonNullable<ReturnType<typeof useTimeline>["history"]>;
  };
}): JSX.Element {
  const { history, drawn, dates, index } = state;
  const day = dates[index] ?? history.range.to;
  const naming = (name: string): string => componentName(page, name);
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
      <SourceReading ids={ids} page={page} state={state} t={t} />
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
        pollDots={state.source === null && observations.length > 0}
      />
      <SourceNotes state={state} t={t} />
      <TimelineTableSection id={`${ids}-table`} page={page} state={state} label={naming} t={t} />
    </section>
  );
}

function Timeline({ page, t, component, observations = [] }: Props): JSX.Element | null {
  const ids = useId();
  const state = useTimeline(page, component, observations);
  if (state.history === undefined) {
    return null;
  }
  return (
    <TimelineContent
      ids={ids}
      page={page}
      t={t}
      component={component}
      observations={observations}
      state={{ ...state, history: state.history }}
    />
  );
}

export { Timeline };

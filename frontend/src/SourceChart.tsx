import type { JSX } from "react";
import { useId } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { PLOT } from "./chart";
import { colour, count, decimal, percent, shortDate } from "./format";
import type { SourceChartData, SourceObservation } from "./source-chart";
import { markerSpan } from "./source-chart";
import { SourceChartFigure } from "./source-chart-figure";
import { ALL_PARTIES, Isolation, PartyToggles, Ranges } from "./timeline-controls";
import type { SourceChartState } from "./useSourceChart";
import { useSourceChart } from "./useSourceChart";

/**
 * The collected polls as a chart, drawn without an estimate behind it.
 *
 * Every mark is one reported share over the interview period the institute actually reported, on a
 * real date axis: nothing is snapped to a sampled estimate day, and nothing here needs a
 * publication to exist. The details name the full dates even when the chosen window clips the mark,
 * because the window is a view of the archive rather than a claim about the fieldwork.
 */

interface Props {
  readonly page: Bootstrap;
  readonly chart: SourceChartData;
  readonly t: Translate;
}

function partyName(page: Bootstrap, component: string): string {
  return page.labels[component] ?? component;
}

function fieldwork(observation: SourceObservation, locale: string): string {
  return `${shortDate(observation.from, locale)} – ${shortDate(observation.to, locale)}`;
}

/** A reported sample size, or the fact that the institute reported none. */
function sample(observation: SourceObservation, locale: string, t: Translate): string {
  if (observation.sampleSize === null) {
    return t("source.chart.noSample");
  }
  return t("source.chart.sample", { sample: count(observation.sampleSize, locale) });
}

/** Every detail one observation carries, in the order the readout and the label both say them. */
function Details({
  page,
  drawn,
  window,
  observation,
  t,
}: {
  readonly page: Bootstrap;
  readonly drawn: readonly string[];
  readonly window: SourceChartData["range"];
  readonly observation: SourceObservation;
  readonly t: Translate;
}): JSX.Element {
  const span = markerSpan(observation, window, PLOT);
  return (
    <p className="readout" aria-live="polite">
      <b>{observation.institute}</b>
      <span>{fieldwork(observation, page.locale)}</span>
      {observation.approximatePeriod && <span>{t("polls.approximate")}</span>}
      {(span.clippedFrom || span.clippedTo) && <span>{t("source.chart.clipped")}</span>}
      <span>{sample(observation, page.locale, t)}</span>
      {drawn.map((component) => {
        const share = observation.shares[component];
        if (share === undefined || share === null) {
          return null;
        }
        return (
          <span key={component}>
            <span className="swatch" style={{ background: colour(component) }} />
            {`${partyName(page, component)} ${percent(decimal(share, page.language), page.language)}`}
          </span>
        );
      })}
    </p>
  );
}

function Readout({
  page,
  state,
  t,
}: {
  readonly page: Bootstrap;
  readonly state: SourceChartState;
  readonly t: Translate;
}): JSX.Element {
  const observation = state.observations[state.index];
  if (observation === undefined) {
    return (
      <p className="readout" aria-live="polite">
        <span>{t("source.chart.empty")}</span>
      </p>
    );
  }
  return (
    <Details
      page={page}
      drawn={state.drawn}
      window={state.data.range}
      observation={observation}
      t={t}
    />
  );
}

/** The one control that steps the selection, so a keyboard and a finger reach the same details. */
function Cursor({
  id,
  page,
  observations,
  index,
  lastIndex,
  setCursor,
  t,
}: {
  readonly id: string;
  readonly page: Bootstrap;
  readonly observations: readonly SourceObservation[];
  readonly index: number;
  readonly lastIndex: number;
  readonly setCursor: (index: number) => void;
  readonly t: Translate;
}): JSX.Element | null {
  const observation = observations[index];
  if (observation === undefined) {
    return null;
  }
  return (
    <p>
      <label htmlFor={id}>{t("source.chart.cursor.label")}</label>
      <input
        id={id}
        type="range"
        min={0}
        max={lastIndex}
        step={1}
        value={index}
        aria-valuetext={`${observation.institute}, ${fieldwork(observation, page.locale)}`}
        onChange={(event) => setCursor(Number(event.target.value))}
      />
    </p>
  );
}

function SourceChart({ page, chart, t }: Props): JSX.Element {
  const ids = useId();
  const state = useSourceChart(chart);
  const summary = t("source.chart.summary", {
    from: shortDate(state.data.range.from, page.locale),
    to: shortDate(state.data.range.to, page.locale),
    parties: state.drawn.map((component) => partyName(page, component)).join(", "),
  });
  const components = state.data.components.map((component) => ({ component }));
  return (
    <section className="sec o-source-chart" aria-labelledby={`${ids}-title`}>
      <h2 id={`${ids}-title`}>{t("source.chart.title")}</h2>
      <Ranges
        ranges={state.data.ranges}
        selected={state.rangeId}
        onSelect={state.chooseRange}
        t={t}
      />
      <Isolation
        id={`${ids}-isolate`}
        series={components}
        isolated={state.isolated}
        onIsolate={state.isolate}
        label={(component) => partyName(page, component)}
        t={t}
      />
      <PartyToggles
        series={components}
        hidden={state.hidden}
        locked={state.isolated !== ALL_PARTIES}
        onToggle={state.toggle}
        label={(component) => partyName(page, component)}
        t={t}
      />
      <Readout page={page} state={state} t={t} />
      <SourceChartFigure page={page} state={state} summary={summary} t={t} />
      <Cursor
        id={`${ids}-cursor`}
        page={page}
        observations={state.observations}
        index={state.index}
        lastIndex={state.lastIndex}
        setCursor={state.setCursor}
        t={t}
      />
      <p className="footnote">{t("source.chart.note")}</p>
      <p className="footnote">{t("source.chart.approximateNote")}</p>
      <p className="footnote">{t("source.chart.sourceNote")}</p>
      {state.loading && <p className="footnote">{t("source.chart.loading")}</p>}
      {state.failed && <p className="footnote">{t("source.chart.failed")}</p>}
    </section>
  );
}

export { Cursor as SourceCursor, Details as SourceDetails, SourceChart };

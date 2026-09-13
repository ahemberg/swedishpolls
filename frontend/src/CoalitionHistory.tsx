import { type JSX, useEffect, useRef, useState } from "react";
import type { Bootstrap, Series, Translate } from "./bootstrap";
import {
  axisLevels,
  axisMaximum,
  BASELINE,
  bandPath,
  HEIGHT,
  LEFT,
  linePath,
  PLOT_WIDTH,
  TOP,
  WIDTH,
  yAt,
} from "./chart";
import { BLOCKS, blockColors, type CoalitionHistoryData, fitSegments } from "./coalition-history";
import { decimal, interval, percent, shortDate, timestamp } from "./format";
import { TimelineTable } from "./TimelineTable";
import { useCursor } from "./useCursor";
import { historyJson } from "./useHistory";

interface Props {
  readonly page: Bootstrap;
  readonly history: CoalitionHistoryData | undefined;
  readonly t: Translate;
}
const BAND_OPACITY = 0.16;
const LINE_WIDTH = 2;
const LABEL_OFFSET = 6;
const DASH = "7 4";
const SEPARATOR = ": ";
const MEMBERS_SEPARATOR = " · ";

function HistoryPlot({
  history,
  page,
  t,
  index,
}: {
  readonly history: CoalitionHistoryData;
  readonly page: Bootstrap;
  readonly t: Translate;
  readonly index: number;
}): JSX.Element {
  const drawn = BLOCKS.map((component) => ({ component, ...history.series[component] }));
  const colors = blockColors([history.selection.a, history.selection.b], history.latest.partyMeans);
  const maximum = axisMaximum(drawn);
  const first = Date.parse(history.requestedRange.from);
  const duration = Math.max(1, Date.parse(history.requestedRange.to) - first);
  const x = (index: number): number =>
    LEFT +
    (PLOT_WIDTH * (Date.parse(history.dates[index] ?? history.requestedRange.from) - first)) /
      duration;
  const y = (value: number): number => yAt(value, maximum);
  const segments = fitSegments(history);
  return (
    <svg
      className="coalition-plot"
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      role="img"
      aria-label={t("coalitionHistory.table")}
    >
      {axisLevels(maximum).map((value) => (
        <g key={value}>
          <line x1={LEFT} x2={LEFT + PLOT_WIDTH} y1={y(value)} y2={y(value)} stroke="#d5d5d5" />
          <text x={LEFT - LABEL_OFFSET} y={y(value)} textAnchor="end">
            {percent(String(value), page.language)}
          </text>
        </g>
      ))}
      {drawn.map((series, blockIndex) => (
        <g key={series.component} fill={colors[blockIndex]} stroke={colors[blockIndex]}>
          {segments.map((indices) => {
            const sliced: Series = {
              component: series.component,
              mean: indices.map((index) => series.mean[index] ?? null),
              lower: indices.map((index) => series.lower[index] ?? null),
              upper: indices.map((index) => series.upper[index] ?? null),
            };
            const at = (index: number): number => x(indices[index] ?? 0);
            let dash: string | undefined;
            if (series.component === "b") {
              dash = DASH;
            }
            return (
              <g key={indices[0]}>
                <path d={bandPath(sliced, at, y)} fillOpacity={BAND_OPACITY} stroke="none" />
                <path
                  d={linePath(sliced.mean, at, y)}
                  fill="none"
                  strokeWidth={LINE_WIDTH}
                  strokeDasharray={dash}
                />
                {indices.length === 1 && typeof sliced.mean[0] === "number" && (
                  <circle cx={at(0)} cy={y(sliced.mean[0])} r={LINE_WIDTH} />
                )}
              </g>
            );
          })}
          {typeof series.mean[index] === "number" && (
            <text
              x={x(index) + LABEL_OFFSET}
              y={y(series.mean[index] ?? 0) - LABEL_OFFSET}
              stroke="none"
            >
              {series.component.toUpperCase()}
            </text>
          )}
        </g>
      ))}
      {history.dates[index] !== undefined && (
        <line x1={x(index)} x2={x(index)} y1={TOP} y2={BASELINE} stroke="#555" />
      )}
    </svg>
  );
}

function HistoryChart({
  history,
  page,
  t,
}: {
  readonly history: CoalitionHistoryData;
  readonly page: Bootstrap;
  readonly t: Translate;
}): JSX.Element {
  const cursor = useCursor(history.dates.length);
  const drawn = BLOCKS.map((component) => ({ component, ...history.series[component] }));
  const date = history.dates[cursor.index];
  return (
    <>
      <HistoryPlot history={history} page={page} t={t} index={cursor.index} />
      <div className="coalition-axis">
        <span>{shortDate(history.requestedRange.from, page.locale)}</span>
        <span>{shortDate(history.requestedRange.to, page.locale)}</span>
      </div>
      {date === undefined && <p>{t("coalitionHistory.empty")}</p>}
      <label>
        {t("coalitionHistory.cursor")}
        <input
          type="range"
          min={0}
          max={cursor.lastIndex}
          value={cursor.index}
          disabled={history.dates.length === 0}
          aria-valuetext={date}
          onChange={(event) => cursor.setCursor(Number(event.currentTarget.value))}
        />
      </label>
      <div aria-live="polite" aria-atomic="true">
        {date !== undefined && <h3>{shortDate(date, page.locale)}</h3>}
        {drawn.map((series) => (
          <p key={series.component}>
            {t(`coalitionHistory.${series.component}`)}
            {SEPARATOR}
            {interval(
              [series.mean[cursor.index], series.lower[cursor.index], series.upper[cursor.index]],
              page.language,
              t,
            )}
          </p>
        ))}
      </div>
      <details>
        <summary>{t("coalitionHistory.table")}</summary>
        <div className="scroll">
          <TimelineTable
            page={page}
            dates={history.dates}
            drawn={drawn}
            label={(component) => t(`coalitionHistory.${component}`)}
            t={t}
            intervals={true}
          />
        </div>
      </details>
    </>
  );
}

function LatestHistory({
  initial,
  page,
  t,
}: {
  readonly initial: CoalitionHistoryData;
  readonly page: Bootstrap;
  readonly t: Translate;
}): JSX.Element {
  return (
    <>
      <p>{t("coalitionHistory.note")}</p>
      <p>
        {t("coalitionHistory.published", { date: timestamp(initial.publishedAt, page.locale) })}
      </p>
      <h3>{t("coalitionHistory.latest", { date: shortDate(initial.latest.date, page.locale) })}</h3>
      {BLOCKS.map((block) => (
        <p key={block}>
          <strong>{t(`coalitionHistory.${block}`)}</strong>
          {SEPARATOR}
          {initial.selection[block].join(" + ")}
          {MEMBERS_SEPARATOR}
          {interval(
            [initial.latest[block].mean, initial.latest[block].lower, initial.latest[block].upper],
            page.language,
            t,
          )}
        </p>
      ))}
      <dl>
        {(
          [
            ["unassigned", initial.latest.unassignedMean],
            ["remainder", initial.latest.comparableRemainderMean],
            ["outside", initial.latest.outsideBothMean],
          ] as const
        ).map(([label, value]) => (
          <div key={label}>
            <dt>{t(`coalitionHistory.${label}`)}</dt>
            <dd>
              {typeof value === "number" && percent(decimal(value, page.language), page.language)}
              {value === null && t("estimate.unavailable")}
            </dd>
          </div>
        ))}
      </dl>
    </>
  );
}

function useHistoryRange(initial: CoalitionHistoryData): {
  readonly history: CoalitionHistoryData;
  readonly loading: boolean;
  readonly failed: boolean;
  readonly load: (form: HTMLFormElement) => Promise<void>;
} {
  const [history, setHistory] = useState(initial);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const pending = useRef<AbortController | null>(null);
  useEffect(
    () => (): void => {
      pending.current?.abort();
    },
    [],
  );
  async function load(form: HTMLFormElement): Promise<void> {
    pending.current?.abort();
    const request = new AbortController();
    pending.current = request;
    const fields = new FormData(form);
    const parameters = new URLSearchParams({
      a: initial.selection.a.join(","),
      b: initial.selection.b.join(","),
      from: String(fields.get("from")),
      to: String(fields.get("to")),
      step: String(initial.requestedRange.step),
    });
    setLoading(true);
    setFailed(false);
    let next: CoalitionHistoryData | undefined;
    try {
      next = await historyJson<CoalitionHistoryData>(
        `/api/v1/publications/${encodeURIComponent(initial.publicationId)}/coalition-history?${parameters}`,
        request.signal,
      );
    } catch {
      next = undefined;
    }
    if (request.signal.aborted) {
      return;
    }
    setLoading(false);
    setFailed(next === undefined);
    if (next !== undefined) {
      setHistory(next);
    }
  }
  return { history, loading, failed, load };
}

function PublishedHistory({
  initial,
  page,
  t,
}: {
  readonly initial: CoalitionHistoryData;
  readonly page: Bootstrap;
  readonly t: Translate;
}): JSX.Element {
  const { history, loading, failed, load } = useHistoryRange(initial);
  return (
    <>
      <LatestHistory initial={initial} page={page} t={t} />
      <form
        className="coalition-range"
        onSubmit={(event) => {
          event.preventDefault();
          load(event.currentTarget);
        }}
      >
        <label>
          {t("coalitionHistory.from")}
          <input
            type="date"
            name="from"
            required={true}
            min="0001-01-01"
            max="9999-12-31"
            defaultValue={initial.requestedRange.from}
          />
        </label>
        <label>
          {t("coalitionHistory.to")}
          <input
            type="date"
            name="to"
            required={true}
            min="0001-01-01"
            max="9999-12-31"
            defaultValue={initial.requestedRange.to}
          />
        </label>
        <button type="submit">{t("coalitionHistory.apply")}</button>
      </form>
      <p role="status">
        {loading && t("coalitionHistory.loading")}
        {failed && t("coalitionHistory.error")}
      </p>
      <div aria-busy={loading}>
        <HistoryChart
          key={`${history.requestedRange.from}/${history.requestedRange.to}`}
          history={history}
          page={page}
          t={t}
        />
      </div>
    </>
  );
}

function CoalitionHistory({ page, history, t }: Props): JSX.Element {
  return (
    <section className="sec coalition-history-section">
      <h2>{t("coalitionHistory.title")}</h2>
      {history === undefined && <p>{t("coalitionHistory.unavailable")}</p>}
      {history !== undefined && (
        <PublishedHistory key={history.publicationId} initial={history} page={page} t={t} />
      )}
    </section>
  );
}

export { CoalitionHistory };

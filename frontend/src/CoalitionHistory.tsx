import { type JSX, useEffect, useRef, useState } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { CoalitionEditor } from "./CoalitionEditor";
import { CoalitionHistoryPlot } from "./CoalitionHistoryPlot";

import {
  type Assignment,
  assignmentFromSelection,
  BLOCKS,
  type CoalitionHistoryData,
  type CoalitionLinkError,
  coalitionSharePath,
  groupsFor,
} from "./coalition-history";
import { decimal, interval, percent, shortDate, timestamp } from "./format";
import { TimelineTable } from "./TimelineTable";
import { useCursor } from "./useCursor";
import { fetchJson } from "./useFetched";

interface Props {
  readonly page: Bootstrap;
  readonly history: CoalitionHistoryData | undefined;
  readonly error: CoalitionLinkError | undefined;
  readonly t: Translate;
}
const SEPARATOR = ": ";
const MEMBERS_SEPARATOR = " · ";

function HistoryBreaks({
  history,
  page,
  t,
}: {
  readonly history: CoalitionHistoryData;
  readonly page: Bootstrap;
  readonly t: Translate;
}): JSX.Element {
  return (
    <details>
      <summary>{t("coalitionHistory.breaks")}</summary>
      <p>{t("timeline.gap")}</p>
      <p>{t("method.coverage.boundaryNote")}</p>
      <ul>
        {history.gaps.map((gap) => (
          <li key={gap.from}>
            {shortDate(gap.from, page.locale)}
            {MEMBERS_SEPARATOR}
            {shortDate(gap.to, page.locale)}
          </li>
        ))}
      </ul>
      <ul>
        {history.fitBoundaries.map((boundary) => (
          <li key={boundary.date}>
            {t("coalitionHistory.fit", { date: shortDate(boundary.date, page.locale) })}
          </li>
        ))}
      </ul>
    </details>
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
      <CoalitionHistoryPlot history={history} page={page} t={t} index={cursor.index} />
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
            {history.selection[series.component].length === 0 && t("coalitionEditor.assignParty")}
            {history.selection[series.component].length > 0 &&
              interval(
                [series.mean[cursor.index], series.lower[cursor.index], series.upper[cursor.index]],
                page.language,
                t,
              )}
          </p>
        ))}
      </div>
      <HistoryBreaks history={history} page={page} t={t} />
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
  history,
  page,
  t,
}: {
  readonly history: CoalitionHistoryData;
  readonly page: Bootstrap;
  readonly t: Translate;
}): JSX.Element {
  return (
    <>
      <p>{t("coalitionHistory.note")}</p>
      <p>
        {t("coalitionHistory.published", { date: timestamp(history.publishedAt, page.locale) })}
      </p>
      <h3>{t("coalitionHistory.latest", { date: shortDate(history.latest.date, page.locale) })}</h3>
      {BLOCKS.map((block) => (
        <p key={block}>
          <strong>{t(`coalitionHistory.${block}`)}</strong>
          {SEPARATOR}
          {history.selection[block].join(" + ") || t("coalitionEditor.noParties")}
          {MEMBERS_SEPARATOR}
          {history.selection[block].length === 0 && t("coalitionEditor.assignParty")}
          {history.selection[block].length > 0 &&
            interval(
              [
                history.latest[block].mean,
                history.latest[block].lower,
                history.latest[block].upper,
              ],
              page.language,
              t,
            )}
        </p>
      ))}
      <dl>
        {(
          [
            ["unassigned", history.latest.unassignedMean],
            ["remainder", history.latest.comparableRemainderMean],
            ["outside", history.latest.outsideBothMean],
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
  readonly requestedRange: CoalitionHistoryData["requestedRange"];
  readonly load: (
    selection: CoalitionHistoryData["selection"],
    range: CoalitionHistoryData["requestedRange"],
  ) => Promise<void>;
  readonly retry: () => Promise<void>;
} {
  const [history, setHistory] = useState(initial);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [requestedRange, setRequestedRange] = useState(initial.requestedRange);
  const pending = useRef<AbortController | null>(null);
  const lastRequest = useRef({ selection: initial.selection, range: initial.requestedRange });
  useEffect(
    () => (): void => {
      pending.current?.abort();
    },
    [],
  );
  async function load(
    selection: CoalitionHistoryData["selection"],
    range: CoalitionHistoryData["requestedRange"],
  ): Promise<void> {
    pending.current?.abort();
    const request = new AbortController();
    pending.current = request;
    lastRequest.current = { selection, range };
    setRequestedRange(range);
    const parameters = new URLSearchParams({
      a: selection.a.join(","),
      b: selection.b.join(","),
      from: range.from,
      to: range.to,
      step: String(range.step),
    });
    setLoading(true);
    setFailed(false);
    let next: CoalitionHistoryData | undefined;
    try {
      next = await fetchJson<CoalitionHistoryData>(
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
  return {
    history,
    loading,
    failed,
    requestedRange,
    load,
    retry: () => load(lastRequest.current.selection, lastRequest.current.range),
  };
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
  const { history, loading, failed, requestedRange, load, retry } = useHistoryRange(initial);
  const [assignment, setAssignment] = useState(() => assignmentFromSelection(initial.selection));

  function changeAssignment(next: Assignment): void {
    setAssignment(next);
    load(groupsFor(next), history.requestedRange);
  }

  return (
    <>
      <CoalitionEditor
        assignment={assignment}
        initial={initial}
        onChange={changeAssignment}
        page={page}
        t={t}
      />
      <LatestHistory history={history} page={page} t={t} />
      <form
        className="coalition-range"
        onSubmit={(event) => {
          event.preventDefault();
          const fields = new FormData(event.currentTarget);
          load(groupsFor(assignment), {
            from: String(fields.get("from")),
            to: String(fields.get("to")),
            step: history.requestedRange.step,
          });
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
        {failed && (
          <>
            {t("coalitionHistory.error")}{" "}
            <button type="button" onClick={retry}>
              {t("coalitionHistory.retry")}
            </button>
          </>
        )}
      </p>
      <p>
        <a href={coalitionSharePath(page.route.path, groupsFor(assignment), requestedRange)}>
          {t("coalitionHistory.share")}
        </a>
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

function CoalitionHistory({ page, history, error, t }: Props): JSX.Element {
  if (error !== undefined) {
    return (
      <section className="sec coalition-history-section">
        <h2>{t("coalitionHistory.title")}</h2>
        <div role="alert">
          <p>
            {t("coalitionHistory.invalid")} {error.reason}
          </p>
          <a href={error.reset}>{t("coalitionHistory.reset")}</a>
        </div>
      </section>
    );
  }
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

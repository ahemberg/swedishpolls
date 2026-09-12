import { type JSX, useCallback, useState } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { PollFilters } from "./poll-filters";
import { PollRows } from "./poll-rows";
import type { PollFilterState, PollTable } from "./poll-table";
import { outsideRoster, pollQuery } from "./poll-table";
import { usePolls } from "./usePolls";

/**
 * The browsable poll table.
 *
 * These are archived source observations, not the model's estimates, and the page says so once
 * rather than repeating it beside every number. Filtering, paging and the download all name the
 * publication the page resolved, so the file a reader saves holds the rows they were looking at
 * even if a corrected snapshot publishes while they are choosing.
 */

interface Props {
  readonly page: Bootstrap;
  readonly table: PollTable;
  readonly t: Translate;
}

/** Whether any cell on this page carries the marker, so the footnote appears only where it is. */
function marked(table: PollTable): boolean {
  return table.polls.some((poll) =>
    table.columns.some((component) => outsideRoster(poll, table.options, component)),
  );
}

/** The address bar follows the filter, so what a reader shares is what they were looking at. */
function remember(page: Bootstrap, filters: PollFilterState, pageNumber: number): void {
  const parameters = pollQuery(filters, pageNumber);
  if (page.permanent === true && page.api !== undefined) {
    parameters.set("publication", page.api.publication);
  }
  const query = parameters.toString();
  if (query === "") {
    globalThis.history.replaceState(null, "", page.route.path);
    return;
  }
  globalThis.history.replaceState(null, "", `${page.route.path}?${query}`);
}

function Notice({
  table,
  failed,
  t,
}: {
  readonly table: PollTable;
  readonly failed: boolean;
  readonly t: Translate;
}): JSX.Element | null {
  if (failed) {
    return (
      <p className="notice" role="alert">
        {t("polls.failed")}
      </p>
    );
  }
  if (table.invalid.length > 0) {
    return (
      <p className="notice" role="alert">
        {t("polls.invalid", { names: table.invalid.map((entry) => entry.name).join(", ") })}
      </p>
    );
  }
  if (table.polls.length === 0) {
    return (
      <p className="notice" role="status">
        {t("polls.empty")}
      </p>
    );
  }
  return null;
}

function Paging({
  table,
  onPage,
  t,
}: {
  readonly table: PollTable;
  readonly onPage: (page: number) => void;
  readonly t: Translate;
}): JSX.Element | null {
  if (table.pages <= 1) {
    return null;
  }
  return (
    <nav className="paging" aria-label={t("head.title.polls")}>
      <button
        type="button"
        className="btn"
        disabled={table.page <= 1}
        onClick={() => onPage(table.page - 1)}
      >
        {t("polls.previous")}
      </button>
      <span className="meta">
        {t("polls.pageOf", { page: String(table.page), pages: String(table.pages) })}
      </span>
      <button
        type="button"
        className="btn"
        disabled={table.page >= table.pages}
        onClick={() => onPage(table.page + 1)}
      >
        {t("polls.next")}
      </button>
    </nav>
  );
}

function Download({
  page,
  table,
  t,
}: {
  readonly page: Bootstrap;
  readonly table: PollTable;
  readonly t: Translate;
}): JSX.Element | null {
  const { api } = page;
  if (api === undefined) {
    return null;
  }
  return (
    <section className="sec o-journalists">
      <h2>{t("downloads.title")}</h2>
      <ul className="downloads">
        <li>
          <a className="btn" href={`${table.csv}&language=${api.language}`}>
            {t("polls.download")}
          </a>
        </li>
        <li>
          <a
            className="btn"
            href={`${api.base}/estimates/latest?publication=${api.publication}&language=${api.language}`}
          >
            {t("downloads.estimates")}
          </a>
        </li>
      </ul>
      <p className="footnote">{t("polls.downloadNote")}</p>
      <p className="meta">{t("downloads.pinned", { publication: api.publication })}</p>
    </section>
  );
}

function PollsPage({ page, table: initial, t }: Props): JSX.Element {
  const [filters, setFilters] = useState<PollFilterState>(initial.filters);
  const [pageNumber, setPageNumber] = useState(initial.page);
  const { table, loading, failed } = usePolls(page, initial, filters, pageNumber);

  const refilter = useCallback(
    (chosen: PollFilterState) => {
      setFilters(chosen);
      setPageNumber(1);
      remember(page, chosen, 1);
    },
    [page],
  );
  const repage = useCallback(
    (chosen: number) => {
      setPageNumber(chosen);
      remember(page, filters, chosen);
    },
    [page, filters],
  );

  return (
    <div>
      <h1>{t("head.title.polls")}</h1>
      <p className="meta">{t("polls.lead")}</p>
      <PollFilters
        page={page}
        options={table.options}
        filters={filters}
        onChange={refilter}
        t={t}
      />
      <Notice table={table} failed={failed} t={t} />
      {loading && (
        <p className="meta" role="status">
          {t("polls.loading")}
        </p>
      )}
      {table.polls.length > 0 && (
        <>
          <PollRows page={page} table={table} t={t} />
          <p className="footnote">{t("polls.sourceNote")}</p>
          {marked(table) && <p className="footnote">{t("polls.unmodelled")}</p>}
          <Paging table={table} onPage={repage} t={t} />
        </>
      )}
      <Download page={page} table={table} t={t} />
    </div>
  );
}

export { PollsPage };

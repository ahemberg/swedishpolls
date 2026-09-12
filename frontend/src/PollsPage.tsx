import type { JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { PollFilters } from "./poll-filters";
import { PollRows } from "./poll-rows";
import type { PollTable } from "./poll-table";
import { pollQuery } from "./poll-table";

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
  return table.polls.some((poll) => poll.unmodeled.length > 0);
}

/**
 * The two notices render independently, the way the no-script markup renders them: a rejected
 * filter and an empty result can both be true at once, and each says its own thing.
 */
function InvalidNotice({
  table,
  t,
}: {
  readonly table: PollTable;
  readonly t: Translate;
}): JSX.Element | null {
  if (table.invalid.length === 0) {
    return null;
  }
  return (
    <p className="notice" role="alert">
      {t("polls.invalid", { names: table.invalid.map((entry) => entry.name).join(", ") })}
    </p>
  );
}

function EmptyNotice({
  table,
  t,
}: {
  readonly table: PollTable;
  readonly t: Translate;
}): JSX.Element | null {
  if (table.polls.length > 0) {
    return null;
  }
  return (
    <p className="notice" role="status">
      {t("polls.empty")}
    </p>
  );
}

function Paging({
  page,
  table,
  t,
}: {
  readonly page: Bootstrap;
  readonly table: PollTable;
  readonly t: Translate;
}): JSX.Element | null {
  if (table.pages <= 1) {
    return null;
  }
  const link = (pageNumber: number): string => {
    const parameters = new URLSearchParams();
    if (page.api !== undefined) {
      parameters.set("publication", page.api.publication);
    }
    for (const [name, value] of pollQuery(table.filters, pageNumber)) {
      parameters.set(name, value);
    }
    return `${page.route.path}?${parameters.toString()}`;
  };
  return (
    <nav className="paging" aria-label={t("head.title.polls")}>
      {table.page > 1 && (
        <a className="btn" href={link(table.page - 1)}>
          {t("polls.previous")}
        </a>
      )}
      <span className="meta">
        {t("polls.pageOf", { page: String(table.page), pages: String(table.pages) })}
      </span>
      {table.page < table.pages && (
        <a className="btn" href={link(table.page + 1)}>
          {t("polls.next")}
        </a>
      )}
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

function PollsPage({ page, table, t }: Props): JSX.Element {
  return (
    <div>
      <h1>{t("head.title.polls")}</h1>
      <p className="meta">{t("polls.lead")}</p>
      <PollFilters page={page} options={table.options} filters={table.filters} t={t} />
      <InvalidNotice table={table} t={t} />
      <EmptyNotice table={table} t={t} />
      {table.polls.length > 0 && (
        <>
          <PollRows page={page} table={table} t={t} />
          <p className="footnote">{t("polls.sourceNote")}</p>
          {marked(table) && <p className="footnote">{t("polls.unmodeled")}</p>}
          <Paging page={page} table={table} t={t} />
        </>
      )}
      <Download page={page} table={table} t={t} />
    </div>
  );
}

export { PollsPage };

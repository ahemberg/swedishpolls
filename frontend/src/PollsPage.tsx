import type { JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { timestamp } from "./format";
import { PollFilters } from "./poll-filters";
import { PollRows } from "./poll-rows";
import type { PollTable } from "./poll-table";
import { pollQuery } from "./poll-table";
import { SourceChart } from "./SourceChart";

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

function pollText(source: boolean, published: string, current: string): string {
  if (source) {
    return current;
  }
  return published;
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
      {t(pollText(table.source, "polls.empty", "source.polls.empty"))}
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
    } else if (page.source !== undefined) {
      parameters.set("snapshot", String(page.source.snapshotId));
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
  const { api, source } = page;
  if (api === undefined && source === undefined) {
    return null;
  }
  return (
    <section className="sec o-journalists">
      <h2>{t("downloads.title")}</h2>
      <ul className="downloads">
        <li>
          <a className="btn" href={`${table.csv}&language=${page.language}`}>
            {t("polls.download")}
          </a>
        </li>
        <EstimateDownload page={page} t={t} />
      </ul>
      <p className="footnote">
        {t(pollText(source !== undefined, "polls.downloadNote", "source.polls.downloadNote"))}
      </p>
      <DownloadPin page={page} t={t} />
    </section>
  );
}

function EstimateDownload({ page, t }: Pick<Props, "page" | "t">): JSX.Element | null {
  const { api } = page;
  if (api === undefined) {
    return null;
  }
  return (
    <li>
      <a
        className="btn"
        href={`${api.base}/estimates/latest?publication=${api.publication}&language=${api.language}`}
      >
        {t("downloads.estimates")}
      </a>
    </li>
  );
}

function DownloadPin({ page, t }: Pick<Props, "page" | "t">): JSX.Element | null {
  if (page.api !== undefined) {
    return <p className="meta">{t("downloads.pinned", { publication: page.api.publication })}</p>;
  }
  if (page.source !== undefined) {
    return (
      <p className="meta">
        {t("source.polls.snapshot", { snapshot: String(page.source.snapshotId) })}
      </p>
    );
  }
  return null;
}

/** The source chart, on the pages whose rows come from a retained snapshot rather than a run. */
function Chart({ page, t }: Pick<Props, "page" | "t">): JSX.Element | null {
  const { sourceChart } = page;
  if (sourceChart === undefined) {
    return null;
  }
  return <SourceChart page={page} chart={sourceChart} t={t} />;
}

function PollsPage({ page, table, t }: Props): JSX.Element {
  return (
    <div>
      <h1>{t(pollText(table.source, "head.title.polls", "source.polls.title"))}</h1>
      <p className="meta">{t(pollText(table.source, "polls.lead", "source.polls.lead"))}</p>
      {page.source !== undefined && (
        <p className="meta">
          {t("source.updated", {
            timestamp: timestamp(page.source.capturedAt, page.locale),
          })}
        </p>
      )}
      <PollFilters page={page} options={table.options} filters={table.filters} t={t} />
      <Chart page={page} t={t} />
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

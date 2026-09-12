import type { JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { count, date } from "./format";
import type { PollRow, PollTable } from "./poll-table";

/**
 * The rows of the poll table.
 *
 * A share is written with the digits the source published: rounding one to the estimate's display
 * precision would print a number no pollster ever reported. A share the source left out reads as
 * missing, and a reported party outside its period's modeled roster is marked as the observation
 * it is rather than passed off as an estimate.
 */

const SWEDISH = "sv";

interface Props {
  readonly page: Bootstrap;
  readonly table: PollTable;
  readonly t: Translate;
}

/** The source's own decimals, with the language's separator and nothing else changed. */
function source(value: string | null | undefined, page: Bootstrap, t: Translate): string {
  if (value === undefined || value === null) {
    return t("polls.missing");
  }
  if (page.language === SWEDISH) {
    return String(value).replace(".", ",");
  }
  return value;
}

/** A sample size, grouped the way the language groups thousands, exactly as the server writes it. */
function sample(poll: PollRow, page: Bootstrap, t: Translate): string {
  if (poll.sampleSize === null) {
    return t("polls.missing");
  }
  return count(poll.sampleSize, page.locale);
}

function sourceIdentity(poll: PollRow, t: Translate): string {
  if (poll.institute === null) {
    return t("polls.missing");
  }
  if (poll.company === null || poll.company === poll.institute) {
    return poll.institute;
  }
  return `${poll.institute} (${poll.company})`;
}

function method(poll: PollRow, t: Translate): string {
  const parts = [poll.methodEra, poll.surveyType].filter(
    (value): value is string => value !== null && value.trim() !== "",
  );
  if (parts.length === 0) {
    return t("polls.missing");
  }
  return parts.join(", ");
}

function methodCell(poll: PollRow, t: Translate): JSX.Element | string {
  const label = method(poll, t);
  if (poll.methodEvidence === null || poll.methodEvidence === "") {
    return label;
  }
  return <a href={poll.methodEvidence}>{label}</a>;
}

function sampleCell(poll: PollRow, page: Bootstrap, t: Translate): JSX.Element | string {
  const label = sample(poll, page, t);
  if (poll.denominatorNote === null || poll.denominatorNote === "") {
    return label;
  }
  return <abbr title={poll.denominatorNote}>{label}</abbr>;
}

function published(poll: PollRow, page: Bootstrap, t: Translate): string {
  if (poll.publicationDate === null) {
    return t("polls.missing");
  }
  return date(poll.publicationDate, page.locale);
}

function fieldwork(poll: PollRow, page: Bootstrap, t: Translate): string {
  const from = poll.collectionFrom;
  const to = poll.collectionTo;
  if (from === null && to === null) {
    return t("polls.missing");
  }
  const span = [from, to]
    .filter((day): day is string => day !== null)
    .map((day) => date(day, page.locale));
  const written = [...new Set(span)].join(" - ");
  if (poll.approximatePeriod) {
    return `${written} (${t("polls.approximate")})`;
  }
  return written;
}

function eligibility(poll: PollRow, t: Translate): string {
  if (poll.eligible) {
    return t("polls.eligible");
  }
  return t("polls.excluded", { reasons: poll.exclusionReasons.join(", ") });
}

function caption(table: PollTable, t: Translate): string {
  const first = (table.page - 1) * table.pageSize + 1;
  return t("polls.tableCaption", {
    first: String(first),
    last: String(first + table.polls.length - 1),
    total: String(table.total),
    page: String(table.page),
    pages: String(table.pages),
  });
}

function Share({
  poll,
  component,
  page,
  t,
}: {
  readonly poll: PollRow;
  readonly component: string;
  readonly page: Bootstrap;
  readonly t: Translate;
}): JSX.Element {
  const flagged = poll.unmodeled.includes(component);
  return (
    <td className="num">
      {source(poll.displayShares[component], page, t)}
      {flagged && <abbr title={t("polls.unmodeled")}>{t("polls.unmodeledMark")}</abbr>}
    </td>
  );
}

function PollRows({ page, table, t }: Props): JSX.Element {
  const eligible = table.filters.includeExcluded;
  return (
    <div className="scroll">
      <table className="polls">
        <caption>{caption(table, t)}</caption>
        <thead>
          <tr>
            <th scope="col">{t("polls.column.institute")}</th>
            <th scope="col">{t("polls.column.method")}</th>
            <th scope="col">{t("polls.column.fieldwork")}</th>
            <th scope="col">{t("polls.column.published")}</th>
            <th scope="col" className="num">
              {t("polls.column.sample")}
            </th>
            {table.columns.map((component) => (
              <th scope="col" className="num" key={component} title={table.labels[component]}>
                {component}
              </th>
            ))}
            <th scope="col" className="num">
              {t("polls.column.other")}
            </th>
            <th scope="col">{t("polls.column.period")}</th>
            {eligible && <th scope="col">{t("polls.column.eligibility")}</th>}
          </tr>
        </thead>
        <tbody>
          {table.polls.map((poll) => (
            <tr key={poll.pollId}>
              <th scope="row">{sourceIdentity(poll, t)}</th>
              <td>{methodCell(poll, t)}</td>
              <td>{fieldwork(poll, page, t)}</td>
              <td>{published(poll, page, t)}</td>
              <td className="num">{sampleCell(poll, page, t)}</td>
              {table.columns.map((component) => (
                <Share key={component} poll={poll} component={component} page={page} t={t} />
              ))}
              <td className="num">{source(poll.displayOther, page, t)}</td>
              <td>{poll.coveragePeriod ?? t("polls.noPeriod")}</td>
              {eligible && <td>{eligibility(poll, t)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export { PollRows };

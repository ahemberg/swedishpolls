import type { JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { componentName } from "./labels";
import type { PollFilterState, PollOptions } from "./poll-table";

/**
 * The poll table's controls.
 *
 * Every control is a labelled native element rather than a styled div, so the keyboard order, the
 * focus ring and the screen-reader name come from the platform. The native GET form also keeps the
 * controls functional before and after React mounts.
 */

interface Props {
  readonly page: Bootstrap;
  readonly options: PollOptions;
  readonly filters: PollFilterState;
  readonly t: Translate;
}

function DateFilter({
  name,
  label,
  value,
}: {
  readonly name: string;
  readonly label: string;
  readonly value: string | null;
}): JSX.Element {
  return (
    <p>
      <label htmlFor={`polls-${name}`}>{label}</label>{" "}
      <input type="date" id={`polls-${name}`} name={name} defaultValue={value ?? ""} />
    </p>
  );
}

function Choice({
  name,
  label,
  any,
  value,
  values,
  display,
}: {
  readonly name: string;
  readonly label: string;
  readonly any: string;
  readonly value: string;
  readonly values: readonly string[];
  readonly display: (value: string) => string;
}): JSX.Element {
  return (
    <p>
      <label htmlFor={`polls-${name}`}>{label}</label>{" "}
      <select id={`polls-${name}`} name={name} defaultValue={value}>
        <option value="">{any}</option>
        {values.map((entry) => (
          <option key={entry} value={entry}>
            {display(entry)}
          </option>
        ))}
      </select>
    </p>
  );
}

function clearPath(page: Bootstrap): string {
  if (page.api !== undefined) {
    return `${page.route.path}?publication=${encodeURIComponent(page.api.publication)}`;
  }
  return page.route.path;
}

function publicationPin(page: Bootstrap): JSX.Element | null {
  if (page.api === undefined) {
    return null;
  }
  return <input type="hidden" name="publication" value={page.api.publication} />;
}

function PollFilters({ page, options, filters, t }: Props): JSX.Element {
  return (
    <form className="filters" method="get" action={page.route.path}>
      {publicationPin(page)}
      <fieldset>
        <legend>{t("polls.filters")}</legend>
        <DateFilter name="from" label={t("polls.filter.from")} value={filters.from} />
        <DateFilter name="to" label={t("polls.filter.to")} value={filters.to} />
        <Choice
          name="institute"
          label={t("polls.filter.institute")}
          any={t("polls.filter.anyInstitute")}
          value={filters.institute[0] ?? ""}
          values={options.institutes}
          display={(institute) => institute}
        />
        <Choice
          name="party"
          label={t("polls.filter.party")}
          any={t("polls.filter.allParties")}
          value={filters.party[0] ?? ""}
          values={options.parties}
          display={(party) => componentName(page, party)}
        />
        <Choice
          name="coveragePeriod"
          label={t("polls.filter.coveragePeriod")}
          any={t("polls.filter.anyPeriod")}
          value={filters.coveragePeriod ?? ""}
          values={options.coveragePeriods.map((period) => period.id)}
          display={(period) => period}
        />
        <p className="check">
          <label htmlFor="polls-includeExcluded">
            <input
              type="checkbox"
              id="polls-includeExcluded"
              name="includeExcluded"
              value="true"
              defaultChecked={filters.includeExcluded}
            />{" "}
            {t("polls.filter.includeExcluded")}
          </label>
        </p>
        <p className="actions">
          <button className="btn primary" type="submit">
            {t("polls.filter.apply")}
          </button>{" "}
          <a className="btn" href={clearPath(page)}>
            {t("polls.filter.clear")}
          </a>
        </p>
        <p className="footnote">{t("polls.filter.dateHint")}</p>
      </fieldset>
    </form>
  );
}

export { PollFilters };

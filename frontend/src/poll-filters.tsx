import type { JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { componentName } from "./labels";
import type { PollFilterState, PollOptions } from "./poll-table";

/**
 * The poll table's controls.
 *
 * Every control is a labelled native element rather than a styled div, so the keyboard order, the
 * focus ring and the screen-reader name come from the platform. The filter applies as it changes;
 * the submit button stays for the reader who arrived without a script and for anyone who expects
 * a form to have one.
 */

interface Props {
  readonly page: Bootstrap;
  readonly options: PollOptions;
  readonly filters: PollFilterState;
  readonly onChange: (filters: PollFilterState) => void;
  readonly t: Translate;
}

/** An empty select value means the filter is not applied, never a value called "all". */
function one(value: string): readonly string[] {
  if (value === "") {
    return [];
  }
  return [value];
}

function bound(value: string): string | null {
  if (value === "") {
    return null;
  }
  return value;
}

function DateFilter({
  name,
  label,
  value,
  onChange,
}: {
  readonly name: string;
  readonly label: string;
  readonly value: string | null;
  readonly onChange: (value: string | null) => void;
}): JSX.Element {
  return (
    <p>
      <label htmlFor={`polls-${name}`}>{label}</label>{" "}
      <input
        type="date"
        id={`polls-${name}`}
        name={name}
        value={value ?? ""}
        onChange={(event) => onChange(bound(event.target.value))}
      />
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
  onChange,
}: {
  readonly name: string;
  readonly label: string;
  readonly any: string;
  readonly value: string;
  readonly values: readonly string[];
  readonly display: (value: string) => string;
  readonly onChange: (value: string) => void;
}): JSX.Element {
  return (
    <p>
      <label htmlFor={`polls-${name}`}>{label}</label>{" "}
      <select
        id={`polls-${name}`}
        name={name}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      >
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

function PollFilters({ page, options, filters, onChange, t }: Props): JSX.Element {
  return (
    <form
      className="filters"
      method="get"
      action={page.route.path}
      onSubmit={(event) => event.preventDefault()}
    >
      <fieldset>
        <legend>{t("polls.filters")}</legend>
        <DateFilter
          name="from"
          label={t("polls.filter.from")}
          value={filters.from}
          onChange={(from) => onChange({ ...filters, from })}
        />
        <DateFilter
          name="to"
          label={t("polls.filter.to")}
          value={filters.to}
          onChange={(to) => onChange({ ...filters, to })}
        />
        <Choice
          name="institute"
          label={t("polls.filter.institute")}
          any={t("polls.filter.anyInstitute")}
          value={filters.institute[0] ?? ""}
          values={options.institutes}
          display={(institute) => institute}
          onChange={(institute) => onChange({ ...filters, institute: one(institute) })}
        />
        <Choice
          name="party"
          label={t("polls.filter.party")}
          any={t("polls.filter.allParties")}
          value={filters.party[0] ?? ""}
          values={options.parties}
          display={(party) => componentName(page, party)}
          onChange={(party) => onChange({ ...filters, party: one(party) })}
        />
        <Choice
          name="coveragePeriod"
          label={t("polls.filter.coveragePeriod")}
          any={t("polls.filter.anyPeriod")}
          value={filters.coveragePeriod ?? ""}
          values={options.coveragePeriods.map((period) => period.id)}
          display={(period) => period}
          onChange={(period) => onChange({ ...filters, coveragePeriod: bound(period) })}
        />
        <p className="check">
          <label htmlFor="polls-includeExcluded">
            <input
              type="checkbox"
              id="polls-includeExcluded"
              name="includeExcluded"
              value="true"
              checked={filters.includeExcluded}
              onChange={(event) => onChange({ ...filters, includeExcluded: event.target.checked })}
            />{" "}
            {t("polls.filter.includeExcluded")}
          </label>
        </p>
        <p className="actions">
          <a className="btn" href={page.route.path}>
            {t("polls.filter.clear")}
          </a>
        </p>
        <p className="footnote">{t("polls.filter.dateHint")}</p>
      </fieldset>
    </form>
  );
}

export { PollFilters };

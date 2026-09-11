import type { JSX } from "react";
import type { Bootstrap, Series, Translate } from "./bootstrap";
import { decimal, percent, shortDate } from "./format";

/**
 * The chart's table alternative. It carries the same sampled days and the same values, so a reader
 * who cannot use the chart is not reading a summary of it but the thing itself.
 */

function cell(page: Bootstrap, series: Series, index: number, t: Translate): string {
  const value = series.mean[index];
  if (value === undefined || value === null) {
    return t("estimate.unavailable");
  }
  return percent(decimal(value, page.language), page.language);
}

function TimelineTable({
  page,
  dates,
  drawn,
  label,
  t,
}: {
  readonly page: Bootstrap;
  readonly dates: readonly string[];
  readonly drawn: readonly Series[];
  readonly label: (component: string) => string;
  readonly t: Translate;
}): JSX.Element {
  return (
    <table>
      <caption>{t("timeline.tableCaption")}</caption>
      <thead>
        <tr>
          <th scope="col">{t("timeline.column.date")}</th>
          {drawn.map((series) => (
            <th scope="col" key={series.component} className="num">
              {label(series.component)}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {dates.map((day, position) => (
          <tr key={day}>
            <th scope="row">{shortDate(day, page.locale)}</th>
            {drawn.map((series) => (
              <td key={series.component} className="num">
                {cell(page, series, position, t)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export { TimelineTable };

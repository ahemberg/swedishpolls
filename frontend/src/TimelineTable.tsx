import type { JSX } from "react";
import type { Bootstrap, Series, Translate } from "./bootstrap";
import { decimal, interval, percent, shortDate } from "./format";

import type { PageTextKey } from "./text";

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
  intervals = false,
}: {
  readonly page: Bootstrap;
  readonly dates: readonly string[];
  readonly drawn: readonly Series[];
  readonly label: (component: string) => string;
  readonly t: Translate;
  readonly intervals?: boolean;
}): JSX.Element {
  let caption: PageTextKey = "timeline.tableCaption";
  let className: string | undefined;
  if (intervals) {
    caption = "coalitionHistory.table";
    className = "coalition-history";
  }
  function value(series: Series, index: number): string {
    if (intervals) {
      return interval(
        [series.mean[index], series.lower[index], series.upper[index]],
        page.language,
        t,
      );
    }
    return cell(page, series, index, t);
  }
  return (
    <table className={className}>
      <caption>{t(caption)}</caption>
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
                {value(series, position)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export { TimelineTable };

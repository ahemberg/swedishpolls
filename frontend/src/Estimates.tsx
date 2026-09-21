import type { JSX } from "react";
import type { Bootstrap, Interval, ResultsPageData, Translate } from "./bootstrap";
import type { Seats } from "./chamber";
import { colour, date, decimal, level, percent } from "./format";
import { componentName } from "./labels";
import { Sparkline } from "./Sparkline";

/**
 * The per-party estimate. The interval is written in words, never as a plus-minus, and the seat
 * column holds the integer point allocation the arc uses.
 */

interface Props {
  readonly page: Bootstrap;
  readonly data: ResultsPageData;
  readonly t: Translate;
}

interface RowProps extends Props {
  readonly component: Interval;
}

function PartyName({
  page,
  component,
}: {
  readonly page: Bootstrap;
  readonly component: string;
}): JSX.Element {
  const name = componentName(page, component);
  const path = page.partyPaths[component];
  if (path === undefined) {
    return <>{name}</>;
  }
  return <a href={path}>{name}</a>;
}

function verbal(component: Interval, page: Bootstrap, t: Translate): string {
  if (component.lower === null || component.upper === null) {
    return t("estimate.unavailable");
  }
  return t("estimate.verbal", {
    lower: percent(decimal(component.lower, page.language), page.language),
    upper: percent(decimal(component.upper, page.language), page.language),
  });
}

/** OTHER is excluded from the allocation by rule, which is a different thing from having none. */
function seatsOf(seats: Seats, component: string, t: Translate): string {
  if (seats.excludedFromAllocation.includes(component)) {
    return t("estimate.noSeats");
  }
  const party = seats.parties.find((entry) => entry.component === component);
  if (party === undefined || party.pointSeats === null) {
    return t("estimate.unavailable");
  }
  return String(party.pointSeats);
}

function EstimateRow({ page, data, t, component }: RowProps): JSX.Element {
  if (component.mean === null) {
    return (
      <tr>
        <th scope="row">
          <span className="swatch" style={{ background: colour(component.component) }} />
          <PartyName page={page} component={component.component} />
        </th>
        <td colSpan={4}>{t("estimate.unavailable")}</td>
      </tr>
    );
  }
  return (
    <tr>
      <th scope="row">
        <span className="swatch" style={{ background: colour(component.component) }} />
        <PartyName page={page} component={component.component} />
      </th>
      <td className="num">{percent(decimal(component.mean, page.language), page.language)}</td>
      <td>{verbal(component, page, t)}</td>
      <td className="num">{seatsOf(data.seats, component.component, t)}</td>
      <td className="spark">
        {data.history !== undefined && (
          <Sparkline history={data.history} component={component.component} />
        )}
      </td>
    </tr>
  );
}

function Estimates({ page, data, t }: Props): JSX.Element {
  const { latest } = data;
  const fi = page.partyPaths.FI;
  return (
    <section className="sec o-estimates">
      <h2>{t("estimate.title")}</h2>
      <p className="meta">
        {t("estimate.fieldwork", { date: date(latest.lastFieldworkDate, page.locale) })}
      </p>
      <div className="scroll">
        <table>
          <caption>{t("estimate.caption", { level: level(latest.intervalLevel) })}</caption>
          <thead>
            <tr>
              <th scope="col">{t("estimate.column.party")}</th>
              <th scope="col" className="num">
                {t("estimate.column.estimate")}
              </th>
              <th scope="col">{t("estimate.column.interval")}</th>
              <th scope="col" className="num">
                {t("estimate.column.seats")}
              </th>
              <th scope="col" className="spark">
                {t("estimate.column.trend")}
              </th>
            </tr>
          </thead>
          <tbody>
            {latest.components.map((component) => (
              <EstimateRow
                key={component.component}
                page={page}
                data={data}
                t={t}
                component={component}
              />
            ))}
          </tbody>
        </table>
      </div>
      {Object.entries(latest.unavailable).map(([component, reason]) => (
        <p className="footnote" key={component}>
          {`${componentName(page, component)}: ${t("estimate.unavailable")} (${reason.reason})`}
        </p>
      ))}
      {fi !== undefined && (
        <p>
          <a href={fi}>{t("party.fiLink")}</a>
        </p>
      )}
    </section>
  );
}

export { Estimates };

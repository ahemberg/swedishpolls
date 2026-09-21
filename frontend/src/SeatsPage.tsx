import type { JSX } from "react";
import type { Bootstrap, ResultsPageData, Translate } from "./bootstrap";
import { ChamberHeading } from "./ChamberHeading";
import type { Seats, SeatsParty } from "./chamber";
import { Downloads } from "./Downloads";
import { decimal, level, probability } from "./format";
import { Hemicycle, hemicycleSummary } from "./Hemicycle";
import { componentName } from "./labels";
import { SeatColumns, seatSpan } from "./seat-columns";

/**
 * The national seat approximation.
 *
 * The arc and the seat column are one integer allocation of the estimated mean support, which is
 * why they fill the chamber exactly. The posterior mean and the interval beside them are summaries
 * over the model's draws and are a different quantity: rounding those would not add up to 349,
 * and the page keeps the two apart rather than presenting either as the other.
 */

interface Props {
  readonly page: Bootstrap;
  readonly data: ResultsPageData;
  readonly t: Translate;
}

/** The seat interval as the two integers the publication carries. */
function span(party: SeatsParty): string {
  if (party.seatInterval === null) {
    return "";
  }
  return seatSpan(party.seatInterval);
}

/** The posterior mean, which is a different quantity from the integer allocation beside it. */
function mean(party: SeatsParty, page: Bootstrap): string {
  if (party.meanSeats === null) {
    return "";
  }
  return decimal(party.meanSeats, page.language);
}

/** The chance of reaching 4% nationally, never inferred from the rounded seat column. */
function threshold(party: SeatsParty, page: Bootstrap, t: Translate): string {
  if (party.thresholdProbability === null) {
    return t("estimate.unavailable");
  }
  return probability(party.thresholdProbability, page.language);
}

/** The accessible alternative to the arc: the same allocation, readable row by row. */
function SeatTable({
  page,
  seats,
  t,
}: {
  readonly page: Bootstrap;
  readonly seats: Seats;
  readonly t: Translate;
}): JSX.Element {
  return (
    <div className="scroll">
      <table className="seats">
        <caption>{t("seats.caption", { level: level(seats.intervalLevel) })}</caption>
        <thead>
          <tr>
            <th scope="col">{t("estimate.column.party")}</th>
            <SeatColumns t={t} />
            <th scope="col" className="num">
              {t("seats.column.threshold")}
            </th>
          </tr>
        </thead>
        <tbody>
          {seats.parties.map((party) => (
            <tr key={party.component}>
              <th scope="row">{componentName(page, party.component)}</th>
              <td className="num">{party.pointSeats}</td>
              <td className="num">{mean(party, page)}</td>
              <td className="num">{span(party)}</td>
              <td className="num">{threshold(party, page, t)}</td>
            </tr>
          ))}
          {Object.keys(seats.unavailable).map((component) => (
            <tr key={component}>
              <th scope="row">{componentName(page, component)}</th>
              <td className="num" colSpan={4}>
                {t("estimate.unavailable")}
              </td>
            </tr>
          ))}
          {seats.excludedFromAllocation.map((component) => (
            <tr key={component}>
              <th scope="row">{componentName(page, component)}</th>
              <td className="num" colSpan={4}>
                {t("estimate.noSeats")}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SeatsPage({ page, data, t }: Props): JSX.Element {
  const { seats, coalitions } = data;
  const summary = hemicycleSummary(seats, (component) => componentName(page, component));
  return (
    <div>
      <ChamberHeading
        page={page}
        t={t}
        title="seats.title"
        lead="seats.lead"
        majoritySeats={coalitions.majoritySeats}
      />
      <section className="sec">
        <Hemicycle
          seats={seats}
          majority={coalitions.majoritySeats}
          label={t("blocs.hemicycleLabel", { summary })}
          majorityLabel={t("blocs.majority")}
          totalLabel={t("blocs.total")}
        />
        <SeatTable page={page} seats={seats} t={t} />
        <p className="footnote">{t("seats.pointVersusMean")}</p>
        <p className="footnote">
          {t("seats.era", { year: String(seats.allocationRule.electionYear) })}
        </p>
        <p className="footnote">{t("seats.threshold")}</p>
        <p className="footnote">{t("seats.other")}</p>
        <p className="footnote">{seats.note}</p>
        <p className="footnote">{seats.allocationRule.tieNote}</p>
        {seats.sensitivity !== undefined && (
          <p className="footnote">{t("sensitivity.note", { note: seats.sensitivity })}</p>
        )}
      </section>
      <Downloads page={page} t={t} card="seats" />
    </div>
  );
}

export { SeatsPage };

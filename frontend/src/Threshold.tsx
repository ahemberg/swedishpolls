import type { JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import type { Seats, SeatsParty } from "./chamber";
import { colour, probability } from "./format";
import { componentName } from "./labels";

/**
 * Spärren 4 %: the probability of clearing the national threshold.
 *
 * It is deliberately not the dashed 4 % line on the timeline. That line is a level in percent; this
 * is a probability from the model's joint draws, and the note beneath says so.
 */

const PERCENT_SCALE = 100;

interface Props {
  readonly page: Bootstrap;
  readonly seats: Seats;
  readonly t: Translate;
}

function width(party: SeatsParty): string {
  if (party.thresholdProbability === null) {
    return "0%";
  }
  return `${Math.round(party.thresholdProbability * PERCENT_SCALE)}%`;
}

function reading(party: SeatsParty, page: Bootstrap, t: Translate): string {
  if (party.thresholdProbability === null) {
    return t("estimate.unavailable");
  }
  return probability(party.thresholdProbability, page.language);
}

function Threshold({ page, seats, t }: Props): JSX.Element {
  return (
    <section className="sec o-threshold">
      <h2>{t("threshold.title")}</h2>
      <table>
        <caption>{t("threshold.caption")}</caption>
        <thead>
          <tr>
            <th scope="col">{t("estimate.column.party")}</th>
            <th scope="col">{t("threshold.title")}</th>
            <th scope="col" className="num">
              {t("threshold.note")}
            </th>
          </tr>
        </thead>
        <tbody>
          {seats.parties.map((party) => (
            <tr key={party.component}>
              <th scope="row">
                <span className="swatch" style={{ background: colour(party.component) }} />
                {componentName(page, party.component)}
              </th>
              <td>
                <span className="bar">
                  <span style={{ width: width(party), background: colour(party.component) }} />
                </span>
              </td>
              <td className="num">{reading(party, page, t)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="footnote">{t("threshold.distinct")}</p>
      <p className="footnote">{seats.note}</p>
      {seats.sensitivity !== undefined && (
        <p className="footnote">{t("sensitivity.note", { note: seats.sensitivity })}</p>
      )}
    </section>
  );
}

export { Threshold };

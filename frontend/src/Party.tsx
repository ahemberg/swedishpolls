import type { JSX } from "react";
import type { Bootstrap, HouseEffect, PartyData, PartyObservation, Translate } from "./bootstrap";
import { resultsData } from "./bootstrap";
import { Downloads } from "./Downloads";
import { colour, count, date, decimal, percent, probability, shortDate } from "./format";
import { componentName } from "./labels";
import { PollColumns } from "./poll-columns";
import { Share } from "./Share";
import { Timeline } from "./Timeline";

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
}

function fieldwork(observation: PartyObservation, page: Bootstrap, t: Translate): string {
  const dated = [observation.collectionFrom, observation.collectionTo].filter(
    (iso): iso is string => iso !== null,
  );
  const shown = [...new Set(dated.map((iso) => shortDate(iso, page.locale)))];
  if (shown.length === 0) {
    return t("polls.missing");
  }
  return shown.join(" - ");
}

function estimateReading(
  page: Bootstrap,
  party: PartyData,
  t: Translate,
  name: string,
): JSX.Element {
  const { estimate } = party;
  if (estimate.mean === null || estimate.lower === null || estimate.upper === null) {
    return <p>{t("party.noCurrent", { party: name })}</p>;
  }
  return (
    <>
      <p className="party-number">
        <b>{percent(decimal(estimate.mean, page.language), page.language)}</b>
        <span>
          {t("estimate.verbal", {
            lower: percent(decimal(estimate.lower, page.language), page.language),
            upper: percent(decimal(estimate.upper, page.language), page.language),
          })}
        </span>
      </p>
      <p className="meta">{t("estimate.fieldwork", { date: fieldworkDate(page) })}</p>
      <p>{t("party.thresholdReading", { probability: thresholdReading(page, party, t) })}</p>
      <EstimateFootnotes page={page} party={party} t={t} />
    </>
  );
}

function fieldworkDate(page: Bootstrap): string {
  return date(page.headlineDate ?? "", page.locale);
}

function thresholdReading(page: Bootstrap, party: PartyData, t: Translate): string {
  if (party.thresholdProbability === null) {
    return t("estimate.unavailable");
  }
  return probability(party.thresholdProbability, page.language);
}

function EstimateFootnotes({ page, party, t }: Props & { readonly party: PartyData }): JSX.Element {
  const sensitivity = resultsData(page)?.seats.sensitivity;
  return (
    <>
      {party.pointSeats !== null && (
        <p>{t("party.seatsReading", { seats: String(party.pointSeats) })}</p>
      )}
      {sensitivity !== undefined && (
        <p className="footnote">{t("sensitivity.note", { note: sensitivity })}</p>
      )}
    </>
  );
}

function Estimate({ page, party, t }: Props & { readonly party: PartyData }): JSX.Element {
  const name = componentName(page, party.component);
  return (
    <section className="party-heading">
      <h1>
        <span className="swatch" style={{ background: colour(party.component) }} />
        {name}
      </h1>
      {estimateReading(page, party, t, name)}
      <p className="meta">{t("notForecast")}</p>
    </section>
  );
}

function observationStatus(observation: PartyObservation, t: Translate): string {
  if (observation.modeled) {
    return t("party.eligible");
  }
  return t("party.excluded");
}

function sample(observation: PartyObservation, page: Bootstrap, t: Translate): string {
  if (observation.sampleSize === null) {
    return t("polls.missing");
  }
  return count(observation.sampleSize, page.locale);
}

function Observations({ page, party, t }: Props & { readonly party: PartyData }): JSX.Element {
  const name = componentName(page, party.component);
  return (
    <section className="sec">
      <h2>{t("party.observations")}</h2>
      <div className="scroll">
        <table>
          <caption>{t("party.observationsCaption", { party: name })}</caption>
          <thead>
            <tr>
              <PollColumns t={t} />
              <th scope="col" className="num">
                {t("party.column.support")}
              </th>
              <th scope="col">{t("party.column.eligibility")}</th>
            </tr>
          </thead>
          <tbody>
            {party.observations.map((observation) => (
              <tr key={observation.pollId}>
                <th scope="row">{observation.institute}</th>
                <td>{fieldwork(observation, page, t)}</td>
                <td className="num">{sample(observation, page, t)}</td>
                <td className="num">
                  {percent(decimal(observation.share, page.language), page.language)}
                </td>
                <td title={observation.exclusionReasons.join(", ")}>
                  {observationStatus(observation, t)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function signed(value: number, page: Bootstrap): string {
  if (value > 0) {
    return `+${decimal(value, page.language)}`;
  }
  return decimal(value, page.language);
}

function EffectRow({
  effect,
  page,
  t,
}: {
  readonly effect: HouseEffect;
  readonly page: Bootstrap;
  readonly t: Translate;
}): JSX.Element {
  return (
    <tr>
      <th scope="row">{effect.institute}</th>
      <td className="num">{signed(effect.mean, page)}</td>
      <td className="num">
        {t("party.effectRange", {
          lower: decimal(effect.lower, page.language),
          upper: decimal(effect.upper, page.language),
        })}
      </td>
    </tr>
  );
}

function EffectsTable({ page, party, t }: Props & { readonly party: PartyData }): JSX.Element {
  const { effects } = party.houseEffects;
  if (effects.length === 0) {
    return <p>{t("party.houseUnavailable")}</p>;
  }
  return (
    <div className="scroll">
      <table>
        <thead>
          <tr>
            <th scope="col">{t("polls.column.institute")}</th>
            <th scope="col" className="num">
              {t("party.houseEffect")}
            </th>
            <th scope="col" className="num">
              {t("party.houseInterval")}
            </th>
          </tr>
        </thead>
        <tbody>
          {effects.map((effect) => (
            <EffectRow key={effect.institute} effect={effect} page={page} t={t} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function HouseEffects({ page, party, t }: Props & { readonly party: PartyData }): JSX.Element {
  return (
    <section className="sec">
      <h2>{t("party.houseEffects")}</h2>
      <p>{t("party.houseReference")}</p>
      <EffectsTable page={page} party={party} t={t} />
      <p className="footnote">{party.houseEffects.reference}</p>
    </section>
  );
}

function Party({ page, t }: Props): JSX.Element | null {
  const party = resultsData(page)?.party;
  if (party === undefined) {
    return null;
  }
  return (
    <div>
      <Estimate page={page} party={party} t={t} />
      <Timeline page={page} t={t} component={party.component} observations={party.observations} />
      <Observations page={page} party={party} t={t} />
      <HouseEffects page={page} party={party} t={t} />
      <div className="cols2">
        <Downloads page={page} t={t} />
        <Share page={page} t={t} />
      </div>
    </div>
  );
}

export { Party };

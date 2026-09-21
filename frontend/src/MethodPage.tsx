import type { JSX, ReactNode } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { decimal } from "./format";
import { componentName } from "./labels";
import type { MethodData } from "./method";

import type { PageTextKey } from "./text";

/**
 * The method page.
 *
 * The server renders the whole explanation before any script runs, and this page repeats the same
 * sections from the same bootstrap: the data and its eligibility, the coverage periods, the model,
 * the recorded validation verdict with the registered gates, the reproduction values, and what the
 * national seat approximation leaves out.
 */

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
}

/** The frozen numbers beside the explanation, read from the bootstrap the server resolved. */
function method(page: Bootstrap): MethodData | undefined {
  return page.data?.method;
}

function Section({
  title,
  children,
}: {
  readonly title: string;
  readonly children: ReactNode;
}): JSX.Element {
  return (
    <section className="sec">
      <h2>{title}</h2>
      {children}
    </section>
  );
}

function Data({ t }: { readonly t: Translate }): JSX.Element {
  return (
    <Section title={t("method.data.title")}>
      <p>{t("method.data.history")}</p>
      <p>{t("method.data.provenance")}</p>
      <p>{t("method.data.eligibility")}</p>
      <p>{t("method.data.eras")}</p>
    </Section>
  );
}

function rosterNames(page: Bootstrap, roster: readonly string[]): string {
  return roster.map((component) => componentName(page, component)).join(", ");
}

function span(period: { readonly from: string; readonly to: string | null }): string {
  if (period.to === null) {
    return `${period.from}–`;
  }
  return `${period.from}–${period.to}`;
}

function status(period: { readonly supportValidated: boolean }, t: Translate): string {
  if (period.supportValidated) {
    return t("method.coverage.validated");
  }
  return t("method.coverage.candidate");
}

function Decision({
  period,
  t,
}: {
  readonly period: { readonly decision: string | null };
  readonly t: Translate;
}): JSX.Element {
  if (period.decision === null) {
    return <>{t("polls.missing")}</>;
  }
  return <a href={period.decision}>{t("method.coverage.column.decision")}</a>;
}

function Coverage({ page, t }: { readonly page: Bootstrap; readonly t: Translate }): JSX.Element {
  const periods = page.data?.latest.coveragePeriods ?? [];
  return (
    <Section title={t("method.coverage.title")}>
      <div className="scroll">
        <table className="coverage">
          <caption>{t("method.coverage.rosterCaption")}</caption>
          <thead>
            <tr>
              <th scope="col">{t("polls.column.period")}</th>
              <th scope="col">{t("method.coverage.column.span")}</th>
              <th scope="col">{t("method.coverage.column.roster")}</th>
              <th scope="col">{t("polls.column.other")}</th>
              <th scope="col">{t("method.coverage.column.status")}</th>
              <th scope="col">{t("method.coverage.column.decision")}</th>
            </tr>
          </thead>
          <tbody>
            {periods.map((period) => (
              <tr key={period.id}>
                <th scope="row">{period.id}</th>
                <td>{span(period)}</td>
                <td>{rosterNames(page, period.roster)}</td>
                <td>{rosterNames(page, period.otherMembers)}</td>
                <td>{status(period, t)}</td>
                <td>
                  <Decision period={period} t={t} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p>{t("method.coverage.otherNote")}</p>
      <p>{t("method.coverage.fiNote")}</p>
      <p>{t("method.coverage.boundaryNote")}</p>
    </Section>
  );
}

function Model({ t }: { readonly t: Translate }): JSX.Element {
  return (
    <Section title={t("method.model.title")}>
      <p>{t("method.model.observations")}</p>
      <p>{t("method.model.estimand")}</p>
      <p>{t("method.model.house")}</p>
      <p>{t("method.model.overdispersion")}</p>
      <p>{t("method.model.hyper")}</p>
      <p>{t("method.model.draws")}</p>
    </Section>
  );
}

interface GateRow {
  readonly label: string;
  readonly value: string;
}

function gateRows(page: Bootstrap, data: MethodData, t: Translate): readonly GateRow[] {
  return [
    { label: t("method.coverage.gate.polls"), value: String(data.coverage.minObservations) },
    { label: t("method.coverage.gate.institutes"), value: String(data.coverage.minInstitutes) },
    { label: t("method.coverage.gate.gap"), value: String(data.coverage.maxInternalGapDays) },
    {
      label: t("method.coverage.gate.shifts"),
      value: data.coverage.boundaryShiftDays.join(", "),
    },
    {
      label: t("method.coverage.gate.burnIn"),
      value: String(data.coverage.stabilityBurnInDays),
    },
    {
      label: t("method.coverage.gate.stability"),
      value: decimal(data.coverage.maxStabilityShiftPoints, page.language),
    },
    {
      label: t("method.coverage.gate.development"),
      value: data.coverage.developmentThrough,
    },
  ];
}

function verdictKey(data: MethodData): PageTextKey {
  if (data.verdict.released) {
    return "method.validation.verdictReleased";
  }
  return "method.validation.verdictBlocked";
}

function Validation({
  page,
  data,
  t,
}: {
  readonly page: Bootstrap;
  readonly data: MethodData;
  readonly t: Translate;
}): JSX.Element {
  const gates = gateRows(page, data, t).map((row) => (
    <tr key={row.label}>
      <th scope="row">{row.label}</th>
      <td className="num">{row.value}</td>
    </tr>
  ));
  let failed: JSX.Element | null = null;
  if (data.verdict.failedGates.length > 0) {
    failed = <p>{t("method.validation.failed", { gates: data.verdict.failedGates.join(", ") })}</p>;
  }
  return (
    <Section title={t("method.validation.title")}>
      <p>{t(verdictKey(data))}</p>
      {failed}
      <p className="meta">{t("method.validation.gatesLead")}</p>
      <ul>
        <li>{t("method.validation.gateScore")}</li>
        <li>{t("method.validation.gateCoverage")}</li>
        <li>{t("method.validation.gateMisfit")}</li>
      </ul>
      <p className="meta">{t("method.validation.coverageLead")}</p>
      <table className="gates">
        <tbody>{gates}</tbody>
      </table>
      <p>{t("method.validation.sensitivity")}</p>
    </Section>
  );
}

function Reproduction({
  data,
  t,
}: {
  readonly data: MethodData;
  readonly t: Translate;
}): JSX.Element {
  return (
    <Section title={t("method.reproduction.title")}>
      <p>{t("method.reproduction.seed", { seed: String(data.draws.seed) })}</p>
      <p>{t("method.reproduction.draws", { draws: String(data.draws.count) })}</p>
      <p>{t("method.reproduction.decimals", { decimals: String(data.draws.decimals) })}</p>
      <p>
        {t("method.reproduction.estimator", {
          version: data.estimator.version,
          library: data.estimator.numericalLibrary,
        })}
      </p>
      <p>
        {t("method.reproduction.protocols", {
          development: data.estimator.developmentProtocol,
          release: data.estimator.releaseProtocol,
        })}
      </p>
      <p>{t("method.reproduction.inputs")}</p>
    </Section>
  );
}

function Seats({ page, t }: { readonly page: Bootstrap; readonly t: Translate }): JSX.Element {
  return (
    <Section title={t("method.seats.title")}>
      <p>{t("seats.era", { year: String(page.approximatedElection ?? "") })}</p>
      <p>{t("seats.threshold")}</p>
      <p>{t("about.seats")}</p>
      <p>{t("seats.tie")}</p>
      <p>{t("seats.pointVersusMean")}</p>
    </Section>
  );
}

function MethodPage({ page, t }: Props): JSX.Element | null {
  const data = method(page);
  if (data === undefined) {
    return null;
  }
  return (
    <div>
      <h1>{t("head.title.method")}</h1>
      <p className="meta">{t("method.lead")}</p>
      <Data t={t} />
      <Coverage page={page} t={t} />
      <Model t={t} />
      <Validation page={page} data={data} t={t} />
      <Reproduction data={data} t={t} />
      <Seats page={page} t={t} />
    </div>
  );
}

export { MethodPage };

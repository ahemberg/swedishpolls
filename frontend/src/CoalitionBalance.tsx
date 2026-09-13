import type { JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import {
  type Assignment,
  blockColors,
  type CoalitionHistoryData,
  destinationOf,
  groupsFor,
  PARTIES,
  total,
} from "./coalition-history";
import { share, shortDate } from "./format";

const ARC = "M 40 145 A 110 110 0 0 1 260 145";
const FULL_SUPPORT = 100;
const SEPARATOR = ": ";
const MIDDLE_DOT = " · ";

interface Props {
  readonly assignment: Assignment;
  readonly initial: CoalitionHistoryData;
  readonly page: Bootstrap;
  readonly t: Translate;
}

function CoalitionBalance({ assignment, initial, page, t }: Props): JSX.Element {
  const groups = groupsFor(assignment);
  const shares = initial.latest.partyMeans;
  const a = total(groups.a, shares);
  const b = total(groups.b, shares);
  const outsideParties = PARTIES.filter(
    (party) => destinationOf(assignment, party) === "unassigned",
  );
  const unassigned = total(outsideParties, shares);
  const remainder = initial.latest.comparableRemainderMean;
  let outside: number | null = null;
  if (
    PARTIES.every((party) => typeof shares[party] === "number") &&
    typeof unassigned === "number" &&
    typeof remainder === "number"
  ) {
    outside = unassigned + remainder;
  }
  const colors = blockColors([groups.a, groups.b], shares);
  const formatted = (number: number | null): string => share(number, page.language, t);
  return (
    <section className="coalition-balance" aria-label={t("coalitionEditor.support")}>
      <h3>{t("coalitionEditor.support")}</h3>
      {outside === null && <p>{t("coalitionEditor.supportUnavailable")}</p>}
      {outside !== null && (
        <svg
          viewBox="0 0 300 165"
          role="img"
          aria-label={t("coalitionEditor.supportLabel", {
            a: formatted(a),
            b: formatted(b),
            outside: formatted(outside),
          })}
        >
          <path d={ARC} pathLength={FULL_SUPPORT} />
          <path
            className="balance-a"
            d={ARC}
            pathLength={FULL_SUPPORT}
            stroke={colors[0]}
            strokeDasharray={`${a ?? 0} ${FULL_SUPPORT}`}
          />
          <path
            className="balance-b"
            d={ARC}
            pathLength={FULL_SUPPORT}
            stroke={colors[1]}
            strokeDasharray={`${b ?? 0} ${FULL_SUPPORT}`}
            strokeDashoffset={-(FULL_SUPPORT - (b ?? 0))}
          />
          <text x="150" y="125" textAnchor="middle">
            {t("coalitionEditor.outside")}
          </text>
          <text className="balance-total" x="150" y="148" textAnchor="middle">
            {formatted(outside)}
          </text>
        </svg>
      )}
      <div className="balance-labels">
        <span>
          {t("coalitionEditor.a")}
          <strong>{formatted(a)}</strong>
        </span>
        <span>
          {t("coalitionEditor.b")}
          <strong>{formatted(b)}</strong>
        </span>
      </div>
      <p className="meta">
        {t("coalitionEditor.unassigned")}
        {SEPARATOR}
        {formatted(unassigned)}
        <br />
        {t("coalitionEditor.remainder")}
        {SEPARATOR}
        {formatted(remainder)}
        <br />
        {shortDate(initial.latest.date, page.locale)}
        {MIDDLE_DOT}
        {t("coalitionEditor.notSeats")}
      </p>
    </section>
  );
}

export { CoalitionBalance };

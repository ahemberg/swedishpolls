import type { JSX } from "react";
import type { Bootstrap, ResultsPageData, Translate } from "./bootstrap";
import { ChamberHeading } from "./ChamberHeading";
import { CoalitionHistory } from "./CoalitionHistory";
import type { CoalitionResults } from "./chamber";
import { Downloads } from "./Downloads";
import { decimal, probability } from "./format";
import { coalitionName, memberNames } from "./labels";
import { SeatColumns, seatSpan } from "./seat-columns";

/**
 * The ten approved coalitions, and every pair of them.
 *
 * A label states which parties are counted together and nothing else. Opposition and c_l_mp_s are
 * separate rows because the first counts V and the second counts L; collapsing them would publish
 * nine memberships under ten names.
 *
 * Every probability here is counted over the same joint draws as the seat allocation, which is
 * what lets a pair be compared at all: two marginal summaries could not answer which coalition
 * takes more seats.
 */

interface Props {
  readonly page: Bootstrap;
  readonly data: ResultsPageData;
  readonly t: Translate;
}

function Catalogue({
  page,
  coalitions,
  t,
}: {
  readonly page: Bootstrap;
  readonly coalitions: CoalitionResults;
  readonly t: Translate;
}): JSX.Element {
  return (
    <div className="scroll">
      <table className="coalitions">
        <caption>{t("coalitions.caption")}</caption>
        <thead>
          <tr>
            <th scope="col">{t("estimate.column.party")}</th>
            <th scope="col">{t("coalitions.column.parties")}</th>
            <SeatColumns t={t} />
            <th scope="col" className="num">
              {t("coalitions.column.majority")}
            </th>
          </tr>
        </thead>
        <tbody>
          {coalitions.coalitions.map((coalition) => (
            <tr key={coalition.id}>
              <th scope="row">{coalitionName(page, coalition.id)}</th>
              <td>{memberNames(page, coalition.parties)}</td>
              <td className="num">{coalition.pointSeats}</td>
              <td className="num">{decimal(coalition.meanSeats, page.language)}</td>
              <td className="num">{seatSpan(coalition.seatInterval)}</td>
              <td className="num">{probability(coalition.majorityProbability, page.language)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Every pair, counted draw by draw. A tie is its own column, not a win for either side. */
function Pairwise({
  page,
  coalitions,
  t,
}: {
  readonly page: Bootstrap;
  readonly coalitions: CoalitionResults;
  readonly t: Translate;
}): JSX.Element {
  return (
    <section className="sec">
      <h2>{t("pairwise.title")}</h2>
      <div className="scroll">
        <table className="pairwise">
          <caption>{t("pairwise.caption")}</caption>
          <thead>
            <tr>
              <th scope="col">{t("pairwise.column.left")}</th>
              <th scope="col">{t("pairwise.column.right")}</th>
              <th scope="col" className="num">
                {t("pairwise.column.leftLeads")}
              </th>
              <th scope="col" className="num">
                {t("pairwise.column.rightLeads")}
              </th>
              <th scope="col" className="num">
                {t("pairwise.tied")}
              </th>
            </tr>
          </thead>
          <tbody>
            {coalitions.comparison.pairs.map((pair) => (
              <tr className="pair" key={`${pair.left}-${pair.right}`}>
                <th scope="row">{coalitionName(page, pair.left)}</th>
                <td>{coalitionName(page, pair.right)}</td>
                <td className="num">{probability(pair.leftLeads, page.language)}</td>
                <td className="num">{probability(pair.rightLeads, page.language)}</td>
                <td className="num">{probability(pair.tied, page.language)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="footnote">{t("pairwise.tieNote")}</p>
    </section>
  );
}

function CoalitionsPage({ page, data, t }: Props): JSX.Element {
  const { coalitions } = data;
  return (
    <div>
      <ChamberHeading
        page={page}
        t={t}
        title="coalitions.title"
        lead="coalitions.lead"
        majoritySeats={coalitions.majoritySeats}
      />
      <CoalitionHistory
        page={page}
        history={data.coalitionHistory}
        error={page.coalitionLinkError}
        t={t}
      />
      <section className="sec">
        <Catalogue page={page} coalitions={coalitions} t={t} />
        <p className="footnote">{coalitions.note}</p>
        {coalitions.sensitivity !== undefined && (
          <p className="footnote">{t("sensitivity.note", { note: coalitions.sensitivity })}</p>
        )}
      </section>
      <Pairwise page={page} coalitions={coalitions} t={t} />
      <Downloads page={page} t={t} />
    </div>
  );
}

export { CoalitionsPage };

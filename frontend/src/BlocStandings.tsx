import type { JSX } from "react";
import type { Bootstrap, PageData, Translate } from "./bootstrap";
import type { Coalition } from "./chamber";
import { count, probability } from "./format";
import { Hemicycle, hemicycleSummary } from "./Hemicycle";
import { coalitionName, componentName } from "./labels";

/**
 * Blockläget: the seat arc with its majority marker, and the four default coalition results beside
 * it. A label states membership and implies no agreement to govern together.
 */

interface Props {
  readonly page: Bootstrap;
  readonly data: PageData;
  readonly t: Translate;
}

function defaults(data: PageData): readonly Coalition[] {
  return data.coalitions.overviewDefaults.flatMap((id) => {
    const found = data.coalitions.coalitions.find((coalition) => coalition.id === id);
    if (found === undefined) {
      return [];
    }
    return [found];
  });
}

function BlocStandings({ page, data, t }: Props): JSX.Element {
  const { seats, coalitions } = data;
  const summary = hemicycleSummary(seats, (component) => componentName(page, component));
  return (
    <section className="sec o-blocs">
      <h2>{t("blocs.title")}</h2>
      <Hemicycle
        seats={seats}
        majority={coalitions.majoritySeats}
        label={t("blocs.hemicycleLabel", { summary })}
        majorityLabel={t("blocs.majority")}
        totalLabel={t("blocs.total")}
      />
      <div className="scroll">
        <table>
          <caption>{t("blocs.caption")}</caption>
          <thead>
            <tr>
              <th scope="col">{t("estimate.column.party")}</th>
              <th scope="col" className="num">
                {t("estimate.column.seats")}
              </th>
              <th scope="col" className="num">
                {t("blocs.majority")}
              </th>
            </tr>
          </thead>
          <tbody>
            {defaults(data).map((coalition) => (
              <tr key={coalition.id}>
                <th scope="row">{coalitionName(page, coalition.id)}</th>
                <td className="num">{count(coalition.pointSeats, page.locale)}</td>
                <td className="num">{probability(coalition.majorityProbability, page.language)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="footnote">{t("blocs.pointSeats")}</p>
      <p className="footnote">{coalitions.note}</p>
      {coalitions.sensitivity !== undefined && (
        <p className="footnote">{t("sensitivity.note", { note: coalitions.sensitivity })}</p>
      )}
    </section>
  );
}

export { BlocStandings };

import type { JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { headlineDate } from "./bootstrap";
import { date } from "./format";

import type { PageTextKey } from "./text";

/**
 * How a page about the chamber opens: what it is, the day it claims, that it is not a forecast,
 * and where the majority line falls.
 *
 * The seats page and the coalitions page open the same way because they answer the same question
 * from one set of draws. Keeping the opening in one place is also what keeps it identical to the
 * markup Spring already rendered: a reader must not watch the lead sentence change, or vanish,
 * the moment the script mounts.
 */

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
  /** The wording key of this page's title. */
  readonly title: PageTextKey;
  /** The wording key of its lead sentence, which takes the headline date. */
  readonly lead: PageTextKey;
  /** The seats a coalition needs for its own majority. */
  readonly majoritySeats: number;
}

function ChamberHeading({ page, t, title, lead, majoritySeats }: Props): JSX.Element {
  const asOf = date(headlineDate(page), page.locale);
  return (
    <>
      <h1>
        {t(title)} <span className="asof">{t("headline.asOf", { date: asOf })}</span>
      </h1>
      <p className="meta">
        {t(lead, { date: asOf })} {t("notForecast")}
      </p>
      <p className="meta">{t("coalitions.majorityLine", { majority: String(majoritySeats) })}</p>
    </>
  );
}

export { ChamberHeading };

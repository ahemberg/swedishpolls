import type { JSX } from "react";
import { BlocStandings } from "./BlocStandings";
import type { Bootstrap, Translate } from "./bootstrap";
import { headlineDate } from "./bootstrap";
import { Downloads } from "./Downloads";
import { Estimates } from "./Estimates";
import { date } from "./format";
import { LatestPolls } from "./LatestPolls";
import { Share } from "./Share";
import { Threshold } from "./Threshold";
import { Timeline } from "./Timeline";

/**
 * The approved variant D overview, in the approved desktop order: timeline, estimate table,
 * Blockläget beside Spärren 4 %, full-width latest polls, downloads beside sharing, then the one
 * method footer the shell adds. On a narrow screen the stylesheet lifts Blockläget to second,
 * because bloc standings are what most readers came for.
 */

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
}

function Overview({ page, t }: Props): JSX.Element | null {
  const { data, publication } = page;
  if (data === undefined || publication === undefined) {
    return null;
  }
  return (
    <div>
      <h1>
        {t("headline")}
        <span className="asof">
          {t("headline.asOf", { date: date(headlineDate(page), page.locale) })}
        </span>
      </h1>
      <p className="meta">{t("notForecast")}</p>
      <div className="overview">
        <Timeline page={page} t={t} />
        <Estimates page={page} data={data} t={t} />
        <div className="cols2">
          <BlocStandings page={page} data={data} t={t} />
          <Threshold page={page} seats={data.seats} t={t} />
        </div>
        <LatestPolls page={page} polls={data.polls} t={t} />
        <div className="cols2">
          <Downloads page={page} t={t} />
          <Share page={page} t={t} />
        </div>
      </div>
    </div>
  );
}

export { Overview };

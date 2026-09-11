import type { JSX } from "react";
import { About } from "./About";
import type { Bootstrap, PageData, Translate } from "./bootstrap";
import { COALITIONS, headlineDate, OVERVIEW, PARTY, SEATS } from "./bootstrap";
import { CoalitionsPage } from "./CoalitionsPage";
import { date, timestamp } from "./format";
import { Overview } from "./Overview";
import { Party } from "./Party";
import { SeatsPage } from "./SeatsPage";
import { SiteHeader } from "./SiteHeader";

/**
 * The page around the results: the header, the staleness notice, the body of whichever family this
 * route names, and the one method footer.
 */

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
}

/** When the last update failed, and null while the current publication is the latest one. */
function staleSince(page: Bootstrap): string | null {
  const { publication } = page;
  if (publication === undefined) {
    return null;
  }
  if (!publication.stale) {
    return null;
  }
  return publication.staleSince;
}

/** A failed update leaves the last validated publication visible, dated, and said out loud. */
function StaleNotice({ page, t }: Props): JSX.Element | null {
  const since = staleSince(page);
  if (since === null) {
    return null;
  }
  return (
    <p className="stale" role="status">
      {t("stale", {
        timestamp: timestamp(since, page.locale),
        date: date(headlineDate(page), page.locale),
      })}
    </p>
  );
}

/** Before the first successful publication there are no numbers, and the page says exactly that. */
function Unavailable({ page, t }: Props): JSX.Element {
  const checked = page.lastSourceCheck;
  return (
    <div>
      <h1>{t("unavailable.title")}</h1>
      <p>{t("unavailable.body")}</p>
      {checked !== undefined && checked !== null && (
        <p className="meta">
          {t("unavailable.lastCheck", { timestamp: timestamp(checked, page.locale) })}
        </p>
      )}
    </div>
  );
}

/** A family this build has no page for yet: its own title, and the shell around it. */
function Title({ page, t }: Props): JSX.Element {
  return <h1>{t(`head.title.${page.route.family.toLowerCase()}`)}</h1>;
}

interface ResultsProps {
  readonly page: Bootstrap;
  readonly data: PageData;
  readonly t: Translate;
}

/**
 * Which page each family that carries results is rendered by.
 *
 * One table rather than a cascade of family tests: adding a family is a line here, and the server
 * decides which family a route is rather than this file rediscovering it four times over.
 */
const PAGES: Readonly<Record<string, (props: ResultsProps) => JSX.Element>> = {
  [OVERVIEW]: ({ page, t }) => <Overview page={page} t={t} />,
  [PARTY]: ({ page, t }) => <Party page={page} t={t} />,
  [SEATS]: ({ page, data, t }) => <SeatsPage page={page} data={data} t={t} />,
  [COALITIONS]: ({ page, data, t }) => <CoalitionsPage page={page} data={data} t={t} />,
};

/** The page of whichever family carries results. The server attaches data only to those. */
function Results({ page, data, t }: ResultsProps): JSX.Element {
  const render = PAGES[page.route.family];
  if (render === undefined) {
    return <Title page={page} t={t} />;
  }
  return render({ page, data, t });
}

/**
 * The families this build renders. A family the server sent no data for is still a real page:
 * it keeps the shell, its own title and the method footer rather than reading as broken.
 */
function Body({ page, t }: Props): JSX.Element {
  const { data } = page;
  if (page.publication === undefined) {
    return <Unavailable page={page} t={t} />;
  }
  if (data === undefined) {
    return <Title page={page} t={t} />;
  }
  return <Results page={page} data={data} t={t} />;
}

function Shell({ page, t }: Props): JSX.Element {
  return (
    <div>
      <SiteHeader page={page} t={t} />
      <StaleNotice page={page} t={t} />
      <main id="main">
        <Body page={page} t={t} />
      </main>
      <About page={page} t={t} />
    </div>
  );
}

export { Shell };

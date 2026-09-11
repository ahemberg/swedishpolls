import type { JSX } from "react";
import { About } from "./About";
import type { Bootstrap, Translate } from "./bootstrap";
import { headlineDate, OVERVIEW, PARTY } from "./bootstrap";
import { date, timestamp } from "./format";
import { Overview } from "./Overview";
import { Party } from "./Party";
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

/** The overview is the family this page builds; the rest carry the shell and the method footer. */
function Body({ page, t }: Props): JSX.Element {
  if (page.publication === undefined) {
    return <Unavailable page={page} t={t} />;
  }
  if (page.route.family === OVERVIEW) {
    return <Overview page={page} t={t} />;
  }
  if (page.route.family === PARTY) {
    return <Party page={page} t={t} />;
  }
  return <h1>{t(`head.title.${page.route.family.toLowerCase()}`)}</h1>;
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

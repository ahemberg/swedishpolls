import type { JSX } from "react";
import { About } from "./About";
import type { Bootstrap, Family, PageData, Translate } from "./bootstrap";
import {
  COALITIONS,
  headlineDate,
  isResultsData,
  METHOD,
  OVERVIEW,
  PARTY,
  POLLS,
  POLLSTERS,
  SEATS,
} from "./bootstrap";
import { CoalitionsPage } from "./CoalitionsPage";
import { date, timestamp } from "./format";
import { MethodPage } from "./MethodPage";
import { Overview } from "./Overview";
import { Party } from "./Party";
import { PollsPage } from "./PollsPage";
import { PollstersPage } from "./PollstersPage";
import { SeatsPage } from "./SeatsPage";
import { SiteHeader } from "./SiteHeader";
import type { PageTextKey } from "./text";

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
function NoSource({ page, t }: Props): JSX.Element {
  return (
    <div>
      <h1>{t("source.noPolls.title")}</h1>
      <p>{t("source.noPolls.body")}</p>
      <p>
        <a className="btn primary" href={page.route.path}>
          {t("source.retry")}
        </a>
      </p>
    </div>
  );
}

/** The document title of each family, written out so no case mapping runs per page. */
const TITLES: Record<Family, PageTextKey> = {
  [OVERVIEW]: "head.title.overview",
  [PARTY]: "head.title.party",
  [SEATS]: "head.title.seats",
  [COALITIONS]: "head.title.coalitions",
  [POLLSTERS]: "head.title.pollsters",
  [POLLS]: "head.title.polls",
  [METHOD]: "head.title.method",
};

/** A family this build has no page for yet: its own title, and the shell around it. */
function Title({ page, t }: Props): JSX.Element {
  return <h1>{t(TITLES[page.route.family])}</h1>;
}

interface ResultsProps {
  readonly page: Bootstrap;
  readonly data: PageData;
  readonly t: Translate;
}

function SeatsResult({ page, data, t }: ResultsProps): JSX.Element {
  if (!isResultsData(data)) {
    return <Title page={page} t={t} />;
  }
  return <SeatsPage page={page} data={data} t={t} />;
}

function CoalitionsResult({ page, data, t }: ResultsProps): JSX.Element {
  if (!isResultsData(data)) {
    return <Title page={page} t={t} />;
  }
  return <CoalitionsPage page={page} data={data} t={t} />;
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
  [SEATS]: SeatsResult,
  [COALITIONS]: CoalitionsResult,
  [POLLSTERS]: ({ page, t }) => <PollstersPage page={page} t={t} />,
  [METHOD]: ({ page, t }) => <MethodPage page={page} t={t} />,
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
function Published({ page, t }: Props): JSX.Element {
  const { data, pollTable } = page;
  // The poll table is not one of the results documents: it publishes source observations rather
  // than the estimate, the allocation and the memberships, so it carries its own payload.
  if (page.route.family === POLLS && pollTable !== undefined) {
    return <PollsPage page={page} table={pollTable} t={t} />;
  }
  if (data === undefined) {
    return <Title page={page} t={t} />;
  }
  return <Results page={page} data={data} t={t} />;
}

function SourceBody({ page, t }: Props): JSX.Element {
  if (page.source === undefined) {
    return <NoSource page={page} t={t} />;
  }
  if (page.route.family === POLLS && page.pollTable !== undefined) {
    return <PollsPage page={page} table={page.pollTable} t={t} />;
  }
  return <Overview page={page} t={t} />;
}

function Body({ page, t }: Props): JSX.Element {
  if (page.publication !== undefined) {
    return <Published page={page} t={t} />;
  }
  return <SourceBody page={page} t={t} />;
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

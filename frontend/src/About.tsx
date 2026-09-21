import type { JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { headlineDate, latestData } from "./bootstrap";
import { date, level, timestamp } from "./format";

/**
 * The one method footer: what the estimate is, what its interval is conditional on, what the seat
 * figure approximates, what Övriga means, and what a missing value means. It appears once. The
 * decision rejected repeating disclaimers section by section.
 */

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
}

function Uncertainty({ page, t }: Props): JSX.Element | null {
  const latest = latestData(page);
  if (latest === undefined) {
    return null;
  }
  return <p>{t("about.uncertainty", { level: level(latest.intervalLevel) })}</p>;
}

function About({ page, t }: Props): JSX.Element | null {
  const { publication } = page;
  if (publication === undefined) {
    return null;
  }
  const { snapshot, modelRun } = publication;
  return (
    <footer className="about">
      <h2>{t("about.title")}</h2>
      <p>
        {t("about.method", {
          date: date(headlineDate(page), page.locale),
        })}
      </p>
      <Uncertainty page={page} t={t} />
      <p>{t("about.seats")}</p>
      <p>{t("about.other")}</p>
      <p>{t("about.missing")}</p>
      <p className="meta">
        {t("about.source", { source: snapshot.sourceUrl, snapshot: snapshot.sha256 })}
      </p>
      <p className="meta">
        {t("about.run", {
          run: modelRun.runId,
          code: modelRun.codeVersion,
          seed: String(modelRun.seed),
        })}
      </p>
      <p className="meta">
        {t("published", { timestamp: timestamp(publication.publishedAt, page.locale) })}
      </p>
      <p className="meta">
        {t("sourceChecked", { timestamp: timestamp(publication.sourceCheckedAt, page.locale) })}
      </p>
    </footer>
  );
}

export { About };

import type { JSX } from "react";
import type { Bootstrap, Translate } from "./bootstrap";
import { resultsData } from "./bootstrap";

/**
 * The journalist downloads. Every link names the publication this page resolved, so a file cannot
 * arrive from a later run than the numbers above it.
 */

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
}

function Download({ href, label }: { readonly href: string; readonly label: string }): JSX.Element {
  return (
    <li>
      <a className="btn" href={href}>
        {label}
      </a>
    </li>
  );
}

function partyComponent(page: Bootstrap): string | null {
  return resultsData(page)?.party?.component ?? null;
}

function pollsQuery(pin: string, component: string | null): string {
  if (component === null) {
    return pin;
  }
  return `${pin}&party=${component}`;
}

function pollsHref(page: Bootstrap, component: string | null): string {
  if (page.source !== undefined) {
    const pin = `?snapshot=${page.source.snapshotId}&language=${page.language}`;
    return `/source/polls.csv${pollsQuery(pin, component)}`;
  }
  const { api } = page;
  if (api === undefined) {
    return "";
  }
  const pin = `?publication=${api.publication}&language=${api.language}`;
  return `${api.base}/polls.csv${pollsQuery(pin, component)}`;
}

function estimatesPath(component: string | null): string {
  if (component === null) {
    return "estimates/latest";
  }
  return "estimates/history";
}

function Downloads({ page, t }: Props): JSX.Element | null {
  const { api, publication } = page;
  if (api === undefined || publication === undefined) {
    return null;
  }
  const component = partyComponent(page);
  const pin = `?publication=${api.publication}&language=${api.language}`;
  const estimates = estimatesPath(component);
  return (
    <section className="sec o-journalists">
      <h2>{t("downloads.title")}</h2>
      <ul className="downloads">
        <Download href={pollsHref(page, component)} label={t("downloads.polls")} />
        <Download href={`${api.base}/${estimates}${pin}`} label={t("downloads.estimates")} />
        <Download href={`${api.base}/seats${pin}`} label={t("downloads.seats")} />
        <Download href={`${api.base}/coalitions${pin}`} label={t("downloads.coalitions")} />
        {component !== null && (
          <>
            <Download href={`${api.base}/institutes${pin}`} label={t("downloads.houseEffects")} />
            <Download href={`${api.base}/elections${pin}`} label={t("downloads.elections")} />
          </>
        )}
      </ul>
      <p className="footnote">{t("downloads.pinned", { publication: api.publication })}</p>
    </section>
  );
}

export { Downloads };

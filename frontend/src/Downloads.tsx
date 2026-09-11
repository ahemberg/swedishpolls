import type { JSX } from "react";
import type { Bootstrap, CardKind, Language, Publication, Translate } from "./bootstrap";

/**
 * The journalist downloads. Every link names the publication this page resolved, so a file cannot
 * arrive from a later run than the numbers above it, and the PNG is labelled for what it is: a
 * dated summary image, not an export of the selected chart range.
 */

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
  /** Which published card this page offers. Defaults to the overview one. */
  readonly card?: CardKind;
}

/**
 * This route's card in this language, at its published asset version.
 *
 * A party page offers its own party's card, built from the component. Every other page offers the
 * publication-wide card that summarizes it, so a reader downloads what is actually on the page
 * rather than the overview.
 */
function image(
  publication: Publication,
  language: Language,
  component: string | null,
  card: CardKind,
): string | null {
  let kind: string = card;
  if (component !== null) {
    kind = `party-${component.toLowerCase()}`;
  }
  const asset = publication.assets[kind];
  if (asset === undefined) {
    return null;
  }
  return asset[language];
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

function ImageDownload({
  href,
  label,
}: {
  readonly href: string | null;
  readonly label: string;
}): JSX.Element | null {
  if (href === null) {
    return null;
  }
  return (
    <li>
      <a className="btn" href={href} download={true}>
        {label}
      </a>
    </li>
  );
}

function partyComponent(page: Bootstrap): string | null {
  return page.data?.party?.component ?? null;
}

function pollsQuery(pin: string, component: string | null): string {
  if (component === null) {
    return pin;
  }
  return `${pin}&party=${component}`;
}

function estimatesPath(component: string | null): string {
  if (component === null) {
    return "estimates/latest";
  }
  return "estimates/history";
}

function Downloads({ page, t, card = "overview" }: Props): JSX.Element | null {
  const { api, publication } = page;
  if (api === undefined || publication === undefined) {
    return null;
  }
  const component = partyComponent(page);
  const pin = `?publication=${api.publication}&language=${api.language}`;
  const polls = pollsQuery(pin, component);
  const estimates = estimatesPath(component);
  return (
    <section className="sec o-journalists">
      <h2>{t("downloads.title")}</h2>
      <ul className="downloads">
        <Download href={`${api.base}/polls.csv${polls}`} label={t("downloads.polls")} />
        <Download href={`${api.base}/${estimates}${pin}`} label={t("downloads.estimates")} />
        <Download href={`${api.base}/seats${pin}`} label={t("downloads.seats")} />
        <Download href={`${api.base}/coalitions${pin}`} label={t("downloads.coalitions")} />
        {component !== null && (
          <>
            <Download href={`${api.base}/institutes${pin}`} label={t("downloads.houseEffects")} />
            <Download href={`${api.base}/elections${pin}`} label={t("downloads.elections")} />
          </>
        )}
        <ImageDownload
          href={image(publication, page.language, component, card)}
          label={t("downloads.image")}
        />
      </ul>
      <p className="footnote">{t("downloads.imageNote")}</p>
      <p className="footnote">{t("downloads.pinned", { publication: api.publication })}</p>
    </section>
  );
}

export { Downloads };

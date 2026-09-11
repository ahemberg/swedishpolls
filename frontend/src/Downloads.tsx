import type { JSX } from "react";
import type { Bootstrap, Language, Publication, Translate } from "./bootstrap";

/**
 * The journalist downloads. Every link names the publication this page resolved, so a file cannot
 * arrive from a later run than the numbers above it, and the PNG is labelled for what it is: a
 * dated summary image, not an export of the selected chart range.
 */

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
}

/** This publication's overview card in this language, at its published asset version. */
function overviewImage(publication: Publication, language: Language): string | null {
  const { overview } = publication.assets;
  if (overview === undefined) {
    return null;
  }
  return overview[language];
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

function Downloads({ page, t }: Props): JSX.Element | null {
  const { api, publication } = page;
  if (api === undefined || publication === undefined) {
    return null;
  }
  const pin = `?publication=${api.publication}&language=${api.language}`;
  return (
    <section className="sec o-journalists">
      <h2>{t("downloads.title")}</h2>
      <ul className="downloads">
        <Download href={`${api.base}/polls.csv${pin}`} label={t("downloads.polls")} />
        <Download href={`${api.base}/estimates/latest${pin}`} label={t("downloads.estimates")} />
        <Download href={`${api.base}/seats${pin}`} label={t("downloads.seats")} />
        <ImageDownload
          href={overviewImage(publication, page.language)}
          label={t("downloads.image")}
        />
      </ul>
      <p className="footnote">{t("downloads.imageNote")}</p>
      <p className="footnote">{t("downloads.pinned", { publication: api.publication })}</p>
    </section>
  );
}

export { Downloads };

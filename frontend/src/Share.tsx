import type { JSX } from "react";
import { useState } from "react";
import type { Bootstrap, Translate } from "./bootstrap";

/**
 * Sharing. The link carries this publication, so what a reader shares is what a reader saw, and a
 * later publication does not change it. Native sharing is used where the browser offers it, and
 * the X intent where it does not.
 */

const X_INTENT = "https://x.com/intent/post";

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
}

function hasNativeShare(): boolean {
  return typeof navigator !== "undefined" && typeof navigator.share === "function";
}

function ShareButton({
  title,
  url,
  t,
}: {
  readonly title: string;
  readonly url: string;
  readonly t: Translate;
}): JSX.Element {
  if (hasNativeShare()) {
    return (
      <button
        type="button"
        className="btn primary"
        onClick={() => {
          navigator.share({ title, url }).catch(() => undefined);
        }}
      >
        {t("share.native")}
      </button>
    );
  }
  const intent = `${X_INTENT}?text=${encodeURIComponent(title)}&url=${encodeURIComponent(url)}`;
  return (
    <a className="btn" href={intent} rel="noreferrer noopener" target="_blank">
      {t("share.x")}
    </a>
  );
}

function note(copied: boolean, t: Translate): string {
  if (copied) {
    return t("share.copied");
  }
  return t("share.note");
}

function Share({ page, t }: Props): JSX.Element | null {
  const [copied, setCopied] = useState(false);
  const { publication } = page;
  if (publication === undefined) {
    return null;
  }
  const url = `${page.site.origin}${page.route.path}?publication=${publication.publicationId}`;
  const title = `${t("headline")} - ${page.site.name}`;
  return (
    <section className="sec">
      <h2>{t("share.title")}</h2>
      <ul className="downloads">
        <li>
          <ShareButton title={title} url={url} t={t} />
        </li>
        <li>
          <button
            type="button"
            className="btn"
            onClick={() => {
              navigator.clipboard
                .writeText(url)
                .then(() => setCopied(true))
                .catch(() => setCopied(false));
            }}
          >
            {t("share.copy")}
          </button>
        </li>
        <li>
          <a className="btn" href={url}>
            {t("share.permalink")}
          </a>
        </li>
      </ul>
      <p className="footnote" aria-live="polite">
        {note(copied, t)}
      </p>
    </section>
  );
}

export { Share };

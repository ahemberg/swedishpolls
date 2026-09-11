import type { JSX } from "react";
import type { Bootstrap, Language, Translate } from "./bootstrap";

/**
 * The brand, the translated navigation and the explicit language switch.
 *
 * The switch is a pair of links to the equivalent translated paths, so it keeps the reader on the
 * page they were on. Nothing here inspects the browser's languages: a shared link opens in the
 * language it was shared in.
 */

const LANGUAGES: readonly Language[] = ["sv", "en"];

interface Props {
  readonly page: Bootstrap;
  readonly t: Translate;
}

function current(active: boolean): "page" | undefined {
  if (active) {
    return "page";
  }
  return undefined;
}

function chosen(active: boolean): "true" | undefined {
  if (active) {
    return "true";
  }
  return undefined;
}

function SiteHeader({ page, t }: Props): JSX.Element {
  return (
    <header className="site">
      <a className="brand" href={page.alternates[page.language]}>
        {page.site.name}
      </a>
      <nav className="nav" aria-label={t("nav.label")}>
        <ul>
          {page.navigation.map((entry) => (
            <li key={entry.family}>
              <a href={entry.path} aria-current={current(entry.current)}>
                {entry.label}
              </a>
            </li>
          ))}
        </ul>
      </nav>
      <nav className="lang" aria-label={t("language.label")}>
        {LANGUAGES.map((code) => (
          <a
            key={code}
            href={page.alternates[code]}
            hrefLang={code}
            lang={code}
            aria-current={chosen(code === page.language)}
          >
            <abbr title={t(`language.${code}`)}>{t(`language.short.${code}`)}</abbr>
          </a>
        ))}
      </nav>
    </header>
  );
}

export { SiteHeader };

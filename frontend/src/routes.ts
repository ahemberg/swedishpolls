import type { Bootstrap, Family, Language } from "./bootstrap";
import { COALITIONS, METHOD, OVERVIEW, PARTY, POLLS, POLLSTERS, SEATS } from "./bootstrap";
import { translate } from "./text";

const PATHS: Readonly<Record<Family, readonly [string, string]>> = {
  [OVERVIEW]: ["/", "/en"],
  [PARTY]: ["/parti", "/en/party"],
  [SEATS]: ["/mandat", "/en/seats"],
  [COALITIONS]: ["/regeringsunderlag", "/en/coalitions"],
  [POLLSTERS]: ["/institut", "/en/pollsters"],
  [POLLS]: ["/matningar", "/en/polls"],
  [METHOD]: ["/metod", "/en/method"],
};
const SLUGS: ReadonlyMap<string, readonly [string, string]> = new Map([
  ["S", ["socialdemokraterna", "social-democrats"]],
  ["M", ["moderaterna", "moderates"]],
  ["SD", ["sverigedemokraterna", "sweden-democrats"]],
  ["V", ["vansterpartiet", "left-party"]],
  ["C", ["centerpartiet", "centre-party"]],
  ["KD", ["kristdemokraterna", "christian-democrats"]],
  ["L", ["liberalerna", "liberals"]],
  ["MP", ["miljopartiet", "green-party"]],
  ["FI", ["feministiskt-initiativ", "feminist-initiative"]],
]);
const NAVIGATION: readonly Family[] = [OVERVIEW, SEATS, COALITIONS, POLLSTERS, POLLS, METHOD];
const LANGUAGES: readonly Language[] = ["sv", "en"];

function index(language: Language): 0 | 1 {
  if (language === "sv") {
    return 0;
  }
  return 1;
}

function path(family: Family, language: Language, party: string | null = null): string {
  const base = PATHS[family][index(language)];
  if (party === null) {
    return base;
  }
  return `${base}/${SLUGS.get(party)?.[index(language)] ?? ""}`;
}

const ROUTES: readonly {
  readonly family: Family;
  readonly language: Language;
  readonly parameter: string | null;
}[] = LANGUAGES.flatMap((language) => [
  ...NAVIGATION.map((family) => ({ family, language, parameter: null })),
  ...[...SLUGS.keys()].map((parameter) => ({ family: PARTY, language, parameter }) as const),
]);

function routePage(url: URL): Bootstrap | undefined {
  const normalized = url.pathname.replace(/\/$/, "") || "/";
  const route = ROUTES.find(
    (entry) => path(entry.family, entry.language, entry.parameter) === normalized,
  );
  if (route === undefined) {
    return undefined;
  }
  const { language, family, parameter } = route;
  let locale = "sv-SE";
  if (language === "en") {
    locale = "en-GB";
  }
  return {
    language,
    locale,
    route: { family, parameter, path: normalized },
    alternates: {
      sv: path(family, "sv", parameter) + url.search,
      en: path(family, "en", parameter) + url.search,
    },
    navigation: NAVIGATION.map((entry) => ({
      family: entry,
      path: path(entry, language),
      current: entry === family,
    })),
    partyPaths: Object.fromEntries(
      [...SLUGS.keys()].map((party) => [party, path(PARTY, language, party)]),
    ),
    site: { name: translate(language, "site.name"), origin: url.origin },
  };
}

export { path, routePage };

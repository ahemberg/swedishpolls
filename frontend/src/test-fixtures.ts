import publicationResponse from "../../src/main/resources/api/v1/examples/publication.json" with {
  type: "json",
};
import type { Bootstrap, Family, Language } from "./bootstrap";
import { COALITIONS, METHOD, OVERVIEW, PARTY, POLLS, POLLSTERS, SEATS } from "./bootstrap";
import {
  COMPONENTS,
  latest,
  method,
  party,
  pollsters,
  pollTable,
  publication,
  results,
  seats,
} from "./test-data-fixtures";

const FAMILIES: readonly Family[] = [OVERVIEW, PARTY, SEATS, COALITIONS, POLLSTERS, POLLS, METHOD];
const LANGUAGES: readonly Language[] = ["sv", "en"];
const PATHS: Readonly<Record<Language, Readonly<Record<Family, string>>>> = {
  sv: {
    [OVERVIEW]: "/",
    [PARTY]: "/parti/S",
    [SEATS]: "/mandat",
    [COALITIONS]: "/regeringsunderlag",
    [POLLSTERS]: "/institut",
    [POLLS]: "/matningar",
    [METHOD]: "/metod",
  },
  en: {
    [OVERVIEW]: "/en",
    [PARTY]: "/en/party/S",
    [SEATS]: "/en/seats",
    [COALITIONS]: "/en/coalitions",
    [POLLSTERS]: "/en/pollsters",
    [POLLS]: "/en/polls",
    [METHOD]: "/en/method",
  },
};

function locale(language: Language): string {
  if (language === "sv") {
    return "sv-SE";
  }
  return "en-GB";
}

function siteName(language: Language): string {
  if (language === "sv") {
    return "Svenska väljarbarometern";
  }
  return "Swedish Poll of Polls";
}

function parameter(family: Family): string | null {
  if (family === PARTY) {
    return "S";
  }
  return null;
}

function partyPath(language: Language, component: string): string {
  if (language === "sv") {
    return `/parti/${component}`;
  }
  return `/en/party/${component}`;
}

function basePage(family: Family, language: Language): Bootstrap {
  const path = PATHS[language][family];
  return {
    language,
    locale: locale(language),
    route: { family, path, parameter: parameter(family) },
    alternates: { sv: PATHS.sv[family], en: PATHS.en[family] },
    navigation: FAMILIES.filter((entry) => entry !== PARTY).map((entry) => ({
      family: entry,
      path: PATHS[language][entry],
      current: entry === family,
    })),
    site: { name: siteName(language), origin: "https://www.swedishpolls.se" },
    partyPaths: Object.fromEntries(
      COMPONENTS.map((component) => [component, partyPath(language, component)]),
    ),
    headlineDate: latest.lastFieldworkDate,
    publication,
    api: {
      base: "/api/v1",
      publication: publicationResponse.publicationId,
      language,
    },
    approximatedElection: seats.allocationRule.electionYear,
  };
}

function pageFixture(family: Family, language: Language = "sv"): Bootstrap {
  const page = basePage(family, language);
  if (family === POLLS) {
    return { ...page, pollTable: pollTable() };
  }
  if (family === POLLSTERS) {
    return { ...page, data: { pollsters: pollsters() } };
  }
  if (family === METHOD) {
    return { ...page, data: { latest, method } };
  }
  if (family === PARTY) {
    return { ...page, data: { ...results, party: party() } };
  }
  return { ...page, data: results };
}

const TEST_ROUTES = LANGUAGES.flatMap((language) =>
  FAMILIES.map((family) => PATHS[language][family]),
);

function pageFixtureForPath(path: string): Bootstrap {
  let language: Language = "sv";
  if (path.startsWith("/en")) {
    language = "en";
  }
  const family = FAMILIES.find((entry) => PATHS[language][entry] === path) ?? OVERVIEW;
  return pageFixture(family, language);
}

export { pageFixture, pageFixtureForPath, TEST_ROUTES };

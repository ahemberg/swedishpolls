import type {
  InstitutesResponse,
  LatestResponse,
  MethodResponse,
  PublicationResponse,
  SeatsResponse,
} from "./api";
import type { CoalitionsResponse } from "./api-coalitions";
import type { Bootstrap, ResultsPageData } from "./bootstrap";
import { COALITIONS, METHOD, OVERVIEW, PARTY, POLLS, POLLSTERS, translator } from "./bootstrap";
import type { Coalition, SeatsParty } from "./chamber";
import { coalitionPage } from "./coalition-page";
import { publishedPolls, sourcePage } from "./poll-page";
import { overview, pollsters, type ReadDocument } from "./result-page";
import { path, routePage } from "./routes";
import { fetchJson, RequestError } from "./useFetched";

function bounds(values: readonly number[]): readonly [number, number] {
  const [lower, upper] = values;
  if (lower === undefined || upper === undefined || values.length !== 2) {
    throw new Error("Invalid seat interval");
  }
  return [lower, upper];
}

async function loadPage(page: Bootstrap, url: URL, signal: AbortSignal): Promise<Bootstrap> {
  if (page.route.family === POLLS && !url.searchParams.has("publication")) {
    return sourceOnly(page, url, signal);
  }
  const requested = url.searchParams.get("publication");
  let endpoint = "/api/v1/publication";
  if (requested !== null) {
    endpoint = `/api/v1/publications/${encodeURIComponent(requested)}`;
  }
  let publication: PublicationResponse;
  try {
    publication = await fetchJson<PublicationResponse>(
      `${endpoint}?language=${page.language}`,
      signal,
    );
  } catch (error) {
    if (
      !(error instanceof RequestError) ||
      error.body.code !== "estimates_unavailable" ||
      requested !== null
    ) {
      throw error;
    }
    const route = routePage(new URL(path(OVERVIEW, page.language), url));
    if (route === undefined) {
      throw error;
    }
    return sourceOnly(route, url, signal);
  }
  const read: ReadDocument = <T>(
    resource: string,
    parameters: Readonly<Record<string, string>> = {},
  ) => {
    const query = new URLSearchParams({
      ...parameters,
      publication: publication.publicationId,
      language: page.language,
    });
    return fetchJson<T>(`/api/v1/${resource}?${query}`, signal);
  };
  const resolved: Bootstrap = {
    ...page,
    publication,
    permanent: requested !== null,
    headlineDate: publication.lastFieldworkDate,
    api: { base: "/api/v1", publication: publication.publicationId, language: page.language },
  };
  return publishedPage(resolved, url, signal, read);
}

async function sourceOnly(page: Bootstrap, url: URL, signal: AbortSignal): Promise<Bootstrap> {
  const source = await sourcePage(page, url, signal);
  return {
    ...source,
    navigation: source.navigation.filter(
      (entry) => entry.family === OVERVIEW || entry.family === POLLS,
    ),
  };
}

async function chamber(
  page: Bootstrap,
  latest: LatestResponse,
  read: ReadDocument,
): Promise<ResultsPageData> {
  const [seats, coalitions] = await Promise.all([
    read<SeatsResponse>("seats"),
    read<CoalitionsResponse>("coalitions"),
  ]);
  return {
    latest,
    seats: {
      ...seats,
      allocationRule: { ...seats.allocationRule, tieNote: translator(page)("seats.tie") },
      parties: seats.parties.map(
        (party): SeatsParty => ({ ...party, seatInterval: bounds(party.seatInterval) }),
      ),
    },
    coalitions: {
      ...coalitions,
      coalitions: coalitions.coalitions.map(
        (coalition): Coalition => ({ ...coalition, seatInterval: bounds(coalition.seatInterval) }),
      ),
    },
  };
}

async function publishedPage(
  page: Bootstrap,
  url: URL,
  signal: AbortSignal,
  read: ReadDocument,
): Promise<Bootstrap> {
  if (page.route.family === POLLSTERS) {
    return { ...page, data: { pollsters: await pollsters(read) } };
  }
  const latest = await read<LatestResponse>("estimates/latest");
  if (page.route.family === METHOD) {
    const method = await read<MethodResponse>("method");
    return {
      ...page,
      headlineDate: latest.lastFieldworkDate,
      approximatedElection: method.approximatedElection,
      data: { latest, method },
    };
  }
  if (page.route.family === POLLS) {
    const institutes = await read<InstitutesResponse>("institutes");
    return publishedPolls({
      page,
      url,
      periods: latest.coveragePeriods,
      institutes: institutes.institutes.map((institute) => institute.institute),
      signal,
    });
  }
  const data = await chamber(page, latest, read);
  const loaded: Bootstrap = {
    ...page,
    headlineDate: latest.lastFieldworkDate,
    approximatedElection: data.seats.allocationRule.electionYear,
    data,
  };
  if (page.route.family === OVERVIEW || page.route.family === PARTY) {
    return overview({ page: loaded, data, read, url, signal });
  }
  if (page.route.family === COALITIONS) {
    return coalitionPage(loaded, data, url, signal);
  }
  return loaded;
}

export { loadPage };

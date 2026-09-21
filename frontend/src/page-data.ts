import type { Bootstrap, Elections, History, Latest, PartyData, Polls } from "./bootstrap";
import type { CoalitionResults, Seats } from "./chamber";
import type { CoalitionHistoryData } from "./coalition-history";
import type { PollstersData } from "./institutes";
import type { MethodData } from "./method";

interface ResultsPageData {
  readonly coalitionHistory?: CoalitionHistoryData;
  readonly latest: Latest;
  readonly seats: Seats;
  readonly coalitions: CoalitionResults;
  readonly elections?: Elections;
  readonly history?: History;
  readonly polls?: Polls;
  readonly party?: PartyData;
}

interface PollstersPageData {
  readonly pollsters: PollstersData;
}

interface MethodPageData {
  readonly latest: Latest;
  readonly method: MethodData;
}

type PageData = ResultsPageData | PollstersPageData | MethodPageData;

function isResultsData(data: PageData): data is ResultsPageData {
  return "seats" in data;
}

function resultsData(page: Bootstrap): ResultsPageData | undefined {
  if (page.data !== undefined && isResultsData(page.data)) {
    return page.data;
  }
  return undefined;
}

function pollstersData(page: Bootstrap): PollstersPageData | undefined {
  if (page.data !== undefined && "pollsters" in page.data) {
    return page.data;
  }
  return undefined;
}

function methodData(page: Bootstrap): MethodPageData | undefined {
  if (page.data !== undefined && "method" in page.data) {
    return page.data;
  }
  return undefined;
}

function latestData(page: Bootstrap): Latest | undefined {
  if (page.data !== undefined && "latest" in page.data) {
    return page.data.latest;
  }
  return undefined;
}

export type { PageData, ResultsPageData };
export { isResultsData, latestData, methodData, pollstersData, resultsData };

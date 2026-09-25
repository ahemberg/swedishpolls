import coalitions from "../../src/main/resources/api/v1/examples/coalitions.json" with {
  type: "json",
};
import elections from "../../src/main/resources/api/v1/examples/elections.json" with {
  type: "json",
};
import history from "../../src/main/resources/api/v1/examples/estimates-history.json" with {
  type: "json",
};
import latest from "../../src/main/resources/api/v1/examples/estimates-latest.json" with {
  type: "json",
};
import institutes from "../../src/main/resources/api/v1/examples/institutes.json" with {
  type: "json",
};
import method from "../../src/main/resources/api/v1/examples/method.json" with { type: "json" };
import polls from "../../src/main/resources/api/v1/examples/polls.json" with { type: "json" };
import publication from "../../src/main/resources/api/v1/examples/publication.json" with {
  type: "json",
};
import seats from "../../src/main/resources/api/v1/examples/seats.json" with { type: "json" };

const sourcePolls = {
  ...polls,
  total: polls.polls.length,
  snapshot: { id: 42, sha256: "source-hash", capturedAt: "2026-09-05T12:00:00Z" },
  filters: { ...polls.filters, party: [], coveragePeriod: null },
  institutes: ["Skop"],
  invalid: [],
  csv: "/source/polls.csv?snapshot=42",
};

const documents = new Map<string, unknown>([
  ["/api/v1/publication", publication],
  ["/api/v1/source/polls", sourcePolls],
  [`/api/v1/publications/${publication.publicationId}`, publication],
  ["/api/v1/estimates/latest", latest],
  ["/api/v1/estimates/history", history],
  ["/api/v1/seats", seats],
  ["/api/v1/coalitions", coalitions],
  ["/api/v1/elections", elections],
  ["/api/v1/institutes", { ...institutes, electionCycles: [institutes.electionCycle] }],
  ["/api/v1/method", method],
  [
    "/api/v1/polls",
    {
      ...polls,
      total: polls.polls.length,
      filters: {
        ...polls.filters,
        party: Object.keys(polls.polls.at(0)?.shares ?? {}),
        coveragePeriod: null,
      },
    },
  ],
]);

function apiFixture(url: URL): { readonly body: unknown; readonly status: number } {
  const body = documents.get(url.pathname);
  if (body !== undefined) {
    return { body, status: 200 };
  }
  if (url.pathname.endsWith("/coalition-history")) {
    return { body: { code: "feature_unavailable" }, status: 409 };
  }
  if (url.pathname === "/api/v1/source/polls" || url.pathname === "/source/chart") {
    return { body: { code: "unknown_route" }, status: 404 };
  }
  throw new Error(`Unexpected API request ${url}`);
}

export { apiFixture };

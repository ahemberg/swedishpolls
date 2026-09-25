import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import coalitions from "../../src/main/resources/api/v1/examples/coalitions.json";
import latest from "../../src/main/resources/api/v1/examples/estimates-latest.json";
import polls from "../../src/main/resources/api/v1/examples/polls.json";
import publication from "../../src/main/resources/api/v1/examples/publication.json";
import seats from "../../src/main/resources/api/v1/examples/seats.json";
import { App } from "./App";
import { apiFixture } from "./api-test-fixtures";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it("loads a direct seat page from one publication and localizes its tie rule", async () => {
  globalThis.history.replaceState(null, "", "/en/seats");
  const documents = new Map<string, unknown>([
    ["/api/v1/publication", publication],
    ["/api/v1/estimates/latest", latest],
    ["/api/v1/seats", seats],
    ["/api/v1/coalitions", coalitions],
  ]);
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string) => {
      const url = new URL(input, globalThis.location.origin);
      if (url.pathname !== "/api/v1/publication") {
        expect(url.searchParams.get("publication")).toBe(publication.publicationId);
      }
      expect(url.searchParams.get("language")).toBe("en");
      const body = documents.get(url.pathname);
      if (body === undefined) {
        throw new Error(`Unexpected request ${url}`);
      }
      return Promise.resolve(Response.json(body));
    }),
  );
  render(<App />);
  expect(screen.getByRole("status")).toHaveTextContent("Loading");
  const table = await screen.findByRole("table", { name: /Integer seats, posterior mean/ });
  expect(within(table).getByRole("row", { name: /Social Democrats/ })).toHaveTextContent("96");
  expect(screen.getByText(/An exact quotient tie/)).toBeVisible();
  expect(screen.getByRole("link", { name: "SV" })).toHaveAttribute("href", "/mandat");
});

it("shows current source polls without requesting a publication on the poll page", async () => {
  globalThis.history.replaceState(null, "", "/en/polls");
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string) => {
      const url = new URL(input, globalThis.location.origin);
      if (url.pathname === "/api/v1/source/polls") {
        return Promise.resolve(
          Response.json({
            snapshot: { id: 42, sha256: "source-hash", capturedAt: "2026-09-05T12:00:00Z" },
            ...polls,
            filters: { ...polls.filters, party: [], coveragePeriod: null },
            institutes: ["Skop"],
            invalid: [],
            csv: "/source/polls.csv?snapshot=42",
          }),
        );
      }
      if (url.pathname === "/source/chart" && url.searchParams.get("snapshot") === "42") {
        return Promise.resolve(Response.json({ code: "unknown_route" }, { status: 404 }));
      }
      throw new Error(`Unexpected request ${url}`);
    }),
  );
  render(<App />);
  expect(await screen.findByRole("heading", { name: "Collected polls" })).toBeVisible();
  expect(screen.getByRole("row", { name: /Skop/ })).toHaveTextContent("29.6");
  expect(screen.getByRole("link", { name: /Filtered polls \(CSV\)/ })).toHaveAttribute(
    "href",
    "/source/polls.csv?snapshot=42&language=en",
  );
  expect(screen.queryByRole("link", { name: "Seats" })).not.toBeInTheDocument();
});

it.each([
  ["/en", /Estimated voter support as of/],
  ["/en/party/social-democrats", /^Social Democrats$/],
  ["/en/pollsters", /Pollsters and house effects/],
  ["/en/method", /Method and validation/],
  ["/en/coalitions", /Coalitions/],
  [`/en/polls?publication=${publication.publicationId}`, /Published polls/],
])("loads the data for %s from its API documents", async (path, heading) => {
  globalThis.history.replaceState(null, "", path);
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string) => {
      const fixture = apiFixture(new URL(input, globalThis.location.origin));
      return Promise.resolve(Response.json(fixture.body, { status: fixture.status }));
    }),
  );
  render(<App />);
  expect(await screen.findByRole("heading", { name: heading, level: 1 })).toBeVisible();
});

const UNAVAILABLE = 503;
const NOT_FOUND = 404;
const SERVER_ERROR = 500;

it.each([
  ["/en/seats", "estimates_unavailable", UNAVAILABLE, "No polls available"],
  [
    "/en/seats?publication=missing",
    "unknown_publication",
    NOT_FOUND,
    "This publication is unavailable.",
  ],
  ["/en/seats", "server_error", SERVER_ERROR, "The page could not be loaded."],
  ["/en/missing", "unused", SERVER_ERROR, "Page not found."],
])("renders a visible state for %s and %s", async (path, code, status, heading) => {
  globalThis.history.replaceState(null, "", path);
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string) => {
      if (input.startsWith("/api/v1/source/polls")) {
        return Promise.resolve(Response.json({ code: "unknown_route" }, { status: 404 }));
      }
      return Promise.resolve(Response.json({ code }, { status }));
    }),
  );
  render(<App />);
  expect(await screen.findByRole("heading", { name: heading })).toBeVisible();
});

it("names a rejected poll filter while keeping the publication and valid institute", async () => {
  globalThis.history.replaceState(
    null,
    "",
    `/en/polls?publication=${publication.publicationId}&from=bad&institute=Skop`,
  );
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string) => {
      const url = new URL(input, globalThis.location.origin);
      if (url.pathname === "/api/v1/polls") {
        expect(url.searchParams.get("publication")).toBe(publication.publicationId);
        expect(url.searchParams.get("institute")).toBe("Skop");
        if (url.searchParams.has("from")) {
          return Promise.resolve(
            Response.json(
              { code: "invalid_filter", invalid: [{ name: "from", reason: "not_a_date" }] },
              { status: 400 },
            ),
          );
        }
      }
      const fixture = apiFixture(url);
      return Promise.resolve(Response.json(fixture.body, { status: fixture.status }));
    }),
  );
  render(<App />);
  expect(await screen.findByRole("alert")).toHaveTextContent("The from filter could not be read");
  expect(screen.getByRole("row", { name: /Skop/ })).toHaveTextContent("29.6");
  expect(screen.getByRole("combobox", { name: "Party" })).toHaveValue("");
});

it("shows a malformed coalition link instead of silently substituting the default selection", async () => {
  globalThis.history.replaceState(
    null,
    "",
    `/en/coalitions?publication=${publication.publicationId}&a=S&parties=M`,
  );
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string) => {
      const fixture = apiFixture(new URL(input, globalThis.location.origin));
      return Promise.resolve(Response.json(fixture.body, { status: fixture.status }));
    }),
  );
  render(<App />);
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Do not mix parties with the a and b parameters.",
  );
  expect(within(screen.getByRole("alert")).getByRole("link")).toHaveAttribute(
    "href",
    `/en/coalitions?publication=${publication.publicationId}`,
  );
});

it("loads every page of a party's source observations", async () => {
  globalThis.history.replaceState(
    null,
    "",
    `/en/party/social-democrats?publication=${publication.publicationId}`,
  );
  const [row] = polls.polls;
  const reportedShare = 30.2;
  if (row === undefined) {
    throw new Error("Missing poll example");
  }
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string) => {
      const url = new URL(input, globalThis.location.origin);
      if (url.pathname === "/api/v1/polls" && url.searchParams.get("party") === "S") {
        expect(url.searchParams.get("includeExcluded")).toBe("true");
        expect(url.searchParams.get("publication")).toBe(publication.publicationId);
        const number = Number(url.searchParams.get("page") ?? "1");
        return Promise.resolve(
          Response.json({
            ...polls,
            total: 2,
            pageSize: 1,
            page: number,
            polls: [
              {
                ...row,
                pollId: `row-${number}`,
                institute: `Institute ${number}`,
                shares: Object.fromEntries([["S", reportedShare]]),
              },
            ],
          }),
        );
      }
      const fixture = apiFixture(url);
      return Promise.resolve(Response.json(fixture.body, { status: fixture.status }));
    }),
  );
  render(<App />);
  expect(await screen.findByRole("row", { name: /Institute 2/ })).toHaveTextContent("30.2%");
  expect(screen.getByRole("row", { name: /Institute 1/ })).toBeVisible();
});

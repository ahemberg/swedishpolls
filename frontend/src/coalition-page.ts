import type { Bootstrap, ResultsPageData } from "./bootstrap";
import type { CoalitionHistoryData, CoalitionLinkError } from "./coalition-history";
import { coalitionSharePath, groupsFor, PRESET } from "./coalition-history";
import { named, translate } from "./text";
import { fetchJson, RequestError } from "./useFetched";

const BAD_REQUEST = 400;

function validateSelection(query: URLSearchParams): void {
  for (const name of ["a", "b", "parties", "from", "to"]) {
    if (query.getAll(name).length > 1) {
      throw new RequestError(BAD_REQUEST, {
        code: "invalid_filter",
        field: name,
        message: "repeated_parameter",
      });
    }
  }
  const modern = query.has("a") || query.has("b");
  if (modern && query.has("parties")) {
    throw new RequestError(BAD_REQUEST, {
      code: "invalid_filter",
      field: "parties",
      message: "mixed_parameters",
    });
  }
}

function selectionQuery(query: URLSearchParams): URLSearchParams {
  validateSelection(query);
  const result = new URLSearchParams();
  const modern = query.has("a") || query.has("b");
  const preset = groupsFor(PRESET);
  let a = preset.a.join(",");
  let b = preset.b.join(",");
  if (modern) {
    a = query.get("a") ?? "";
    b = query.get("b") ?? "";
  } else if (query.has("parties")) {
    a = query.get("parties") ?? "";
    b = "";
  }
  result.set("a", a);
  result.set("b", b);
  result.set("step", "7");
  for (const name of ["from", "to"]) {
    const value = query.get(name);
    if (value !== null) {
      result.set(name, value);
    }
  }
  return result;
}

async function coalitionPage(
  page: Bootstrap,
  data: ResultsPageData,
  url: URL,
  signal: AbortSignal,
): Promise<Bootstrap> {
  try {
    const query = selectionQuery(url.searchParams);
    const history = await fetchJson<CoalitionHistoryData>(
      `/api/v1/publications/${encodeURIComponent(page.api?.publication ?? "")}/coalition-history?${query}`,
      signal,
    );
    const { selection } = history;
    const preset = groupsFor(PRESET);
    const share = coalitionSharePath(page.route.path, selection, history.requestedRange);
    const pin = url.searchParams.get("publication");
    let suffix = "";
    if (pin !== null) {
      suffix = `&publication=${encodeURIComponent(pin)}`;
    }
    return {
      ...page,
      data: { ...data, coalitionHistory: history },
      coalitionShare: share + suffix,
      route: { ...page.route, path: share + suffix },
      alternates: {
        sv: coalitionSharePath(page.alternates.sv, selection, history.requestedRange) + suffix,
        en: coalitionSharePath(page.alternates.en, selection, history.requestedRange) + suffix,
      },
      customCoalitionSelection:
        selection.a.join(",") !== preset.a.join(",") ||
        selection.b.join(",") !== preset.b.join(","),
    };
  } catch (error) {
    if (!(error instanceof RequestError)) {
      throw error;
    }
    if (error.body.code === "feature_unavailable") {
      return { ...page, data };
    }
    if (error.body.code !== "invalid_filter") {
      throw error;
    }
    let reset = page.route.path;
    if (page.permanent) {
      reset += `?publication=${encodeURIComponent(page.api?.publication ?? "")}`;
    }
    const failure: CoalitionLinkError = {
      field: error.body.field ?? "selection",
      reason: named(
        page.language,
        `coalitionHistory.invalid.${error.body.message}`,
        translate(page.language, "page.invalidFilter"),
      ),
      reset,
      requested: Object.fromEntries(
        ["a", "b", "parties", "from", "to"]
          .filter((name) => url.searchParams.has(name))
          .map((name) => [name, url.searchParams.getAll(name)]),
      ),
    };
    return { ...page, data, customCoalitionSelection: true, coalitionLinkError: failure };
  }
}

export { coalitionPage };

import type { Bootstrap, History, RangeSpec } from "./bootstrap";
import { useFetched } from "./useFetched";

/**
 * The history for the selected range.
 *
 * The bootstrap already carries the default range, so the first paint fetches nothing. Every later
 * range is fetched with the page's own publication in the query, which is what keeps a range change
 * on the same publication even when a newer one has been published meanwhile.
 */

interface Loaded {
  readonly history: History | undefined;
  readonly loading: boolean;
  readonly failed: boolean;
}

/** One range's history request, or null where the page has no publication API to ask. */
function query(page: Bootstrap, range: RangeSpec): string | null {
  const { api } = page;
  if (api === undefined) {
    return null;
  }
  const parameters = new URLSearchParams({
    publication: api.publication,
    language: api.language,
    from: range.from,
    to: range.to,
    step: String(range.step),
  });
  return `${api.base}/estimates/history?${parameters.toString()}`;
}

/** A range worth fetching: one the page offers and is not already carrying. */
function offered(page: Bootstrap, rangeId: string): RangeSpec | undefined {
  if (rangeId === page.defaultRange) {
    return undefined;
  }
  return (page.ranges ?? []).find((entry) => entry.id === rangeId);
}

function request(page: Bootstrap, rangeId: string): string | null {
  const range = offered(page, rangeId);
  if (range === undefined) {
    return null;
  }
  return query(page, range);
}

function useHistory(page: Bootstrap, rangeId: string): Loaded {
  const { value, loading, failed } = useFetched(page.data?.history, request(page, rangeId));
  return { history: value, loading, failed };
}

export type { Loaded };
export { useHistory };

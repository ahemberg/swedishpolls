import { useEffect, useState } from "react";
import type { Bootstrap, History, RangeSpec } from "./bootstrap";

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

function query(page: Bootstrap, range: RangeSpec): string {
  const { api } = page;
  if (api === undefined) {
    return "";
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

function useHistory(page: Bootstrap, rangeId: string): Loaded {
  const initial = page.data?.history;
  const isDefault = rangeId === page.defaultRange;
  const range = (page.ranges ?? []).find((entry) => entry.id === rangeId);
  const [history, setHistory] = useState<History | undefined>(initial);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (isDefault || range === undefined || page.api === undefined) {
      setHistory(initial);
      setFailed(false);
      return;
    }
    const aborter = new AbortController();
    setLoading(true);
    setFailed(false);
    fetch(query(page, range), { signal: aborter.signal })
      .then((response) => {
        if (!response.ok) {
          throw new Error(rangeId);
        }
        return response.json() as Promise<History>;
      })
      .then((loaded) => {
        setHistory(loaded);
        setLoading(false);
      })
      .catch(() => {
        if (!aborter.signal.aborted) {
          setFailed(true);
          setLoading(false);
        }
      });
    return () => aborter.abort();
  }, [page, range, rangeId, isDefault, initial]);

  return { history, loading, failed };
}

export type { Loaded };
export { useHistory };

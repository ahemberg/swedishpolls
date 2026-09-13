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

async function historyJson<T>(url: string, signal: AbortSignal): Promise<T> {
  const response = await fetch(url, { signal });
  if (!response.ok) {
    throw new Error(`History request failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
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
    historyJson<History>(query(page, range), aborter.signal)
      .then((loaded) => {
        if (!aborter.signal.aborted) {
          setHistory(loaded);
          setLoading(false);
        }
      })
      .catch(() => {
        if (!aborter.signal.aborted) {
          setFailed(true);
          setLoading(false);
        }
      });
    return () => aborter.abort();
  }, [page, range, isDefault, initial]);

  return { history, loading, failed };
}

export type { Loaded };
export { historyJson, useHistory };

import { useEffect, useState } from "react";
import type { Bootstrap } from "./bootstrap";
import type { PollFilterState, PollTable } from "./poll-table";
import { pollQuery } from "./poll-table";

/**
 * One filtered page of the pinned snapshot.
 *
 * The server already answered the request the reader arrived with, so the first paint fetches
 * nothing. Every later request carries the publication this page resolved, which is what keeps a
 * filter change on the snapshot the table started from even when a corrected one publishes while
 * the reader is still choosing.
 */

interface Loaded {
  readonly table: PollTable;
  readonly loading: boolean;
  readonly failed: boolean;
}

/** What the API returns for one poll request. It carries no options and no rejected filters. */
interface PollResponse {
  readonly total: number;
  readonly page: number;
  readonly pageSize: number;
  readonly csv: string;
  readonly labels: Readonly<Record<string, string>>;
  readonly polls: PollTable["polls"];
  readonly filters: PollFilterState;
}

function pages(total: number, pageSize: number): number {
  return Math.max(1, Math.ceil(total / pageSize));
}

/** The response in the table's own shape, with the options the page was already given. */
function merge(initial: PollTable, response: PollResponse): PollTable {
  return {
    ...initial,
    total: response.total,
    page: response.page,
    pageSize: response.pageSize,
    pages: pages(response.total, response.pageSize),
    csv: response.csv,
    columns: Object.keys(response.labels),
    filters: response.filters,
    invalid: [],
    polls: response.polls,
  };
}

function query(page: Bootstrap, filters: PollFilterState, pageNumber: number): string {
  const { api } = page;
  if (api === undefined) {
    return "";
  }
  const parameters = pollQuery(filters, pageNumber);
  parameters.set("publication", api.publication);
  parameters.set("language", api.language);
  return `${api.base}/polls?${parameters.toString()}`;
}

/** The filter and page the server already answered, which needs no request of its own. */
function served(initial: PollTable, filters: PollFilterState, pageNumber: number): boolean {
  return (
    pollQuery(initial.filters, initial.page).toString() ===
    pollQuery(filters, pageNumber).toString()
  );
}

function usePolls(
  page: Bootstrap,
  initial: PollTable,
  filters: PollFilterState,
  pageNumber: number,
): Loaded {
  const [table, setTable] = useState<PollTable>(initial);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const first = served(initial, filters, pageNumber);

  useEffect(() => {
    if (first || page.api === undefined) {
      setTable(initial);
      setFailed(false);
      return;
    }
    const aborter = new AbortController();
    setLoading(true);
    setFailed(false);
    fetch(query(page, filters, pageNumber), { signal: aborter.signal })
      .then((response) => {
        if (!response.ok) {
          throw new Error(String(response.status));
        }
        return response.json() as Promise<PollResponse>;
      })
      .then((response) => {
        setTable(merge(initial, response));
        setLoading(false);
      })
      .catch(() => {
        if (!aborter.signal.aborted) {
          setFailed(true);
          setLoading(false);
        }
      });
    return () => aborter.abort();
  }, [page, initial, filters, pageNumber, first]);

  return { table, loading, failed };
}

export type { Loaded };
export { usePolls };

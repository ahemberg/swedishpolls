import { useEffect, useState } from "react";

/**
 * One document fetched for the selection a chart is showing.
 *
 * A null URL means the page already carries the document, which is how the first paint of every
 * chart fetches nothing: the server wrote the opening selection into the bootstrap. Each fetch
 * aborts when the selection changes again, so a slow answer for a selection the reader has left
 * cannot overwrite the one they are looking at.
 */

interface Fetched<T> {
  readonly value: T;
  readonly loading: boolean;
  readonly failed: boolean;
}

interface ApiFailure {
  readonly code: string;
  readonly message: string;
  readonly field?: string;
  readonly invalid?: readonly { readonly name: string; readonly reason: string }[];
}

class RequestError extends Error {
  readonly status: number;
  readonly body: ApiFailure;
  constructor(status: number, body: ApiFailure) {
    super(`Request failed: ${status}. ${body.message ?? body.code}`);
    this.status = status;
    this.body = body;
  }
}

async function fetchJson<T>(url: string, signal: AbortSignal): Promise<T> {
  const response = await fetch(url, { signal });
  if (!response.ok) {
    const body: ApiFailure = await response.json();
    throw new RequestError(response.status, body);
  }
  return response.json() as Promise<T>;
}

function useFetched<T>(initial: T, url: string | null): Fetched<T> {
  const [value, setValue] = useState<T>(initial);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (url === null) {
      setValue(initial);
      setFailed(false);
      return;
    }
    const aborter = new AbortController();
    setLoading(true);
    setFailed(false);
    fetchJson<T>(url, aborter.signal)
      .then((loaded) => {
        if (!aborter.signal.aborted) {
          setValue(loaded);
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
  }, [initial, url]);

  return { value, loading, failed };
}

export type { Fetched };
export { fetchJson, RequestError, useFetched };

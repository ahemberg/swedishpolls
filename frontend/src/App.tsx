import { type JSX, useEffect, useState } from "react";
import type { Bootstrap, Language } from "./bootstrap";
import { translator } from "./bootstrap";
import { loadPage } from "./load-page";
import { routePage } from "./routes";
import { Shell } from "./Shell";
import { named, translate } from "./text";
import { RequestError } from "./useFetched";

type Loaded = { readonly page: Bootstrap } | { readonly error: string } | null;

function Page({ url }: { readonly url: URL }): JSX.Element {
  const [loaded, setLoaded] = useState<Loaded>(null);
  useEffect(() => {
    setLoaded(null);
    const page = routePage(url);
    if (page === undefined) {
      setLoaded({ error: "not-found" });
      return;
    }
    const controller = new AbortController();
    document.documentElement.lang = page.language;
    loadPage(page, url, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) {
          if (value.route.family !== page.route.family) {
            globalThis.history.replaceState(null, "", value.route.path);
          }
          document.title = named(
            value.language,
            `head.title.${value.route.family.toLowerCase()}`,
            value.site.name,
          );
          setLoaded({ page: value });
        }
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          let code = "failed";
          if (error instanceof RequestError) {
            ({ code } = error.body);
          }
          setLoaded({ error: code });
        }
      });
    return () => controller.abort();
  }, [url]);
  let language: Language = "sv";
  if (url.pathname === "/en" || url.pathname.startsWith("/en/")) {
    language = "en";
  }
  if (loaded === null) {
    return (
      <main id="main">
        <p role="status">{translate(language, "page.loading")}</p>
      </main>
    );
  }
  if ("error" in loaded) {
    let message = translate(language, "page.failed");
    if (loaded.error === "unknown_publication") {
      message = translate(language, "page.unavailable");
    }
    if (loaded.error === "invalid_filter") {
      message = translate(language, "page.invalidFilter");
    }
    if (loaded.error === "not-found") {
      message = translate(language, "page.notFound");
    }
    return (
      <main id="main">
        <h1>{message}</h1>
        <a href={url.href}>{translate(language, "source.retry")}</a>
      </main>
    );
  }
  return <Shell page={loaded.page} t={translator(loaded.page)} />;
}

function App(): JSX.Element {
  const [url, setUrl] = useState(() => new URL(globalThis.location.href));
  useEffect(() => {
    const navigate = () => setUrl(new URL(globalThis.location.href));
    const click = (event: MouseEvent) => {
      if (
        event.defaultPrevented ||
        event.button !== 0 ||
        event.metaKey ||
        event.ctrlKey ||
        event.shiftKey ||
        event.altKey ||
        !(event.target instanceof Element)
      ) {
        return;
      }
      const link = event.target.closest<HTMLAnchorElement>("a[href]");
      if (link === null || link.hasAttribute("download") || link.target) {
        return;
      }
      const next = new URL(link.href);
      if (
        next.origin !== globalThis.location.origin ||
        next.hash ||
        routePage(next) === undefined
      ) {
        return;
      }
      event.preventDefault();
      globalThis.history.pushState(null, "", next);
      navigate();
    };
    globalThis.addEventListener("popstate", navigate);
    document.addEventListener("click", click);
    return () => {
      globalThis.removeEventListener("popstate", navigate);
      document.removeEventListener("click", click);
    };
  }, []);
  return <Page key={url.href} url={url} />;
}

export { App };

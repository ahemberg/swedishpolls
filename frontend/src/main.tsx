import { createRoot } from "react-dom/client";
import { MOUNT_ELEMENT, readBootstrap, translator } from "./bootstrap";
import { Shell } from "./Shell";
import "./site.css";

/**
 * The script mounts over the server's markup rather than hydrating it.
 *
 * Spring already rendered a complete page: the headline, its date and the results table, so a
 * reader without JavaScript is not reading a placeholder. This replaces that markup with the
 * interactive page, which is why the two never end up side by side: no result and no accessible
 * label is ever present twice.
 */

const mount = document.getElementById(MOUNT_ELEMENT);
if (mount === null) {
  throw new Error(`The page has no #${MOUNT_ELEMENT}`);
}

const page = readBootstrap();
createRoot(mount).render(<Shell page={page} t={translator(page)} />);

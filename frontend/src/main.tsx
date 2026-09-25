import { createRoot } from "react-dom/client";
import { App } from "./App";
import { MOUNT_ELEMENT } from "./bootstrap";
import "./site.css";

const mount = document.getElementById(MOUNT_ELEMENT);
if (mount === null) {
  throw new Error(`The page has no #${MOUNT_ELEMENT}`);
}
createRoot(mount).render(<App />);

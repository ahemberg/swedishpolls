import { hydrateRoot } from "react-dom/client";

const heading = "Svenska opinionsmätningar";
const placeholder = "Skattningar är ännu inte tillgängliga.";

const root = document.getElementById("root");
if (!root) {
  throw new Error("Missing application root");
}
hydrateRoot(
  root,
  <main>
    <h1>{heading}</h1>
    <p>{placeholder}</p>
  </main>,
);

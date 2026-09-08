import { hydrateRoot } from "react-dom/client";

const root = document.getElementById("root");
if (!root) throw new Error("Missing application root");
hydrateRoot(
  root,
  <main>
    <h1>Svenska opinionsmätningar</h1>
    <p>Skattningar är ännu inte tillgängliga.</p>
  </main>,
);

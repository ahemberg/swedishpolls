import assert from "node:assert/strict";
import test from "node:test";
import { parseBootstrap } from "../src/bootstrap-parser.ts";

const VALID = {
  language: "sv",
  locale: "sv-SE",
  route: { family: "OVERVIEW", path: "/", parameter: null },
  alternates: { sv: "/", en: "/en" },
  navigation: [],
  site: { name: "Poll of polls", origin: "https://example.test" },
  partyPaths: {},
};

test("the bootstrap parser accepts the required shell", () => {
  assert.deepEqual(parseBootstrap(JSON.stringify(VALID)), VALID);
});

test("the bootstrap parser rejects a shape mismatch", () => {
  assert.throws(
    () => parseBootstrap(JSON.stringify({ ...VALID, route: { ...VALID.route, family: "NEW" } })),
    /Invalid page bootstrap/,
  );
});

test("the bootstrap parser rejects malformed optional data", () => {
  assert.throws(
    () => parseBootstrap(JSON.stringify({ ...VALID, publication: "latest" })),
    /Invalid page bootstrap/,
  );
  assert.throws(
    () => parseBootstrap(JSON.stringify({ ...VALID, data: { latest: {}, seats: {} } })),
    /Invalid page bootstrap/,
  );
  assert.throws(
    () => parseBootstrap(JSON.stringify({ ...VALID, alternates: { sv: "/" } })),
    /Invalid page bootstrap/,
  );
  assert.throws(
    () => parseBootstrap(JSON.stringify({ ...VALID, defaultRange: "week" })),
    /Invalid page bootstrap/,
  );
});

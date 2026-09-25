import { expect, test } from "@playwright/test";
import { apiFixture } from "../src/api-test-fixtures";
import { TEST_ROUTES } from "../src/test-fixtures";

test.beforeEach(async ({ page }) => {
  await page.route(
    (url) => url.pathname.startsWith("/api/v1/") || url.pathname === "/source/chart",
    async (route) => {
      const response = apiFixture(new URL(route.request().url()));
      await route.fulfill({ status: response.status, json: response.body });
    },
  );
});

test("thePageMountsTheScriptOverItsOwnMarkupRatherThanBesideIt", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByTestId("server-markup")).toHaveCount(0);
  await expect(
    page.getByRole("heading", { name: /Skattat väljarstöd per 5 september 2026/ }),
  ).toBeVisible();
  const estimates = page.getByRole("table", { name: /Skattat väljarstöd per parti/ });
  await expect(estimates.getByRole("row", { name: /Socialdemokraterna/ })).toContainText("27,0 %");
});

test("theApprovedRoutesResolve", async ({ page }) => {
  for (const route of TEST_ROUTES) {
    await page.goto(route);
    await expect(page.locator("main h1")).toBeVisible();
    await expect(page.locator("header.site")).toBeVisible();
  }
});

test("theNavigationAndLanguageSwitchLinkToTranslatedPathsOnly", async ({ page }) => {
  await page.goto("/mandat");

  await page.getByRole("link", { name: "EN", exact: true }).click();
  await expect(page).toHaveURL("/en/seats");
  await expect(page.getByRole("heading", { name: /National seat approximation/ })).toBeVisible();
  await expect(page.getByRole("link", { name: "SV", exact: true })).toHaveAttribute(
    "href",
    "/mandat",
  );
});

test("client navigation keeps React mounted and back restores the route", async ({ page }) => {
  await page.goto("/en/seats");
  await expect(page.locator("header.site")).toBeVisible();
  await page.evaluate(() => {
    document.body.dataset.navigationSentinel = "mounted";
  });
  await page.getByRole("link", { name: "Method", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Method and validation" })).toBeVisible();
  await expect(page.locator("body")).toHaveAttribute("data-navigation-sentinel", "mounted");
  await page.goBack();
  await expect(page.getByRole("heading", { name: /National seat approximation/ })).toBeVisible();
});

test("a failed API request offers visible retry content", async ({ page }) => {
  await page.route("**/api/v1/seats?**", (route) => route.abort());
  await page.goto("/en/seats");
  await expect(page.getByRole("heading", { name: "The page could not be loaded." })).toBeVisible();
  await expect(page.getByRole("link", { name: "Retry" })).toBeVisible();
});

test("source-only pages remain usable without a publication", async ({ page }) => {
  await page.route("**/api/v1/publication?**", (route) =>
    route.fulfill({ status: 503, json: { code: "estimates_unavailable" } }),
  );
  await page.goto("/en");
  await expect(page.getByRole("heading", { name: "Opinion polls" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Seats", exact: true })).toHaveCount(0);
});

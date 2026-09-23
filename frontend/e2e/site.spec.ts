import { expect, test } from "@playwright/test";
import { TEST_ROUTES } from "../src/test-fixtures";

test("thePageMountsTheScriptOverItsOwnMarkupRatherThanBesideIt", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByTestId("server-markup")).toHaveCount(0);
  await expect(
    page.getByRole("heading", { name: /Skattat väljarstöd per 5 september 2026/ }),
  ).toBeVisible();
  const estimates = page.getByRole("table", { name: /Skattat väljarstöd per parti/ });
  await expect(estimates.getByRole("row", { name: /Socialdemokraterna/ })).toContainText("27,0 %");
});

test("theApprovedRoutesResolve", async ({ request }) => {
  for (const route of TEST_ROUTES) {
    const response = await request.get(route);
    expect(response.ok()).toBe(true);
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

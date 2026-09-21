import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import {
  COALITIONS,
  METHOD,
  OVERVIEW,
  PARTY,
  POLLS,
  POLLSTERS,
  SEATS,
  translator,
} from "./bootstrap";
import { Shell } from "./Shell";
import { pageFixture } from "./test-fixtures";

afterEach(cleanup);

const COALITION_ROWS_WITH_HEADER = 11;
const SEATS_NOTE =
  "A national seat approximation from national shares. It omits constituency rules, including the return of excess fixed seats, and is not an official allocation.";

function renderPage(family: Parameters<typeof pageFixture>[0], language: "sv" | "en" = "sv") {
  const page = pageFixture(family, language);
  render(<Shell page={page} t={translator(page)} />);
}

describe("PageIT overview content ports", () => {
  it("theOverviewCarriesItsHeadlineDateAndResultsTableBeforeAnyScriptRuns", () => {
    renderPage(OVERVIEW);

    expect(
      screen.getByRole("heading", { name: /Skattat väljarstöd per 5 september 2026/ }),
    ).toBeVisible();
    const estimates = screen.getByRole("table", { name: /Skattat väljarstöd per parti/ });
    const socialDemocrats = within(estimates).getByRole("row", { name: /Socialdemokraterna/ });
    expect(socialDemocrats).toHaveTextContent("27,0 %");
    expect(socialDemocrats).toHaveTextContent("96");
    expect(screen.getByRole("heading", { name: "Blockläget" })).toBeVisible();
  });

  it("theEnglishOverviewSaysTheSameThingInEnglish", () => {
    renderPage(OVERVIEW, "en");

    expect(
      screen.getByRole("heading", { name: /Estimated voter support as of 5 September 2026/ }),
    ).toBeVisible();
    const estimates = screen.getByRole("table", { name: /Estimated voter support per party/ });
    expect(within(estimates).getByRole("row", { name: /Social Democrats/ })).toHaveTextContent(
      "27.0%",
    );
    expect(screen.getByRole("heading", { name: "Bloc standings" })).toBeVisible();
  });
});

describe("PageIT party and seat content ports", () => {
  it("aCurrentPartyPageCarriesOnePartyThroughHtmlChartsEffectsAndDownloads", () => {
    renderPage(PARTY);

    expect(screen.getByRole("heading", { name: "Socialdemokraterna" })).toBeVisible();
    expect(screen.getByText("27,0 %")).toBeVisible();
    expect(screen.getByRole("row", { name: /Skop/ })).toHaveTextContent("29,6 %");
    expect(screen.getByRole("heading", { name: "Huseffekter" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Mätningar (CSV)" })).toHaveAttribute(
      "href",
      expect.stringContaining("party=S"),
    );
  });

  it("theSeatsPageSeparatesPointSeatsFromPosteriorMeansAndIntervals / theSeatsPageExplainsTheApproximationItPublishes", () => {
    const page = pageFixture(SEATS);
    const t = translator(page);
    render(<Shell page={page} t={t} />);

    expect(screen.getByRole("heading", { name: /Approximerad mandatfördelning/ })).toBeVisible();
    const table = screen.getByRole("table", { name: /Heltalsmandat/ });
    const socialDemocrats = within(table).getByRole("row", { name: /Socialdemokraterna/ });
    expect(socialDemocrats).toHaveTextContent("96");
    expect(socialDemocrats).toHaveTextContent("95,7");
    expect(socialDemocrats).toHaveTextContent("89–102");
    expect(screen.getAllByText(SEATS_NOTE)).not.toHaveLength(0);
    expect(screen.getByText(/regler som gällde vid valet 2026/)).toBeVisible();
    for (const label of ["seats.threshold", "seats.other"] as const) {
      expect(document.body).toHaveTextContent(t(label));
    }
  });
});

describe("PageIT coalition and poll content ports", () => {
  it("theCoalitionsPageListsAllTenApprovedMembershipsWithTheirParties", () => {
    renderPage(COALITIONS);

    expect(screen.getByRole("heading", { name: /Regeringsunderlag/ })).toBeVisible();
    const table = screen.getByRole("table", { name: /Mandat och sannolikhet/ });
    expect(within(table).getAllByRole("row")).toHaveLength(COALITION_ROWS_WITH_HEADER);
    const opposition = within(table)
      .getByRole("rowheader", { name: "S, C, V och MP" })
      .closest("tr");
    const centreLeft = within(table)
      .getByRole("rowheader", { name: "C, L, MP och S" })
      .closest("tr");
    expect(opposition).toHaveTextContent("Vänsterpartiet");
    expect(centreLeft).toHaveTextContent("Liberalerna");
  });

  it("aCoalitionProbabilityReadsAsWholePercentAndNeverAsCertainty / theCoalitionsPageStatesThatALabelIsNotAnEndorsement", () => {
    renderPage(COALITIONS, "en");

    const table = screen.getByRole("table", { name: /Seats and majority probability/ });
    const opposition = within(table).getByRole("row", { name: /S, C, V and MP/ });
    expect(opposition).toHaveTextContent("71%");
    expect(table).not.toHaveTextContent(/\b(?:0|100)%\b/);
    expect(screen.getByText(/A label states membership and implies no agreement/)).toBeVisible();
  });

  it("thePollsPageListsArchivedObservationsBeforeAnyScriptRuns / aReportedShareKeepsTheSourcePrecisionAndAnAbsentOneStaysMissing", () => {
    renderPage(POLLS);

    expect(screen.getByRole("heading", { name: "Publicerade mätningar" })).toBeVisible();
    const skop = screen.getByRole("row", { name: /Skop/ });
    expect(skop).toHaveTextContent("1 021");
    expect(skop).toHaveTextContent("29,6");
    expect(skop).toHaveTextContent("1,9");
    expect(screen.getByRole("link", { name: "Filtrerade mätningar (CSV)" })).toHaveAttribute(
      "href",
      expect.stringContaining("publication=pub_20260908T051233Z"),
    );
  });
});

describe("PageIT pollster and method content ports", () => {
  it("thePollstersPageNamesItsInstitutesTheirErasAndTheirFootprint / thePollstersPageRendersAHeatTablePerCycleBesideItsTableAlternative", () => {
    renderPage(POLLSTERS);

    expect(screen.getByRole("heading", { name: "Institut och huseffekter" })).toBeVisible();
    const metadata = screen.getByRole("table", { name: "" });
    expect(within(metadata).getByRole("row", { name: /Novus/ })).toHaveTextContent("96");
    expect(screen.getByText("demoskop_before_2019_11")).toHaveAttribute(
      "href",
      expect.stringContaining("MansMeg/SwedishPolls"),
    );
    expect(screen.getByText(/House effects are deviations/)).toBeVisible();
    expect(screen.getByRole("table", { name: /Huseffekter under 2022-2026/ })).toBeVisible();
    expect(screen.getByRole("table", { name: /Samma effekter som tabell/ })).toBeVisible();
  });

  it("theMethodPageCarriesTheFrozenReproductionValues / theMethodPagePresentsTheRecordedVerdictOfTheFreezeItRunsUnder", () => {
    renderPage(METHOD, "en");

    expect(screen.getByRole("heading", { name: "Method and validation" })).toBeVisible();
    expect(screen.getByText(/Seed: 20260908/)).toBeVisible();
    expect(screen.getByText(/Joint draws: 10000/)).toBeVisible();
    expect(screen.getByText(/midpoint-ilr-state-space\/v1-development-1/)).toBeVisible();
    expect(screen.getByRole("row", { name: /eight_party_2010/ })).toBeVisible();
    expect(screen.getByText(/Recorded verdict.*blocked/)).toBeVisible();
    expect(screen.getByText(/development_gates/)).toBeVisible();
  });
});

const BILINGUAL_CASES = [
  [
    "theOverviewCarriesItsHeadlineDateAndResultsTableBeforeAnyScriptRuns",
    OVERVIEW,
    "sv",
    /Skattat väljarstöd/,
    /Socialdemokraterna.*27,0 %/,
    ["estimate.column.party", "estimate.column.estimate", "estimate.column.interval"],
  ],
  [
    "theEnglishOverviewSaysTheSameThingInEnglish",
    OVERVIEW,
    "en",
    /Estimated voter support/,
    /Social Democrats.*27\.0%/,
    ["estimate.column.party", "estimate.column.estimate", "estimate.column.interval"],
  ],
  [
    "aCurrentPartyPageCarriesOnePartyThroughHtmlChartsEffectsAndDownloads",
    PARTY,
    "sv",
    /^Socialdemokraterna$/,
    /Skop.*29,6 %/,
    ["party.observations", "party.houseEffects", "downloads.title"],
  ],
  [
    "aCurrentPartyPageCarriesOnePartyThroughHtmlChartsEffectsAndDownloads",
    PARTY,
    "en",
    /^Social Democrats$/,
    /Skop.*29\.6%/,
    ["party.observations", "party.houseEffects", "downloads.title"],
  ],
  [
    "theSeatsPageCarriesItsIntegerAllocationBeforeAnyScriptRuns",
    SEATS,
    "sv",
    /Approximerad mandatfördelning/,
    /Socialdemokraterna.*96.*95,7.*89–102/,
    ["seats.column.point", "seats.column.mean", "seats.column.interval"],
  ],
  [
    "theSeatsPageSeparatesPointSeatsFromPosteriorMeansAndIntervals",
    SEATS,
    "en",
    /National seat approximation/,
    /Social Democrats.*96.*95\.7.*89–102/,
    ["seats.column.point", "seats.column.mean", "seats.column.interval"],
  ],
  [
    "theCoalitionsPageListsAllTenApprovedMembershipsWithTheirParties",
    COALITIONS,
    "sv",
    /Regeringsunderlag/,
    /S, C, V och MP.*Socialdemokraterna/,
    ["coalitions.column.parties", "coalitions.column.majority", "coalitions.caption"],
  ],
  [
    "theCoalitionsPageStatesThatALabelIsNotAnEndorsement",
    COALITIONS,
    "en",
    /Coalitions/,
    /S, C, V and MP.*Social Democrats/,
    ["coalitions.column.parties", "coalitions.column.majority", "coalitions.caption"],
  ],
  [
    "thePollstersPageNamesItsInstitutesTheirErasAndTheirFootprint",
    POLLSTERS,
    "sv",
    /Institut och huseffekter/,
    /Novus.*96/,
    ["pollsters.metadata", "pollsters.column.polls", "pollsters.column.eras"],
  ],
  [
    "theEnglishPollstersPageRendersTheSameTablesInEnglish",
    POLLSTERS,
    "en",
    /Pollsters and house effects/,
    /Novus.*96/,
    ["pollsters.metadata", "pollsters.column.polls", "pollsters.column.eras"],
  ],
  [
    "thePollsPageListsArchivedObservationsBeforeAnyScriptRuns",
    POLLS,
    "sv",
    /Publicerade mätningar/,
    /Skop.*29,6/,
    ["polls.filters", "polls.filter.institute", "polls.filter.apply"],
  ],
  [
    "theEnglishPollsPageTranslatesItsControlsAndDescribesItself",
    POLLS,
    "en",
    /Published polls/,
    /Skop.*29\.6/,
    ["polls.filters", "polls.filter.institute", "polls.filter.apply"],
  ],
  [
    "theMethodPageExplainsDataCoverageModelValidationAndSeats",
    METHOD,
    "sv",
    /Metod och validering/,
    /eight_party_2010.*Socialdemokraterna/,
    ["method.data.title", "method.validation.title", "method.seats.title"],
  ],
  [
    "theEnglishMethodPageRendersItsOwnWordingAndDescribesItself",
    METHOD,
    "en",
    /Method and validation/,
    /eight_party_2010.*Social Democrats/,
    ["method.data.title", "method.validation.title", "method.seats.title"],
  ],
] as const;

describe("PageIT bilingual headline, figure, table and label ports", () => {
  const cases = BILINGUAL_CASES.map((testCase) => [testCase] as const);
  it.each(cases)("%s", (testCase) => {
    const [_name, family, language, heading, tableContent, labels] = testCase;
    const page = pageFixture(family, language);
    const t = translator(page);
    render(<Shell page={page} t={t} />);

    expect(screen.getByRole("heading", { name: heading })).toBeVisible();
    expect(screen.getAllByRole("table")[0]).toHaveTextContent(tableContent);
    for (const label of labels) {
      expect(document.body).toHaveTextContent(t(label));
    }
  });
});

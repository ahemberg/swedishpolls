import type { Series } from "./bootstrap";

type Block = "a" | "b";
interface Summary {
  readonly mean: number | null;
  readonly lower: number | null;
  readonly upper: number | null;
  readonly availability: string;
}
interface CoalitionHistoryData {
  readonly publicationId: string;
  readonly publishedAt: string;
  readonly lastFieldworkDate: string;
  readonly selection: Readonly<Record<Block, readonly string[]>>;
  readonly requestedRange: { readonly from: string; readonly to: string; readonly step: number };
  readonly dates: readonly string[];
  readonly fitIds: readonly (string | null)[];
  readonly fitBoundaries: readonly { readonly date: string }[];
  readonly gaps: readonly { readonly from: string; readonly to: string }[];
  readonly series: Readonly<
    Record<
      Block,
      Omit<Series, "component"> & {
        readonly availability: readonly string[];
      }
    >
  >;
  readonly latest: {
    readonly date: string;
    readonly partyMeans: Readonly<Record<string, number | null>>;
    readonly a: Summary;
    readonly b: Summary;
    readonly unassignedMean: number | null;
    readonly comparableRemainderMean: number | null;
    readonly outsideBothMean: number | null;
    readonly availability: string;
  };
}

const BLOCKS: readonly Block[] = ["a", "b"];
const PALETTE: ReadonlyMap<string, string> = new Map([
  ["S", "#d22d3f"],
  ["M", "#173b70"],
  ["SD", "#e2bc25"],
  ["V", "#8c1737"],
  ["C", "#187344"],
  ["KD", "#639bd2"],
  ["L", "#337dc4"],
  ["MP", "#8fc44b"],
]);
const NEUTRAL_RED = 100;
const NEUTRAL_GREEN = 110;
const NEUTRAL_BLUE = 120;
const NEUTRAL = [NEUTRAL_RED, NEUTRAL_GREEN, NEUTRAL_BLUE];
const SD_BLUE = "#427cae";
const RED_LUMINANCE = 0.2126;
const GREEN_LUMINANCE = 0.7152;
const BLUE_LUMINANCE = 0.0722;
const LUMINANCE = [RED_LUMINANCE, GREEN_LUMINANCE, BLUE_LUMINANCE];
const MAX_CHANNEL = 255;
const LINEAR_LIMIT = 0.040_45;
const LINEAR_DIVISOR = 12.92;
const GAMMA_OFFSET = 0.055;
const GAMMA_DIVISOR = 1.055;
const GAMMA = 2.4;
const MAX_LUMINANCE = 0.23;
const DARKEN_STEP = 0.95;
const SIMILARITY_DISTANCE = 65;
const SIMILARITY_DARKEN = 0.55;
const HEX_DIGITS = 2;
const HEX_RADIX = 16;
const BLUE_CHANNEL = 2;

function luminance(rgb: readonly number[]): number {
  return rgb.reduce((sum, value, index) => {
    const channel = value / MAX_CHANNEL;
    let linear = channel / LINEAR_DIVISOR;
    if (channel > LINEAR_LIMIT) {
      linear = ((channel + GAMMA_OFFSET) / GAMMA_DIVISOR) ** GAMMA;
    }
    return sum + linear * (LUMINANCE[index] ?? 0);
  }, 0);
}

function partyColor(party: string, blue: boolean): string {
  if (party === "SD" && blue) {
    return SD_BLUE;
  }
  return PALETTE.get(party) ?? "#646e78";
}

function blend(
  parties: readonly string[],
  shares: Readonly<Record<string, number | null>>,
): number[] {
  if (
    parties.some((party) => typeof shares[party] !== "number" || !Number.isFinite(shares[party]))
  ) {
    return [...NEUTRAL];
  }
  const total = parties.reduce((sum, party) => sum + (shares[party] ?? 0), 0);
  if (total <= 0) {
    return [...NEUTRAL];
  }
  const blue = parties.some((party) => ["M", "KD", "L"].includes(party));
  let rgb = [0, 1, BLUE_CHANNEL].map((channel) =>
    parties.reduce((sum, party) => {
      const hex = partyColor(party, blue);
      return (
        sum +
        (Number.parseInt(
          hex.slice(1 + channel * HEX_DIGITS, 1 + (channel + 1) * HEX_DIGITS),
          HEX_RADIX,
        ) *
          (shares[party] ?? 0)) /
          total
      );
    }, 0),
  );
  while (luminance(rgb) > MAX_LUMINANCE) {
    rgb = rgb.map((value) => value * DARKEN_STEP);
  }
  return rgb.map(Math.round);
}

function blockColors(
  groups: readonly (readonly string[])[],
  shares: Readonly<Record<string, number | null>>,
): readonly string[] {
  const [a = [], b = []] = groups;
  const first = blend(a, shares);
  let second = blend(b, shares);
  if (
    groups.every((group) => group.length > 0) &&
    Math.hypot(...first.map((value, index) => value - (second[index] ?? 0))) < SIMILARITY_DISTANCE
  ) {
    second = second.map((value) => Math.round(value * SIMILARITY_DARKEN));
  }
  return [first, second].map(
    (rgb) => `#${rgb.map((value) => value.toString(HEX_RADIX).padStart(HEX_DIGITS, "0")).join("")}`,
  );
}

type SegmentedHistory = Pick<CoalitionHistoryData, "dates" | "fitIds" | "fitBoundaries" | "gaps">;

function startsSegment(history: SegmentedHistory, index: number, date: string): boolean {
  const previous = history.dates[index - 1];
  if (previous === undefined) {
    return true;
  }
  return (
    history.fitIds[index] !== history.fitIds[index - 1] ||
    history.fitBoundaries.some((boundary) => boundary.date > previous && boundary.date <= date) ||
    history.gaps.some((gap) => gap.from < date && gap.to > previous)
  );
}

function fitSegments(history: SegmentedHistory): readonly (readonly number[])[] {
  return history.dates.reduce<number[][]>((segments, date, index) => {
    if (typeof history.fitIds[index] !== "string") {
      return segments;
    }
    if (startsSegment(history, index, date)) {
      segments.push([]);
    }
    segments.at(-1)?.push(index);
    return segments;
  }, []);
}

export type { Block, CoalitionHistoryData };
export { BLOCKS, blockColors, fitSegments };

import type { JSX } from "react";
import type { Bootstrap, Series, Translate } from "./bootstrap";
import {
  axisLevels,
  axisMaximum,
  BASELINE,
  bandPath,
  HEIGHT,
  LEFT,
  linePath,
  PLOT_WIDTH,
  TOP,
  WIDTH,
  yAt,
} from "./chart";
import { BLOCKS, blockColors, type CoalitionHistoryData, fitSegments } from "./coalition-history";
import { percent } from "./format";

const BAND_OPACITY = 0.16;
const LINE_WIDTH = 2;
const PERCENT_SCALE = 100;
const DASH = "7 4";

function SegmentPaths({
  series,
  indices,
  x,
  y,
}: {
  readonly series: Series;
  readonly indices: readonly number[];
  readonly x: (index: number) => number;
  readonly y: (value: number) => number;
}): JSX.Element {
  const sliced: Series = {
    component: series.component,
    mean: indices.map((index) => series.mean[index] ?? null),
    lower: indices.map((index) => series.lower[index] ?? null),
    upper: indices.map((index) => series.upper[index] ?? null),
  };
  const at = (index: number): number => x(indices[index] ?? 0);
  let dash: string | undefined;
  if (series.component === "b") {
    dash = DASH;
  }
  return (
    <g>
      <path d={bandPath(sliced, at, y)} fillOpacity={BAND_OPACITY} stroke="none" />
      <path
        d={linePath(sliced.mean, at, y)}
        fill="none"
        strokeWidth={LINE_WIDTH}
        vectorEffect="non-scaling-stroke"
        strokeDasharray={dash}
      />
      {indices.length === 1 && typeof sliced.mean[0] === "number" && (
        <circle cx={at(0)} cy={y(sliced.mean[0])} r={LINE_WIDTH} />
      )}
    </g>
  );
}

function CoalitionHistoryPlot({
  history,
  page,
  t,
  index,
}: {
  readonly history: CoalitionHistoryData;
  readonly page: Bootstrap;
  readonly t: Translate;
  readonly index: number;
}): JSX.Element {
  const drawn = BLOCKS.map((component) => ({ component, ...history.series[component] }));
  const colors = blockColors([history.selection.a, history.selection.b], history.latest.partyMeans);
  const maximum = axisMaximum(drawn);
  const first = Date.parse(history.requestedRange.from);
  const duration = Math.max(1, Date.parse(history.requestedRange.to) - first);
  const x = (index: number): number =>
    LEFT +
    (PLOT_WIDTH * (Date.parse(history.dates[index] ?? history.requestedRange.from) - first)) /
      duration;
  const y = (value: number): number => yAt(value, maximum);
  const segments = fitSegments(history);
  return (
    <div className="coalition-graphic">
      <svg
        className="coalition-plot"
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={t("coalitionHistory.table")}
      >
        {axisLevels(maximum).map((value) => (
          <g key={value}>
            <line x1={LEFT} x2={LEFT + PLOT_WIDTH} y1={y(value)} y2={y(value)} stroke="#d5d5d5" />
          </g>
        ))}
        {drawn.map((series, blockIndex) => (
          <g key={series.component} fill={colors[blockIndex]} stroke={colors[blockIndex]}>
            {segments.map((indices) => (
              <SegmentPaths key={indices[0]} series={series} indices={indices} x={x} y={y} />
            ))}
          </g>
        ))}
        {history.dates[index] !== undefined && (
          <line x1={x(index)} x2={x(index)} y1={TOP} y2={BASELINE} stroke="#555" />
        )}
      </svg>
      {axisLevels(maximum).map((value) => (
        <span
          key={value}
          className="coalition-axis-label"
          style={{ top: `${(PERCENT_SCALE * y(value)) / HEIGHT}%` }}
        >
          {percent(String(value), page.language)}
        </span>
      ))}
      {drawn.map(
        (series, blockIndex) =>
          typeof series.mean[index] === "number" && (
            <span
              key={series.component}
              className={`coalition-direct-label block-${series.component}`}
              style={{
                left: `${(PERCENT_SCALE * x(index)) / WIDTH}%`,
                top: `${(PERCENT_SCALE * y(series.mean[index] ?? 0)) / HEIGHT}%`,
                color: colors[blockIndex],
              }}
            >
              {series.component.toUpperCase()}
            </span>
          ),
      )}
    </div>
  );
}

export { CoalitionHistoryPlot };

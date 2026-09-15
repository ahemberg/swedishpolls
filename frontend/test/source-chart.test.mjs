import assert from 'node:assert/strict';
import test from 'node:test';
import { axisMaximum, PLOT } from '../src/chart.ts';
import {
  MINIMUM_SPAN,
  dateX,
  markerSpan,
  reportedShares,
  sourceMarks,
  yearTicks,
} from '../src/source-chart.ts';

const YEAR = { id: 'oneYear', from: '2025-01-01', to: '2025-12-31' };
const LEFT = PLOT.left;
const PLOT_WIDTH = PLOT.width;
const RIGHT_EDGE = LEFT + PLOT_WIDTH;

function observation(over) {
  return {
    pollId: '7:1',
    institute: 'Novus',
    from: '2025-06-01',
    to: '2025-06-10',
    approximatePeriod: false,
    sampleSize: 1200,
    shares: { S: 30, M: 20, FI: null },
    ...over,
  };
}

test('a marker spans its own interview dates rather than a sampled estimate day', () => {
  const span = markerSpan(observation(), YEAR, PLOT);
  const day = PLOT_WIDTH / 365;
  assert.ok(Math.abs(span.x1 - (LEFT + day * 151)) < 0.5);
  assert.ok(Math.abs(span.x2 - (LEFT + day * 161)) < 0.5);
  assert.equal(span.clippedFrom, false);
  assert.equal(span.clippedTo, false);
});

test('an estimate ending before the source window does not reach the chart edge', () => {
  assert.ok(dateX('2025-12-15', YEAR, PLOT) < RIGHT_EDGE);
  assert.equal(dateX(YEAR.to, YEAR, PLOT), RIGHT_EDGE);
});

test('a one-day period keeps a selectable width inside the plot', () => {
  const span = markerSpan(observation({ from: '2025-06-01', to: '2025-06-01' }), YEAR, PLOT);
  assert.ok(span.x2 - span.x1 >= MINIMUM_SPAN);
  assert.ok(span.x1 >= LEFT);
  assert.ok(span.x2 <= RIGHT_EDGE);
});

test('a period crossing either boundary is clipped to the plot and says so', () => {
  const early = markerSpan(observation({ from: '2024-12-20', to: '2025-01-05' }), YEAR, PLOT);
  assert.equal(early.x1, LEFT);
  assert.equal(early.clippedFrom, true);
  assert.equal(early.clippedTo, false);
  const late = markerSpan(observation({ from: '2025-12-28', to: '2026-01-04' }), YEAR, PLOT);
  assert.equal(late.x2, RIGHT_EDGE);
  assert.equal(late.clippedFrom, false);
  assert.equal(late.clippedTo, true);
});

test('a single-day window still places its markers instead of dividing by nothing', () => {
  const point = { id: 'all', from: '2025-06-01', to: '2025-06-01' };
  const span = markerSpan(observation({ from: '2025-06-01', to: '2025-06-01' }), point, PLOT);
  assert.ok(Number.isFinite(span.x1));
  assert.ok(Number.isFinite(span.x2));
  assert.ok(span.x2 > span.x1);
});

test('a missing share draws no marker and the remainder is never one', () => {
  const marks = sourceMarks([observation()], YEAR, ['S', 'M', 'FI'], PLOT);
  assert.deepEqual(marks.map((mark) => mark.component), ['S', 'M']);
  assert.deepEqual(sourceMarks([observation()], YEAR, ['OTHER'], PLOT), []);
});

test('marks carry the observation they came from so details stay complete', () => {
  const [mark] = sourceMarks([observation({ approximatePeriod: true })], YEAR, ['S'], PLOT);
  assert.equal(mark.key, '7:1-S');
  assert.equal(mark.share, 30);
  assert.equal(mark.observation.institute, 'Novus');
  assert.equal(mark.observation.approximatePeriod, true);
});

test('the axis reaches the highest reported share of the drawn parties alone', () => {
  const polls = [observation(), observation({ pollId: '7:2', shares: { S: 44, M: 3, FI: null } })];
  const maximum = (components) => axisMaximum([], reportedShares(polls, components));
  assert.deepEqual(reportedShares(polls, ['S', 'FI']), [30, 44]);
  assert.equal(maximum(['M']), 20);
  assert.equal(maximum(['S', 'M']), 45);
  assert.equal(axisMaximum([], reportedShares([], ['S'])), 10);
});

test('year rules fall on every January inside the window, never on its first day', () => {
  assert.deepEqual(yearTicks(YEAR, PLOT), []);
  assert.deepEqual(
    yearTicks({ id: 'all', from: '2023-06-01', to: '2025-03-01' }, PLOT).map((tick) => tick.year),
    ['2024', '2025'],
  );
});

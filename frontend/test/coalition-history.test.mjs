import assert from 'node:assert/strict';
import test from 'node:test';
import {
  assign,
  assignmentFromSelection,
  blockColors,
  coalitionSharePath,
  destinationOf,
  emptyAssignment,
  fitSegments,
  groupsFor,
  PARTIES,
  PRESET,
  total,
} from '../src/coalition-history.ts';

test('all 6,561 assignments keep each party in exactly one ordered destination', () => {
  const destinations = ['a', 'b', 'unassigned'];
  for (let state = 0; state < 3 ** PARTIES.length; state += 1) {
    let remaining = state;
    const assignment = PARTIES.map(() => {
      const destination = destinations[remaining % destinations.length];
      remaining = Math.floor(remaining / destinations.length);
      return destination;
    });
    const groups = groupsFor(assignment);
    assert.deepEqual(groups.a, PARTIES.filter((party) => destinationOf(assignment, party) === 'a'));
    assert.deepEqual(groups.b, PARTIES.filter((party) => destinationOf(assignment, party) === 'b'));
    assert.equal(groups.a.length + groups.b.length + assignment.filter((value) => value === 'unassigned').length, PARTIES.length);
  }
});

test('preset, clear, singleton and moves preserve canonical roster order', () => {
  assert.deepEqual(groupsFor(PRESET), {a:['S','V','C','MP'], b:['M','SD','KD','L']});
  assert.deepEqual(groupsFor(emptyAssignment()), {a:[], b:[]});
  const singleton = assign(emptyAssignment(), 'SD', 'b');
  assert.deepEqual(groupsFor(singleton), {a:[], b:['SD']});
  assert.equal(destinationOf(assign(singleton, 'SD', 'a'), 'SD'), 'a');
  assert.deepEqual(assignmentFromSelection({a:['MP','S'], b:['L']}), ['a','unassigned','unassigned','unassigned','unassigned','unassigned','b','a']);
});

test('latest totals use zero for empty groups and null for missing constituents', () => {
  assert.equal(total([], {}), 0);
  assert.equal(total(['S','V'], {S:31.2,V:7.8}), 39);
  assert.equal(total(['S','V'], {S:31.2,V:null}), null);
});

test('share paths use the editor assignment and selected range', () => {
  const assignment = assign(PRESET, 'V', 'unassigned');
  assert.equal(
    coalitionSharePath('/en/coalitions?old=yes', groupsFor(assignment), {from:'2020-01-01',to:'2020-12-31'}),
    '/en/coalitions?a=S,C,MP&b=M,SD,KD,L&from=2020-01-01&to=2020-12-31'
  );
});

test('approved mixtures use latest support, conditional SD blue and neutral missing weights', () => {
  assert.equal(blockColors([['S', 'M'], []], {S:10,M:30})[0], '#463864');
  assert.equal(blockColors([['SD', 'M'], []], {SD:10,M:30})[0], '#224b80');
  assert.equal(blockColors([['S'], []], {})[0], '#646e78');
  assert.equal(blockColors([['S'], []], {S:0})[0], '#646e78');
  assert.equal(blockColors([['S','M'], ['V']], {S:90,M:10,V:10})[1], '#4d0d1e');
});

test('lines and bands break at both fit changes and explicit gaps', () => {
  const history = {
    dates: ['2020-01-01','2020-01-02','2020-01-04','2020-01-05'],
    fitIds: ['one','one','two','two'],
    fitBoundaries: [],
    gaps: [{from:'2020-01-03',to:'2020-01-03'}]
  };
  assert.deepEqual(fitSegments(history), [[0,1],[2,3]]);
  assert.deepEqual(fitSegments({...history, fitIds:['one','one','one','one']}), [[0,1],[2,3]]);
  assert.deepEqual(fitSegments({...history, dates:[], fitIds:[]}), []);
});

test('missing fitted days and declared boundaries cannot be joined', () => {
  const history = {dates:['2020-01-01','2020-01-02','2020-01-03'], fitIds:['one', null, 'one'], fitBoundaries:[], gaps:[]};
  assert.deepEqual(fitSegments(history), [[0],[2]]);
  assert.deepEqual(fitSegments({...history, fitIds:['one','one','one'],fitBoundaries:[{date:'2020-01-02'}]}), [[0],[1,2]]);
});

test('history fetches reject errors and cancellation instead of accepting an error document', async () => {
  const { createServer } = await import('node:http');
  const { once } = await import('node:events');
  const { historyJson } = await import('../src/useHistory.ts');
  const server = createServer((request, response) => {
    response.statusCode = request.url === '/ok' ? 200 : 409;
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({publicationId: 'pinned'}));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const base = `http://127.0.0.1:${server.address().port}`;
    assert.deepEqual(await historyJson(`${base}/ok`, new AbortController().signal), {publicationId:'pinned'});
    await assert.rejects(historyJson(`${base}/missing`, new AbortController().signal), /409/);
    const aborted = new AbortController();
    aborted.abort();
    await assert.rejects(historyJson(`${base}/ok`, aborted.signal), {name:'AbortError'});
  } finally {
    server.close();
    await once(server, 'close');
  }
});

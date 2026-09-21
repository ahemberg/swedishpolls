import assert from 'node:assert/strict';
import test from 'node:test';
import en from '../src/text.en.json' with { type: 'json' };
import sv from '../src/text.sv.json' with { type: 'json' };
import { named, translate } from '../src/text.ts';

test('both languages carry the same keys', () => {
  assert.deepEqual(Object.keys(sv).sort(), Object.keys(en).sort());
});

test('a template places every value it was given', () => {
  assert.equal(
    translate('sv', 'headline.asOf', { date: '4 september 2026' }),
    sv['headline.asOf'].replace('{date}', '4 september 2026'),
  );
});

test('a token the caller left unfilled is a throw rather than a rendered brace', () => {
  assert.throws(() => translate('sv', 'headline.asOf'), /Unfilled token/);
  assert.throws(() => translate('en', 'about.run', { run: '7' }), /Unfilled token/);
});

test('a template placeholder has the same name in both languages', () => {
  const tokens = (template) => [...template.matchAll(/\{([a-zA-Z]+)\}/g)].map((m) => m[1]).sort();
  for (const key of Object.keys(sv)) {
    assert.deepEqual(tokens(sv[key]), tokens(en[key]), key);
  }
});

test('an unlabelled component reads as its key rather than disappearing', () => {
  assert.equal(named('sv', 'component.S', 'S'), sv['component.S']);
  assert.equal(named('sv', 'component.XX', 'XX'), 'XX');
});

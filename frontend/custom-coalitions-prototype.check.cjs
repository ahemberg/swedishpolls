// Run: node frontend/custom-coalitions-prototype.check.cjs
const {readFileSync}=require('node:fs');
const {runInNewContext}=require('node:vm');
const assert=require('node:assert/strict');
const html=readFileSync(`${__dirname}/custom-coalitions-prototype.html`,'utf8');
const script=html.match(/<script>([\s\S]*)<\/script>/)[1];
function load(search='') {
 const elements={};
 const context={URLSearchParams, location:{search,href:'http://localhost:4173/custom-coalitions-prototype.html'+search},history:{replaceState(){}},document:{documentElement:{},body:{dataset:{}},getElementById(id){return elements[id]??=( {value:id==='scrub'?'32':'',setAttribute(){}});},querySelectorAll(){return [];}}};
 runInNewContext(script,context);
 return {elements,eval:code=>runInNewContext(code,context)};
}
const page=load('?parties=S,M,FI,OTHER,S');
assert.equal(page.eval("keys.filter(k=>assignment[k]==='a').join(',')"),'S,M');
assert.equal(page.eval('estimate(0,[] )'),null);
assert.equal(page.eval("estimate(15,['S'])"),null);
assert.equal(page.eval("validDate('2025-99-99')"),false);
assert.equal(page.eval("validDate('2025-02-30')"),false);
assert.equal(page.eval("validDate('2024-02-29')"),true);
const s=page.eval("estimate(0,['S']).mean"),m=page.eval("estimate(0,['M']).mean");
assert.ok(Math.abs(page.eval("estimate(0,['S','M']).mean")-s-m)<1e-10);
page.elements.clear.onclick();
assert.equal(page.elements.empty.hidden,false);
assert.equal(page.elements.plot.hidden,true);
const empty=load('?parties=&from=2025-01-01&to=2025-12-01&lang=sv&variant=B');
assert.equal(empty.eval("keys.filter(k=>assignment[k]!=='none').length"),0);
assert.equal(empty.elements.from.value,'2025-01-01');
assert.equal(empty.elements.variantName.textContent,'C · Kompakt');
empty.eval("cycle(1)");assert.equal(empty.eval('variant'),'A');
const reverse=load('?from=2026-01-01&to=2025-01-01');
assert.ok(reverse.elements.rangeError.textContent);
console.log('Passed: party filtering, empty state, joint sums, missing estimates, date validation, URL restore and layouts.');

const blocks=load('?a=S,M,FI&b=M,SD');
assert.equal(blocks.eval('assignment.M'),'a');
blocks.eval("assign('M','b'); render()");
assert.equal(blocks.eval('assignment.M'),'b');
assert.equal(blocks.eval("keys.filter(k=>assignment[k]==='a').join(',')"),'S');
blocks.eval("assign('M','none'); render()");
assert.equal(blocks.eval('assignment.M'),'none');
const color=blocks.eval("JSON.stringify(colorsFor([['S'],['SD']]))");
blocks.elements.scrub.value='3';blocks.eval('render()');
assert.equal(blocks.eval("JSON.stringify(colorsFor([['S'],['SD']]))"),color);
assert.equal(blocks.eval("colorsFor([['S'],['M']]).every(c=>/^#[0-9a-f]{6}$/.test(c))"),true);
assert.equal(blocks.eval("colorsFor([['M'],['M']])[0]!==colorsFor([['M'],['M']])[1]"),true);
assert.equal(blocks.eval("blockColor(['S','M']).join(',')===blockColor(['M','S']).join(',')"),true);
console.log('Passed: disjoint ownership, moving/unassigning, stable weighted colors, similar-color fallback.');

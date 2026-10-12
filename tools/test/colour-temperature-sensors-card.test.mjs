import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = await readFile(process.argv[2] || new URL('../../app/src/main/assets/info.js', import.meta.url), 'utf8');
async function rowsFor(payload) {
  let rows;
  const context = vm.createContext({
    i18nText: (_key, text, args = {}) => text.replace(/\{(\w+)\}/g, (_, key) => String(args[key])),
    document: { hidden: false, getElementById: () => null },
    PT: { subscribe: (family, onValue) => { assert.equal(family, 'sensors'); onValue(payload, 'known'); return { close() {} }; } },
    paint: (_table, value) => { rows = value; },
    cardSizeSourceReady: () => {},
  });
  vm.runInContext(source.slice(source.indexOf('function formatBrightness('), source.indexOf("sensorsCard('senstbl','sensage');")), context);
  context.sensorsCard('table', 'age');
  await new Promise(resolve => setImmediate(resolve));
  return JSON.parse(JSON.stringify(rows));
}
test('live card shows estimated kelvin with receipt age beside existing sensor readings', async () => {
  const rows = await rowsFor({colour_temperature:{present:true,kelvin:2726.6,age_s:3},light:{present:true,lux:20,age_s:2}});
  assert.equal(rows[0].val, '20 lx');
  assert.deepEqual(rows[1], {label:'Estimated colour temperature',val:'2726.6 K',suf:'· 3s ago'});
});
test('supported source without a usable sample shows no reading and missing source has no row', async () => {
  for (const kelvin of [null, 0, -1, NaN, Infinity, '2726']) {
    const rows = await rowsFor({colour_temperature:{present:true,kelvin,age_s:null}});
    assert.equal(rows[0].val, 'no reading yet');
    assert.equal(rows[0].suf, '');
  }
  assert.equal((await rowsFor({colour_temperature:{present:false}}))[0].val, 'no sensors on this panel');
});

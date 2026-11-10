import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {censusRecord,livingPresence,validate} from '../obras/a-hora-vermelha/atlas/model.mjs';

const load = name => JSON.parse(readFileSync(new URL(`../obras/a-hora-vermelha/atlas/data/${name}.json`,import.meta.url)));
const data = load('atlas');
const legacy = load('atlas-v2');

test('presence colors distinguish living stocks, unknown, and verified empty',()=>{
  assert.equal(livingPresence({human:0,converted:0},true),'empty');
  assert.equal(livingPresence({human:0,converted:0}),'unknown');
  assert.equal(livingPresence({human:0},true),'unknown');
  assert.equal(livingPresence({conversions:86712}),'unknown');
  assert.equal(livingPresence({converted:806}),'converted');
  assert.equal(livingPresence({human:75000}),'human');
  assert.equal(livingPresence({human:150320,converted:3100}),'mixed');
});

test('census lookup never substitutes a future observation or gives a date to undated records',()=>{
  const natal=data.localCensuses.find(site=>site.id==='natal-domain');
  assert.equal(censusRecord(natal,'2132-01-01'),undefined);
  assert.equal(censusRecord(natal,'2138-11-30').counts.converted,38720);
  assert.equal(censusRecord(natal,'2138-12-01').at,'2138-11-30');
  assert.equal(censusRecord(natal,'2149-04-30').counts.converted,undefined);
  const underground=data.localCensuses.find(site=>site.id==='underground-network');
  assert.equal(censusRecord(underground,'2155-01-01').at,null);
});

test('local reference counts validate without affecting worldwide totals',()=>{
  assert.equal(Object.keys(validate(data)).length,15);
  assert.equal(data.localCensuses.reduce((n,site)=>n+site.records.length,0),19);
  assert.equal(data.observations.find(r=>r.metric==='converted').value,null);
  const condo=data.localCensuses.find(site=>site.id==='urban-condo').records[0];
  assert.equal(Object.values(condo.counts).reduce((a,b)=>a+b),1165);
  const sony=data.localCensuses.find(site=>site.id==='sony-kibi').records.at(-1);
  assert.equal(sony.counts.conversions,86712);
  assert.equal(sony.counts.converted,undefined);
  const invalid=structuredClone(data);
  invalid.localCensuses[0].records[0].counts.converted=-1;
  assert.throws(()=>validate(invalid),/Contagem local inválida/);
});

test('additional V2 records may be mapped approximately or listed without coordinates',()=>{
  assert.equal(legacy.records.length,40);
  assert.equal(legacy.records.filter(r=>r.coordinates===null).length,5);
  assert.equal(legacy.records.find(r=>r.id==='NAVAL'&&r.epoch==='Y5').value,7500);
  assert.equal(Object.keys(legacy.regions).length,62);
});

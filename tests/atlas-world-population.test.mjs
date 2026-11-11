import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFileSync} from 'node:fs';
import {WORLD_CONTROL,buildWorld,regionStyle,symbolFor,worldAt} from '../obras/a-hora-vermelha/atlas/world-population.mjs';

const root=new URL('../obras/a-hora-vermelha/atlas/data/',import.meta.url);
const read=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'));
const world=buildWorld(read('world-population'),read('atlas'),read('atlas-v2'));

test('all snapshots preserve the global totals through regions and countries',()=>{
  for (const m of world.snapshots) {
    const state=worldAt(world,m.date);
    for (const map of [state.regions,state.countries]) {
      for (const key of ['human','converted']) {
        assert.equal([...map.values()].reduce((sum,value)=>sum+value[key],0),m[key]);
      }
    }
    assert.ok(m.human+m.converted<=WORLD_CONTROL);
  }
  const state=worldAt(world,'2132-01-01');
  assert.equal(state.anchor.human,21_338_000_000);
  assert.equal(state.anchor.converted,281_000_000);
  assert.equal(worldAt(world,'2131-12-31').anchor.human,21_900_000_000);
  assert.equal(worldAt(world,'2132-01-11').anchor.id,'D0');
});

test('geographic catalog includes Rio and subnational records on five continents',()=>{
  assert.equal(world.countryById.size,242);
  assert.equal(world.unitById.size,527);
  const rio=[...world.unitById.values()].find(u=>u.name==='Rio de Janeiro');
  assert.equal(rio.country,'BRA');
  const continents=new Set([...world.countryById.values()].filter(c=>c.regions.length>1).map(c=>c.continent));
  assert.ok(continents.size>=5);
  assert.ok(rio.geometry.coordinates.flat(Infinity).length/2>25);
  assert.ok(worldAt(world,'2132-01-01').regions.get(rio.id).human>1_000_000);
  assert.equal(worldAt(world,'2132-01-01').regions.get('ATA'),undefined);
});

test('symbol classes and region palettes distinguish unknown and sparse data',()=>{
  assert.equal(symbolFor(0),0);
  assert.equal(symbolFor(100),2.5);
  assert.equal(symbolFor(999),2.5);
  assert.equal(symbolFor(1000),3.5);
  assert.equal(regionStyle(null,1000).kind,'unknown');
  assert.equal(regionStyle({human:5,converted:0},100).kind,'sparse');
  assert.equal(regionStyle({human:1000,converted:150},100).kind,'human');
  assert.equal(regionStyle({human:150,converted:1000},100).kind,'converted');
  assert.equal(regionStyle({human:500,converted:500},100).kind,'mixed');
});

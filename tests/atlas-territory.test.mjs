import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFileSync} from 'node:fs';
import {footprint,planarArea,territoryGeometry,validateTerritories} from '../obras/a-hora-vermelha/atlas/territory.mjs';

const root=new URL('../obras/a-hora-vermelha/atlas/data/',import.meta.url);
const load=name=>JSON.parse(readFileSync(new URL(name,root),'utf8'));
const records=validateTerritories(load('territories.json'));
const groups=load('dispersed-v2.json');
const find=id=>records.find(r=>r.id===id);

test('Japanese base footprints preserve measured dimensions and distinct land areas',()=>{
  const legacy=find('jp-sony-pesquisa-animal');
  const current=find('jp-sony-megapredio');
  assert.deepEqual(legacy.dimensions,[500,500]);
  assert.deepEqual(current.dimensions,[350,850]);
  assert.equal(planarArea(footprint(legacy).points),250000);
  assert.equal(planarArea(footprint(current).points),297500);
  assert.equal(territoryGeometry(current),null);
  assert.equal(find('jp-polos').area,30_000_000);
});

test('legacy irregular layout and subsequent total stay separate',()=>{
  const previous=find('zeus-planta-1344');
  const current=find('zeus-territorio');
  assert.equal(planarArea(footprint(previous).points),1_344_000_000);
  assert.equal(planarArea(footprint(current).points),1_600_000_000);
  const ring=territoryGeometry(previous).coordinates[0];
  assert.equal(ring.length,9);
  assert.deepEqual(ring[0],ring.at(-1));
});

test('territory outlines can be hidden and verified geographic shapes still render',()=>{
  const port=find('porto-maceio');
  assert.equal(port.area,12_000_000);
  assert.equal(territoryGeometry(port,false),null);
  assert.equal(territoryGeometry(port).type,'Polygon');
  const geometry={type:'MultiPolygon',coordinates:[territoryGeometry(port).coordinates]};
  const exact={...port,geometry};
  assert.equal(validateTerritories([exact]).length,1);
  assert.equal(territoryGeometry(exact,false),geometry);
});

test('catalog includes cities, used ports and separately identified V2 modeled groups',()=>{
  assert.equal(records.length,64);
  assert.equal(records.filter(r=>r.category==='city').length,32);
  assert.equal(records.filter(r=>r.category==='port').length,4);
  assert.equal(find('porto-santa-aurora').schematicOnMap,false);
  assert.equal(find('porto-pacifico').anchor,null);
  assert.equal(Object.values(groups).flat().length,619);
  assert.equal(groups.PRE,undefined);
  assert.ok(groups.Y1.every(g=>g.groups>0&&g.min<=g.max&&g.rings.length));
  assert.equal(groups.Y1.find(g=>g.region==='R001').groups,8379);
});

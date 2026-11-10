import { parentAt, parseCell } from './geo.mjs';

export const metrics = ['human', 'converted', 'deaths', 'missing'];
export const confidence = {canon:'Canônico', estimate:'Estimativa calculada', hypothesis:'Hipótese', demo:'Demonstração', unknown:'Sem dados'};
export const presences = {present:'Presença registrada', absent:'Ausência confirmada', estimatedAbsent:'Ausência estimada', unknown:'Presença desconhecida'};
const format = /^\d{4}-\d{2}-\d{2}$/;
const validDate = value => typeof value === 'string' && format.test(value) && Number.isFinite(Date.parse(value))
  && new Date(value).toISOString().slice(0,10) === value;
const count = value => Number.isSafeInteger(value) && value >= 0;
const sum = values => values.reduce((a,b) => {
  if (!Number.isSafeInteger(a+b)) throw new Error('Total excede a precisão inteira suportada.');
  return a+b;
}, 0);
const assert = (condition, message) => { if (!condition) throw new Error(message); };
const owns = (object, key) => Object.hasOwn(object, key);
const reserved = new Set(['__proto__','prototype','constructor']);
const idValid = id => typeof id === 'string' && /^[a-zA-Z0-9_.-]{1,80}$/.test(id) && !reserved.has(id);
const plain = value => value !== null && typeof value === 'object' && !Array.isArray(value);

export function allocate(total, entries) {
  assert(count(total) && Array.isArray(entries) && entries.length > 0, 'Distribuição sem total ou sem pesos.');
  assert(entries.every(e => typeof e.id === 'string' && count(e.weight)) && new Set(entries.map(e=>e.id)).size === entries.length, 'Pesos inválidos ou duplicados.');
  const divisor = entries.reduce((s,e) => s + BigInt(e.weight), 0n);
  assert(divisor > 0n, 'Distribuição sem peso positivo.');
  const parts = entries.map(({id,weight}) => {
    const product = BigInt(total) * BigInt(weight);
    return {id, value: Number(product/divisor), rest: product%divisor};
  }).sort((a,b) => a.rest === b.rest ? (a.id < b.id ? -1 : a.id > b.id ? 1 : 0) : a.rest > b.rest ? -1 : 1);
  const remainder = total - sum(parts.map(p=>p.value));
  return Object.fromEntries(parts.map((p,i)=>[p.id,p.value + Number(i < remainder)]));
}
export function distribute(total, cells, replacements = {}) {
  const fixed = Object.entries(replacements);
  assert(fixed.every(([id,value]) => cells.some(c=>c.id===id) && count(value)), 'Substituição aponta para uma célula desconhecida ou usa valor inválido.');
  const free = cells.filter(c => !owns(replacements,c.id));
  const residual = total - sum(fixed.map(([,value])=>value));
  assert(residual >= 0 && (free.length || residual === 0), 'Substituição excede o total ou deixa saldo sem cobertura.');
  return {...(free.length && residual ? allocate(residual,free) : Object.fromEntries(free.map(c=>[c.id,0]))), ...replacements};
}
export function ratio(numerator, denominator) {
  return numerator == null || denominator == null || denominator <= 0 ? null : numerator / denominator;
}
export function weightedMean(items) {
  if (!items.length || items.some(x=>!Number.isFinite(x.value) || !Number.isFinite(x.weight) || x.weight < 0)) return null;
  const weight = items.reduce((s,x)=>s+x.weight,0);
  return weight ? items.reduce((s,x)=>s+x.value*x.weight,0) / weight : null;
}

function unique(items,label) {
  assert(Array.isArray(items),`Lista inválida: ${label}`);
  assert(items.every(item => item && idValid(item.id)) && new Set(items.map(item=>item.id)).size === items.length, `Identificador inválido ou repetido: ${label}`);
}
function windowValid(when, nullable = false) {
  if (nullable && when === null) return;
  assert(plain(when) && validDate(when.start) && validDate(when.end) && when.start <= when.end, 'Intervalo de data inválido.');
}
function locationValid(p) {
  return Array.isArray(p) && p.length === 2 && p.every(Number.isFinite) && Math.abs(p[0]) <= 180 && Math.abs(p[1]) <= 90;
}
function geometryValid(g) {
  if (g === null) return true;
  if (!plain(g)) return false;
  const c = g.coordinates;
  if (g.type === 'Point') return locationValid(c);
  if (g.type === 'LineString') return Array.isArray(c) && c.length >= 2 && c.every(locationValid);
  if (g.type === 'Polygon') return Array.isArray(c) && c.length > 0 && c.every(r => Array.isArray(r) && r.length >= 4
    && r.every(locationValid) && r[0][0] === r.at(-1)[0] && r[0][1] === r.at(-1)[1]);
  return false;
}
function splitTotal(total, children) {
  const fixed = Object.fromEntries(children.filter(child=>child.declaredTotal!==undefined)
    .map(child=>[child.id,child.declaredTotal]));
  return distribute(total,children,fixed);
}
function makeInitial(demo) {
  const nationalTotals = splitTotal(demo.initialGlobal - (demo.unallocatedGlobal ?? 0), demo.countries);
  const balances = {};
  for (const country of demo.countries) {
    const localRegions = demo.regions.filter(r=>r.country===country.id);
    const split = splitTotal(nationalTotals[country.id],localRegions);
    for (const region of localRegions) {
      const humans = distribute(split[region.id],region.cells,region.replacements);
      balances[region.id] = Object.fromEntries(Object.entries(humans).map(([id,human])=>[id,{human,converted:0,deaths:0,missing:0}]));
    }
  }
  return balances;
}
function validateOps(event, owners) {
  const ops = event.ops;
  assert(Array.isArray(ops) && ops.length && ops.every(o=>o && owns(owners,o.cell) && metrics.includes(o.metric) && Number.isSafeInteger(o.delta) && o.delta !== 0), `Lançamentos inválidos: ${event.id}`);
  const keys = ops.map(o=>`${o.cell}|${o.metric}`);
  assert(new Set(keys).size===keys.length, `Lançamento repetido: ${event.id}`);
  const total = sum(ops.map(o=>o.delta));
  const positive = ops.filter(o=>o.delta>0);
  const negative = ops.filter(o=>o.delta<0);
  const paired = (from,to) => total===0 && positive.length && negative.length && negative.every(o=>from.includes(o.metric)) && positive.every(o=>o.metric===to);
  const accepted = {
    birth: ops.every(o=>o.metric==='human' && o.delta>0),
    conversion: paired(['human'],'converted'),
    death: paired(['human','converted'],'deaths'),
    disappearance: paired(['human','converted'],'missing'),
    migration: total===0 && positive.length && negative.length && ops.every(o=>o.metric===ops[0].metric && ['human','converted'].includes(o.metric)),
    equipment: false
  };
  assert(owns(accepted,event.type) && accepted[event.type], `Balanço incompatível com ${event.type}: ${event.id}`);
}
function apply(balances,event,owners) {
  for (const o of event.ops) {
    const row = balances[owners[o.cell]][o.cell];
    const value = row[o.metric] + o.delta;
    assert(count(value),`Saldo inválido em ${event.id} / ${o.cell} / ${o.metric}`);
    row[o.metric] = value;
  }
}
export function validate(data) {
  assert(plain(data) && data.version===1 && plain(data.demo), 'Formato de atlas incompatível.');
  for (const key of ['sources','milestones','observations','features','layers']) unique(data[key],key);
  const sources = new Set(data.sources.map(x=>x.id));
  for (const source of data.sources) assert(typeof source.title==='string' && (source.url===undefined || /^https:\/\//.test(source.url)), 'Fonte inválida.');
  for (const layer of data.layers) assert(typeof layer.title==='string' && /^#[a-fA-F0-9]{6}$/.test(layer.color),'Camada inválida.');
  const layers = new Set(data.layers.map(x=>x.id));
  assert(data.milestones.length>=2,'Linha do tempo incompleta.');
  for (const t of data.milestones) assert(validDate(t.date) && typeof t.title==='string' && typeof t.era==='string', 'Marco inválido.');
  assert(data.milestones.every((t,i)=>!i || t.date>data.milestones[i-1].date), 'Marcos fora de ordem ou com datas repetidas.');
  const sourceRecord = row => assert(sources.has(row.source) && owns(confidence,row.confidence), 'Proveniência inválida.');
  for (const row of data.observations) {
    sourceRecord(row); windowValid(row.when,true);
    assert(typeof row.title==='string' && typeof row.scope==='string' && typeof row.unit==='string'
      && (row.detail===undefined || typeof row.detail==='string') && (row.value===null || count(row.value)), 'Observação inválida.');
    assert((row.confidence==='unknown') === (row.value===null), 'Valor e confiabilidade incompatíveis.');
    assert(row.milestone===undefined || data.milestones.some(t=>t.id===row.milestone), 'Marco da observação inexistente.');
  }
  for (const f of data.features) {
    sourceRecord(f); windowValid(f.start,true); windowValid(f.end,true);
    assert(layers.has(f.layer) && owns(presences,f.presence) && geometryValid(f.geometry) && typeof f.title==='string' && typeof f.detail==='string', `Ficha inválida: ${f.id}`);
    assert(!f.start || !f.end || f.start.end < f.end.start,`Janela de operação ambígua: ${f.id}`);
  }

  const d = data.demo;
  assert(count(d.initialGlobal) && count(d.unallocatedGlobal ?? 0) && (d.unallocatedGlobal ?? 0) <= d.initialGlobal && validDate(d.start), 'Base demonstrativa inválida.');
  unique(d.countries,'países demo'); unique(d.regions,'regiões demo'); unique(d.events,'eventos demo');
  const owners = Object.create(null);
  for (const region of d.regions) {
    assert(d.countries.some(c=>c.id===region.country) && typeof region.title==='string' && count(region.weight)
      && geometryValid(region.geometry) && region.geometry?.type==='Polygon' && plain(region.replacements)
      && Array.isArray(region.cells) && region.cells.length, `Região inválida: ${region.id}`);
    for (const cell of region.cells) {
      assert(count(cell.weight) && parseCell(cell.id).level===d.dataLevel && !owns(owners,cell.id), `Célula inválida ou com dupla contagem: ${cell.id}`);
      owners[cell.id] = region.id;
    }
  }
  for (const country of d.countries) assert(typeof country.title==='string' && count(country.weight) && d.regions.some(r=>r.country===country.id),`País sem cobertura: ${country.id}`);
  const state = makeInitial(d);
  assert(aggregate(data,state).global.human + (d.unallocatedGlobal ?? 0)===d.initialGlobal,'Base global não reconciliada.');
  for (const event of d.events) {
    windowValid(event.when,true);
    assert(typeof event.title==='string' && Number.isSafeInteger(event.order) && (!event.when || event.when.start>=d.start), 'Evento inválido.');
    validateOps(event,owners);
  }
  // Valida a cronologia conservadora e o cenário alternativo de efeito na primeira data.
  for (const policy of ['end','start']) {
    const state = makeInitial(d);
    for (const event of dated(d.events,policy)) apply(state,event,owners);
    aggregate(data,state);
  }
  return owners;
}
function dated(events,policy) {
  return events.filter(e=>e.when).sort((a,b)=>a.when[policy].localeCompare(b.when[policy]) || a.order-b.order || a.id.localeCompare(b.id));
}
const empty = () => Object.fromEntries(metrics.map(key=>[key,0]));
function combine(rows) {
  return Object.fromEntries(metrics.map(key=>[key,sum(rows.map(row=>row[key]))]));
}
export function aggregate(data, balances) {
  const regions = Object.fromEntries(data.demo.regions.map(r=>[r.id,combine(Object.values(balances[r.id]))]));
  const countries = Object.fromEntries(data.demo.countries.map(c=>[c.id,combine(data.demo.regions.filter(r=>r.country===c.id).map(r=>regions[r.id]))]));
  return {regions,countries,global:combine(Object.values(countries))};
}
export function snapshot(data,date,owners,policy='end') {
  assert(validDate(date) && ['end','start'].includes(policy), 'Data ou política inválida.');
  if (date < data.demo.start) return null;
  const balances = makeInitial(data.demo);
  for (const event of dated(data.demo.events,policy)) {
    if (event.when[policy] > date) break;
    apply(balances,event,owners);
  }
  return {balances,...aggregate(data,balances)};
}
export function queryCell(state,id,dataLevel) {
  const {level} = parseCell(id);
  if (!state || level > dataLevel) return null;
  const rows = Object.values(state.balances).flatMap(group=>Object.entries(group))
    .filter(([child])=>parentAt(child,level)===id).map(([,row])=>row);
  return rows.length ? {metrics:combine(rows),count:rows.length,partial:rows.length<4**(dataLevel-level)} : null;
}
export function featureStatus(f,date) {
  if (f.start && date < f.start.start) return 'future';
  if (f.start && date < f.start.end) return 'uncertain';
  if (f.end && date >= f.end.end) return 'past';
  if (f.end && date >= f.end.start) return 'uncertain';
  return f.start ? 'active' : 'undated';
}

export function applyChanges(base, changes) {
  assert(plain(changes) && changes.version===1 && Array.isArray(changes.operations) && changes.operations.length<=2000, 'Arquivo de alterações incompatível.');
  const data = structuredClone(base);
  const replaced = new Set();
  const added = new Set();
  for (const change of changes.operations) {
    assert(plain(change), 'Operação inválida.');
    if (change.kind === 'replace-estimate') {
      const region = data.demo.regions.find(r=>r.id===change.region);
      assert(region && region.cells.some(c=>c.id===change.cell) && count(change.value), 'Substituição de estimativa inválida.');
      region.replacements[change.cell] = change.value;
    } else if (change.kind === 'add-event') {
      assert(change.event && !data.demo.events.some(e=>e.id===change.event.id) && !added.has(change.event.id), 'ID de evento repetido.');
      added.add(change.event.id);
      data.demo.events.push(change.event);
    } else if (change.kind === 'correct-event') {
      const index = data.demo.events.findIndex(e=>e.id===change.event?.id);
      assert(index>=0 && !replaced.has(change.event.id), 'Correção exige um evento existente e único.');
      replaced.add(change.event.id);
      data.demo.events[index] = change.event;
    } else {
      throw new Error('Tipo de alteração desconhecido.');
    }
  }
  return {data,owners:validate(data)};
}

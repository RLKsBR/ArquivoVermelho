import {allocate} from './model.mjs';

export const WORLD_CONTROL = 21_900_000_000;
export const CONVERSION_SHARE = .5;
export const CONVERTED_HALF_LIFE = 5;
const yearsToCollapse = 108;
const growth = {'Africa':.014,'Asia':.008,'Europe':.0035,'North America':.006,
  'South America':.008,'Oceania':.009};
const dayMs = 86_400_000;
const regionalYear = 365.2425;

export function buildWorld({countries,regions},base,legacy) {
  const countryById = new Map(countries.map(([id,iso,name,continent,population,source]) =>
    [id,{id,iso,name,continent,population,source,regions:[]}]));
  const unitById = new Map(regions.map(([id,country,name,area,urban,anchor,type,coordinates]) => {
    const parent = countryById.get(country);
    const unit = {id,country,name,area,urban,anchor,geometry:{type,coordinates}};
    parent.regions.push(unit);
    return [id,unit];
  }));
  const nationalWeights = [...countryById.values()].map(country=>({
    id:country.id,
    weight:Math.round(country.population*(1+(growth[country.continent]??.005))**yearsToCollapse)
  }));
  const nationalControl = allocate(WORLD_CONTROL,nationalWeights);
  const initial = [];
  for (const country of countryById.values()) {
    if (!country.population) continue;
    const urbanTotal = country.regions.reduce((sum,r)=>sum+r.urban,0);
    const areaTotal = country.regions.reduce((sum,r)=>sum+r.area,0);
    const weights = country.regions.map(r=>({
      id:r.id,weight:Math.round(1e9*(urbanTotal ? .85*r.urban/urbanTotal+.15*r.area/areaTotal : r.area/areaTotal))
    }));
    const split = allocate(nationalControl[country.id],weights);
    for (const unit of country.regions) {
      const size = split[unit.id];
      const urbanProxy = unit.urban/(unit.urban+unit.area*8);
      initial.push({id:unit.id,weight:size,urbanProxy});
    }
  }
  const documented = new Map(base.observations.filter(o=>o.metric==='human' && o.value!==null)
    .map(o=>[o.milestone,o.value]));
  let previousHuman = WORLD_CONTROL;
  let active = 0;
  let previousDate = null;
  const snapshots = base.milestones.flatMap(m=>{
    const documentary = documented.get(m.id);
    const human = m.id==='PRE'?WORLD_CONTROL:documentary??legacy.epochs[m.id]?.total;
    if (human==null) return [];
    const elapsed = previousDate===null?0:(Date.parse(m.date)-previousDate)/dayMs/regionalYear;
    active = Math.min(WORLD_CONTROL-human,Math.round(active*2**(-elapsed/CONVERTED_HALF_LIFE)
      +CONVERSION_SHARE*Math.max(0,previousHuman-human)));
    previousDate = Date.parse(m.date);
    previousHuman = human;
    return [{...m,human,converted:active,source:m.id==='PRE'?'scenario':documentary==null?'legacy':'documental'}];
  });
  return {countryById,unitById,initial,snapshots};
}

export function worldAt(world,date) {
  const anchor = world.snapshots.findLast(m=>m.date<=date);
  if (!anchor) return null;
  const relativeLoss = 1-anchor.human/WORLD_CONTROL;
  const humanWeights = world.initial.map(({id,weight,urbanProxy})=>({
    id,weight:Math.round(weight**(1-.17*relativeLoss)*(1-.55*relativeLoss*urbanProxy))
  }));
  const convertedWeights = world.initial.map(({id,weight,urbanProxy})=>({
    id,weight:Math.round(weight*(.45+.55*urbanProxy))
  }));
  const humans = allocate(anchor.human,humanWeights);
  const converted = allocate(anchor.converted,convertedWeights);
  const regions = new Map(world.initial.map(({id})=>[id,{human:humans[id],converted:converted[id]}]));
  const countries = new Map();
  for (const country of world.countryById.values()) {
    if (!country.population) continue;
    countries.set(country.id,country.regions.reduce((total,r)=>{
      const values=regions.get(r.id);
      total.human+=values.human;
      total.converted+=values.converted;
      return total;
    },{human:0,converted:0}));
  }
  return {anchor,regions,countries};
}

export const symbolClasses = [
  [1,1.6],[100,2.5],[1_000,3.5],[10_000,4.6],[100_000,6],
  [1_000_000,7.7],[10_000_000,9.6],[100_000_000,12]
];
export function symbolFor(count) {
  return symbolClasses.findLast(([threshold])=>count>=threshold)?.[1]??0;
}

export function regionStyle(values, area) {
  if (!values) return {kind:'unknown',opacity:0};
  const total = values.human + values.converted;
  const density = total / area;
  if (density<.5) return {kind:'sparse',opacity:.15};
  const fraction = values.human/total;
  const kind = fraction>=.8?'human':fraction<=.2?'converted':'mixed';
  return {kind,opacity:Math.min(.74,.26+Math.log10(1+density)*.19)};
}

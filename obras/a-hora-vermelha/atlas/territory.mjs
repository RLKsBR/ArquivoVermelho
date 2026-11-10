const radius=6371007.1809;
const rad=Math.PI/180;
const sqm=n=>Number.isFinite(n)&&n>0;
const coordinate=pair=>Array.isArray(pair)&&pair.length===2&&pair.every(Number.isFinite)
  &&Math.abs(pair[0])<=180&&Math.abs(pair[1])<=90;
const categories=['property','building','city','port'];
const ringValid=ring=>Array.isArray(ring)&&ring.length>=4&&ring.every(coordinate)
  &&ring[0].every((value,i)=>value===ring.at(-1)[i]);
const polygonValid=coordinates=>Array.isArray(coordinates)&&coordinates.length>0&&coordinates.every(ringValid);
function geographic(shape) {
  return shape?.type==='Polygon'&&polygonValid(shape.coordinates)
    || shape?.type==='MultiPolygon'&&Array.isArray(shape.coordinates)&&shape.coordinates.length>0&&shape.coordinates.every(polygonValid);
}
export function validateTerritories(records) {
  const ids=new Set();
  for (const r of records) {
    if (!/^[a-z0-9-]+$/.test(r.id)||ids.has(r.id)||!categories.includes(r.category)
      ||!r.title||!r.source||!r.note||!(r.anchor===null||coordinate(r.anchor))
      ||!(r.area===null||sqm(r.area))
      ||(r.dimensions&&(!Array.isArray(r.dimensions)||r.dimensions.length!==2||!r.dimensions.every(sqm)
      ||Math.abs(r.dimensions[0]*r.dimensions[1]-r.area)>.01))
      ||(r.outline&&(!Array.isArray(r.outline)||r.outline.length<3
      ||!r.outline.every(p=>Array.isArray(p)&&p.length===2&&p.every(Number.isFinite))
      ||Math.abs(planarArea(r.outline)-r.area)>.01))
      ||(r.geometry&&!geographic(r.geometry))
      ||!Array.isArray(r.facts)||!r.facts.every(row=>Array.isArray(row)&&row.length===2&&row.every(value=>typeof value==='string'))) {
      throw new Error('Ficha territorial inválida: '+r.id);
    }
    ids.add(r.id);
  }
  return records;
}
export function planarArea(points) {
  return Math.abs(points.reduce((sum,[x,y],i)=>{
    const [nextX,nextY]=points[(i+1)%points.length];
    return sum+x*nextY-y*nextX;
  },0))/2;
}
export function footprint(r) {
  if (r.outline) return {points:r.outline,method:'implantação esquemática'};
  if (!r.area) return null;
  const [width,height]=r.dimensions??[Math.sqrt(r.area),Math.sqrt(r.area)];
  return {points:[[-width/2,-height/2],[width/2,-height/2],[width/2,height/2],[-width/2,height/2]],
    method:r.dimensions?'dimensões definidas':'quadrado de área equivalente'};
}
export function territoryGeometry(r,schematic=true) {
  if (r.geometry) return r.geometry;
  if (!schematic||!r.anchor) return null;
  const shape=footprint(r);
  if (!shape) return null;
  const [lon,lat]=r.anchor;
  const c=Math.cos(lat*rad), s=Math.sin(lat*rad);
  if (c<1e-6) return null;
  const coords=shape.points.map(([x,y])=>{
    const p=s+y*c/radius;
    return [lon+x/(radius*c*rad),Math.abs(p)<=1?Math.asin(p)/rad:NaN];
  });
  if (!coords.every(coordinate)) return null;
  return {type:'Polygon',coordinates:[[...coords,coords[0]]]};
}

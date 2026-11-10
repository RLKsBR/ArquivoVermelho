import {ASPECT, cellAt, cellId, cellArea, cellBounds, gridForViewport, parseCell, parentAt, project, unproject} from './geo.mjs';
import {applyChanges, censusMetrics, censusRecord, confidence, featureStatus, livingPresence, metrics, presences, queryCell, ratio, snapshot, validate} from './model.mjs';
import {footprint, territoryGeometry, validateTerritories} from './territory.mjs';

const $ = id => document.getElementById(id);
const fmt = new Intl.NumberFormat('pt-BR');
const decimal = new Intl.NumberFormat('pt-BR',{maximumFractionDigits:2});
const displayDate = date => date.split('-').reverse().join('/');
const number = value => value == null ? 'Sem dados' : fmt.format(value);
const signs = value => (value>0?'+':'') + fmt.format(value);
const normalize = text => text.normalize('NFD').replace(/\p{Diacritic}/gu,'').toLowerCase();
const metricNames = {human:'Humanos vivos',converted:'Convertidos vivos',deaths:'Mortes acumuladas',missing:'Desaparecidos'};
const metricColors = {human:'#63d999',converted:'#ff625c',deaths:'#b8a9f0',missing:'#7dc8e6'};
const societyColor = '#f8ce7c';
const groupColor = '#64b5ff';
const territoryLabels = {property:'Propriedade',building:'Edifício',city:'Cidade ou aglomeração',port:'Porto'};
const territoryColors = {property:'#c4a7fa',building:'#c4a7fa',city:'#a6cfc8',port:'#f09e63'};
const legacyCategories = ['Grandes concentrações','Comunidades aliadas','Núcleos intermediários','Grupos dispersos','Urbano ou transição'];
const statusNames = {future:'Futuro na data selecionada',past:'Encerrado na data selecionada',uncertain:'Janela de data incerta',active:'Vigente na data selecionada',undated:'Sem data definida'};
const viewNames = {canon:'SÉRIE DOCUMENTAL',legacy:'ATLAS V2 · CENÁRIO ANTERIOR',demo:'DEMONSTRAÇÃO · DADOS SINTÉTICOS'};
const storageKey = 'ahv-atlas:edits:v1';

function element(tag,text,className) {
  const node = document.createElement(tag);
  if (text!==undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function button(text,onClick) {
  const node = element('button',text);
  node.type='button';
  node.addEventListener('click',onClick);
  return node;
}
function dateWindow(when) {
  if (!when) return 'Data indefinida';
  return when.start===when.end ? displayDate(when.start) : `${displayDate(when.start)} a ${displayDate(when.end)}`;
}
function addOptions(select, options) {
  select.replaceChildren(...options.map(([value,label]) => new Option(label,value)));
}
function badge(type) {
  return element('span',confidence[type],`badge badge-${type}`);
}
function stat(target,label,value,detail,hero=false,change='') {
  const block = element('div',undefined,hero?'stat hero':'stat');
  block.append(element('span',label),element('strong',value));
  if (change) block.append(element('small',change,'difference'));
  if (detail) block.append(element('small',detail));
  target.append(block);
}

async function readJson(path) {
  const result = await fetch(path);
  if (!result.ok) throw new Error(`Falha ao carregar ${path}: ${result.status}`);
  return result.json();
}

async function start() {
  const [base, geography, legacy] = globalThis.ATLAS_PACKED ?? await Promise.all([
    readJson('./data/atlas.json'), readJson('./data/geography.json'), readJson('./data/atlas-v2.json')
  ]);
  const [territoryRows, dispersed] = await Promise.all([readJson('./data/territories.json'),readJson('./data/dispersed-v2.json')]);
  const territories=validateTerritories(territoryRows);
  let changes = {version:1,operations:[]};
  let data = base;
  let owners = validate(base);
  let storageMessage = '';
  try {
    const saved = localStorage.getItem(storageKey);
    if (saved) {
      const proposed = JSON.parse(saved);
      ({data,owners}=applyChanges(base,proposed));
      changes=proposed;
    }
  } catch {
    storageMessage='Os ajustes armazenados não puderam ser lidos. Use a importação ou trabalhe com a base.';
  }

  let view='canon';
  let mode='region';
  let selection=null;
  let date=base.milestones.find(t=>t.id==='D0').date;
  let comparing=false;
  let worldFit=true;
  let grid;
  let state;
  let referenceState;
  const active = new Set(['societies','history','energy','places','routes','occupation','communications']);
  const censuses = data.localCensuses??[];
  const sources = Object.fromEntries(data.sources.map(s=>[s.id,s]));
  const layers = Object.fromEntries(data.layers.map(l=>[l.id,l]));
  const map = L.map('map',{
    crs: L.extend({},L.CRS,{
      projection:{
        project(latlng) {return L.point(...project(latlng.lat,latlng.lng));},
        unproject(p) {return L.latLng(...unproject(p.x,p.y));},
        bounds:L.bounds([0,0],[1,ASPECT])
      },
      transformation:new L.Transformation(1,0,1,0),
      distance:(a,b)=>L.CRS.Earth.distance(a,b)
    }),
    minZoom:0,maxZoom:19,zoomSnap:.25,zoomDelta:1,
    maxBounds:[[-90,-180],[90,180]],maxBoundsViscosity:1,
    zoomControl:false,attributionControl:false
  });
  const pane = (name,z) => {map.createPane(name).style.zIndex=String(z);return name;};
  const physicalPane=pane('physical',250);
  const borderPane=pane('boundaries',290);
  const gridPane=pane('coordinates',340);
  const themePane=pane('themes',390);
  const labelPane=pane('labels',370);
  const selectedPane=pane('highlight',440);
  const physical=L.canvas({pane:physicalPane,padding:.22});
  const borderRenderer=L.canvas({pane:borderPane,padding:.1});
  const gridRenderer=L.canvas({pane:gridPane,padding:.12});
  const themeRenderer=L.canvas({pane:themePane,padding:.12});
  const highlightRenderer=L.canvas({pane:selectedPane,padding:.1});
  const gridGroup=L.layerGroup().addTo(map);
  const themeGroup=L.layerGroup().addTo(map);
  const selectionGroup=L.layerGroup().addTo(map);
  const oceanGroup=L.layerGroup();
  const landOptions={pane:physicalPane,renderer:physical,interactive:false,style:{fillColor:'#253b38',fillOpacity:1,color:'#60817a',weight:.9,opacity:.85},smoothFactor:.65};
  const landLow=L.geoJSON(geography.land,landOptions);
  let landDetail;
  let landDisplayed;
  const borders=L.geoJSON(geography.borders,{pane:borderPane,renderer:borderRenderer,interactive:false,style:{color:'#b6a99a',weight:1,opacity:.7,dashArray:'4 3'}});
  for (const [label,lat,lon] of [['PACÍFICO',-12,-125],['ATLÂNTICO',-16,-25],['ÍNDICO',-27,85],['PACÍFICO',8,160],['ANTÁRTIDA',-76,0]]) {
    L.marker([lat,lon],{pane:labelPane,interactive:false,keyboard:false,icon:L.divIcon({className:'ocean-label',html:label,iconSize:[100,16],iconAnchor:[50,8]})}).addTo(oceanGroup);
  }
  L.control.scale({position:'bottomleft',imperial:false,maxWidth:90}).addTo(map);
  L.control.zoom({position:'bottomright',zoomInTitle:'Aproximar',zoomOutTitle:'Afastar'}).addTo(map);

  function sourceLink(target, id) {
    const source=sources[id];
    if (!source.url) {
      target.append(element('p',`Fonte · ${source.title}`,'source-note'));
      return;
    }
    const link=element('a',source.title);
    link.href=source.url;
    link.target='_blank';
    link.rel='noopener noreferrer';
    const p=element('p',undefined,'muted');
    p.append(link);
    target.append(p);
  }
  function fitWorld() {
    const size=map.getSize();
    const zoom=Math.max(0,Math.log2(Math.min((size.x-22)/256,(size.y-32)/(256*ASPECT))));
    map.setView([0,0],Math.floor(zoom*4)/4,{animate:false});
    worldFit=true;
  }
  function switchLand() {
    const detail=map.getZoom()>=5;
    if (detail && !landDetail) landDetail=L.geoJSON(geography.detailed,landOptions);
    const next=detail?landDetail:landLow;
    if (landDisplayed!==next) {
      if (landDisplayed) map.removeLayer(landDisplayed);
      next.addTo(map);
      landDisplayed=next;
    }
    if (map.getZoom()<=3 && !map.hasLayer(oceanGroup)) oceanGroup.addTo(map);
    if (map.getZoom()>3 && map.hasLayer(oceanGroup)) map.removeLayer(oceanGroup);
  }
  function choose(next, scroll=false) {
    const territoryChanged=selection?.kind==='territory'||next?.kind==='territory';
    selection=next;
    if (territoryChanged&&view==='canon') drawThemes();
    drawSelection();
    renderPanel();
    if (scroll && matchMedia('(max-width:790px)').matches) {
      $('inspector').scrollIntoView({block:'nearest',behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'});
    }
  }
  function drawSelection() {
    selectionGroup.clearLayers();
    if (selection?.kind==='cell') {
      L.rectangle(cellBounds(selection.id),{pane:selectedPane,renderer:highlightRenderer,interactive:false,color:'#ffd2a4',weight:2,fillOpacity:.12}).addTo(selectionGroup);
    }
  }
  function drawGrid() {
    const bounds=map.getBounds();
    grid=gridForViewport([bounds.getSouth(),bounds.getWest()],[bounds.getNorth(),bounds.getEast()],map.getZoom(),mode);
    const area=cellArea(cellId(grid.level,0,0));
    $('map-level').textContent=`GRADE · EA1 / N${grid.level}`;
    $('map-cell-area').textContent=area>=1?`${decimal.format(area)} km² por célula`:`${fmt.format(Math.round(area*1e6))} m² por célula`;
    $('map-level').dataset.count=String(grid.count);
    gridGroup.clearLayers();
    if (!$('show-grid').checked) return;
    const metric=$('metric').value;
    const visible = [];
    for (let x=grid.first.col;x<=grid.last.col;x++) {
      for (let y=grid.first.row;y<=grid.last.row;y++) {
        const id=cellId(grid.level,x,y);
        visible.push({bounds:cellBounds(id),result:view==='demo' && metric ? queryCell(state,id,data.demo.dataLevel):null});
      }
    }
    const max = Object.fromEntries(metrics.map(key=>[key,Math.max(1,...visible.map(c=>c.result?.metrics[key]??0))]));
    const options={pane:gridPane,renderer:gridRenderer,interactive:false};
    for (const {bounds,result} of visible) {
      const values=result?.metrics??{};
      const presence=livingPresence(values,Boolean(result&&!result.partial));
      L.rectangle(bounds,{...options,color:presence==='empty'?'#9cb1a3':'#6d888e',
        opacity:result?.partial ? .52 : .29,weight:.7,fill:false,dashArray:view==='demo'&&!result?'2 4':undefined
      }).addTo(gridGroup);
      const fill = (key,box) => {
        if (values[key]>0) L.rectangle(box,{...options,stroke:false,fillColor:metricColors[key],
          fillOpacity:.28+.47*Math.sqrt(values[key]/max[key])}).addTo(gridGroup);
      };
      if (metric==='presence') {
        if (presence==='mixed') {
          const [sw,ne]=bounds;
          const middle=(sw[1]+ne[1])/2;
          fill('human',[sw,[ne[0],middle]]);
          fill('converted',[[sw[0],middle],ne]);
        } else if (presence==='human'||presence==='converted') fill(presence,bounds);
      } else if (metric) fill(metric,bounds);
    }
  }
  function epochFor(day) {
    return data.milestones.find(t=>t.date===day)?.id;
  }
  function currentFeatures() {
    return data.features.filter(f=>view==='demo'?f.confidence==='demo':f.confidence!=='demo'&&!censuses.some(site=>site.title===f.title));
  }
  function visibleStatus(status) {
    return status==='active' || ($('show-uncertain').checked && ['uncertain','undated'].includes(status));
  }
  function showMarker(latlng,options,next) {
    const mark=L.circleMarker(latlng,{pane:themePane,renderer:themeRenderer,bubblingMouseEvents:false,...options}).addTo(themeGroup);
    mark.on('click',()=>choose(next,true));
    return mark;
  }
  function drawThemes() {
    themeGroup.clearLayers();
    if (view==='legacy') {
      const epoch=legacy.epochs[epochFor(date)];
      if (!epoch) return;
      drawDispersed();
      for (const [id,row] of Object.entries(epoch.rows)) {
        const [, ,lon,lat]=legacy.regions[id];
        showMarker([lat,lon],{color:row[5]?'#ffe0a5':row[0]>0?'#7ce1ac':'#96a9a5',fillColor:metricColors.human,weight:row[5]?2:1,fillOpacity:row[0]>0?.40:0,radius:Math.max(4,Math.min(13,Math.log10(Math.max(1,row[0]))*1.1))},{kind:'legacy-region',id});
      }
      if ($('show-legacy-records').checked && active.has('societies')) {
        legacy.records.forEach((r,index)=>{
          if (r.epoch!==epochFor(date) || !r.coordinates) return;
          showMarker([...r.coordinates].reverse(),{color:'#46391e',fillColor:societyColor,weight:1.3,fillOpacity:.98,radius:5},{kind:'legacy-record',id:index});
        });
      }
      return;
    }
    if (view==='demo') {
      for (const r of data.demo.regions) {
        L.geoJSON(r.geometry,{pane:themePane,renderer:themeRenderer,interactive:false,
          style:{color:'#d6af82',weight:1,fillOpacity:0,dashArray:'6 5'}}).addTo(themeGroup);
      }
    }
    if (view==='canon') {
      drawTerritories();
      drawCensusMarkers();
    }
    for (const f of currentFeatures()) {
      const status=featureStatus(f,date);
      if (!f.geometry || !active.has(f.layer) || !visibleStatus(status)) continue;
      const color=layers[f.layer].color;
      const next={kind:'feature',id:f.id};
      if (f.geometry.type==='Point') {
        showMarker([...f.geometry.coordinates].reverse(),{color:'#272526',fillColor:color,weight:1.3,fillOpacity:status==='active'?.95:.52,radius:7},next);
      } else {
        const surveyed=f.layer==='occupation' && f.presence==='absent';
        const stroke=surveyed?'#9cb1a3':f.presence==='unknown'||f.presence==='estimatedAbsent'?'#a1b3b2':color;
        L.geoJSON(f.geometry,{pane:themePane,renderer:themeRenderer,bubblingMouseEvents:false,
          style:{color:stroke,weight:2,fillOpacity:surveyed||f.presence==='unknown'||f.presence==='estimatedAbsent'?0:.22,
            dashArray:surveyed?undefined:'5 5'}}).on('click',()=>choose(next,true)).addTo(themeGroup);
      }
    }
  }

  function geographicFootprint(record) {
    return territoryGeometry(record,$('show-schematic').checked && record.schematicOnMap!==false);
  }

  function drawTerritories() {
    if (!$('show-territories').checked) return;
    for (const record of territories) {
      if (!record.anchor && !record.geometry) continue;
      const selected=selection?.kind==='territory' && selection.id===record.id;
      if (record.mapWhenSelected && !selected) continue;
      if (record.category==='city' && (!$('show-cities').checked || (map.getZoom()<4 && !selected))) continue;
      if (record.category==='port' && !$('show-ports').checked) continue;
      const color=territoryColors[record.category];
      const geometry=geographicFootprint(record);
      const next={kind:'territory',id:record.id};
      if (geometry) {
        const shape=L.geoJSON(geometry,{pane:themePane,renderer:themeRenderer,bubblingMouseEvents:false,
          style:{color,weight:selected?2.6:1.5,fillColor:color,fillOpacity:selected?.21:.095,dashArray:record.geometry?undefined:'5 4'}});
        const bounds=shape.getBounds();
        const size=map.latLngToLayerPoint(bounds.getNorthEast()).distanceTo(map.latLngToLayerPoint(bounds.getSouthWest()));
        if (size>=12) {
          shape.on('click',()=>choose(next,true)).addTo(themeGroup);
          continue;
        }
      }
      if (!record.anchor) continue;
      const icon=L.divIcon({className:`territory-marker territory-${record.category}`,iconSize:[10,8],iconAnchor:[5,4]});
      L.marker([...record.anchor].reverse(),{pane:themePane,icon,title:record.title,alt:record.title,bubblingMouseEvents:false})
        .on('click',()=>focusItem(next)).addTo(themeGroup);
    }
  }

  function drawDispersed() {
    if (!$('show-dispersed').checked) return;
    for (const group of dispersed[epochFor(date)]??[]) {
      L.geoJSON({type:'Polygon',coordinates:group.rings},{pane:themePane,renderer:themeRenderer,bubblingMouseEvents:false,
        style:{color:groupColor,weight:1.1,opacity:.68,fillColor:groupColor,fillOpacity:.16,dashArray:'4 5'}})
        .on('click',()=>choose({kind:'dispersed',id:group.region},true)).addTo(themeGroup);
    }
  }

  function areaLabel(area) {
    if (area===null) return 'Sem dados';
    return area>=1e6?`${decimal.format(area/1e6)} km²`:`${number(area)} m²`;
  }

  function footprintDiagram(record,shape) {
    const svgNS='http://www.w3.org/2000/svg';
    const points=shape.points.map(([x,y])=>[x,-y]);
    const xs=points.map(p=>p[0]), ys=points.map(p=>p[1]);
    const width=Math.max(...xs)-Math.min(...xs), height=Math.max(...ys)-Math.min(...ys);
    const padding=Math.max(width,height)*.09;
    const svg=document.createElementNS(svgNS,'svg');
    const polygon=document.createElementNS(svgNS,'polygon');
    svg.setAttribute('viewBox',`${Math.min(...xs)-padding} ${Math.min(...ys)-padding} ${width+padding*2} ${height+padding*2}`);
    svg.setAttribute('role','img');
    svg.setAttribute('aria-label',`Planta de ${record.title}: ${shape.method}`);
    svg.classList.add('footprint-diagram');
    polygon.setAttribute('points',points.map(p=>p.join(',')).join(' '));
    polygon.setAttribute('vector-effect','non-scaling-stroke');
    svg.append(polygon);
    return svg;
  }

  function showTerritory(panel,record) {
    panel.append(element('span',territoryLabels[record.category],'badge'),element('h2',record.title));
    if (record.reference) panel.append(element('p',record.reference,'muted'));
    const shape=footprint(record);
    stat(panel,record.category==='building'?'Área da base do prédio':record.category==='city'?'Área urbana de referência':'Área por terreno ou instalação',
      areaLabel(record.area),record.dimensions?`${number(record.dimensions[0])} × ${number(record.dimensions[1])} m`:undefined,true);
    if (shape) {
      panel.append(footprintDiagram(record,shape),element('p',`Planta · ${shape.method}`,'source-note'));
    }
    for (const [label,value] of record.facts) stat(panel,label,value);
    panel.append(element('p',record.note,'notice'),element('p',`Fonte · ${record.source}`,'source-note'));
  }

  function showDispersed(panel,id) {
    const region=legacy.regions[id];
    const group=(dispersed[epochFor(date)]??[]).find(row=>row.region===id);
    panel.append(badge('estimate'),element('h2',region[0]),element('p','Grupos humanos dispersos · Atlas V2','muted'));
    if (!group) {
      panel.append(element('p','Nenhuma mancha modelada no marco selecionado.','notice'));
      return;
    }
    stat(panel,'Pessoas nos grupos dispersos',number(group.population),undefined,true);
    stat(panel,'Número estimado de grupos',number(group.groups));
    stat(panel,'Faixa de tamanho por grupo',`${number(group.min)} a ${number(group.max)} pessoas`);
    panel.append(element('p','A mancha mostra uma região de referência. Os locais individuais e a distribuição dentro dela estão em aberto. Esta população já integra o cenário regional.','notice'));
  }

  function drawCensusMarkers() {
    for (const site of censuses) {
      if (!site.coordinates || (site.kind==='society'&&!active.has('societies')) || (site.kind==='event'&&!active.has('history'))) continue;
      const record=censusRecord(site,date);
      const current=record?.at===date;
      const presence=current?livingPresence(record.counts):'unknown';
      const latlng=[...site.coordinates].reverse();
      const next={kind:'census',id:site.id};
      if (presence==='human'||presence==='converted'||presence==='mixed') {
        const color=presence==='converted'?metricColors.converted:metricColors.human;
        showMarker(latlng,{color,fillColor:color,weight:1.8,fillOpacity:.27,radius:11},next);
        if (presence==='mixed') showMarker(latlng,{color:metricColors.converted,weight:2,fill:false,radius:8},next);
      }
      if (site.kind==='society') {
        showMarker(latlng,{color:current?'#fff1c4':societyColor,fillColor:societyColor,weight:1.3,
          fillOpacity:current?1:.20,radius:5.3},next);
      } else if (presence==='unknown') {
        showMarker(latlng,{color:'#96a9a5',weight:1.2,fillOpacity:0,dashArray:'2 3',radius:5.3},next);
      }
    }
  }

  function localCensusList(panel) {
    if (!censuses.length) return;
    const entries=element('div',undefined,'record-buttons census-cards');
    panel.append(element('h3','Censos por lugar'),element('p','Abra uma ficha para consultar seus marcos e as contagens de cada grupo.','muted'),entries);
    for (const site of censuses) {
      const open=button(site.title,()=>focusItem({kind:'census',id:site.id}));
      open.append(element('small',site.coordinates?'Ver ficha e referência no mapa':'Ver ficha · posição em aberto'));
      entries.append(open);
    }
  }

  function showCensus(panel,site) {
    const record=censusRecord(site,date);
    panel.append(badge(record?.confidence??'unknown'),element('h2',site.title),element('p',site.where,'muted'));
    if (record) {
      const selected=record.at===date;
      const caption=record.at ? (selected?'Marco consultado':'Último registro anterior') : 'Sem data definida';
      stat(panel,caption,record.label,record.at&&!selected?'O registro não confirma a situação na data selecionada.':undefined);
      for (const key of ['human','converted']) {
        stat(panel,censusMetrics[key],number(record.counts[key]),undefined,key==='converted'&&record.counts[key]!==undefined);
      }
      for (const [key,value] of Object.entries(record.counts)) {
        if (key!=='human'&&key!=='converted') stat(panel,censusMetrics[key],number(value));
      }
      panel.append(element('p',record.detail,'notice'));
      sourceLink(panel,record.source);
    } else {
      panel.append(element('p','Sem observação local até a data selecionada. Escolha um registro da ficha.','muted'));
    }
    const options=site.records.filter(row=>row.at);
    if (options.length) {
      const label=element('label','Registros da ficha','wide-label');
      const select=element('select');
      addOptions(select,[['','Escolher registro'],...options.map(row=>[row.at,row.label])]);
      if (record?.at) select.value=record.at;
      select.addEventListener('change',()=>{if (select.value) chooseDate(select.value);});
      label.append(select);
      panel.append(label);
    }
  }

  function compareStat(target,value,reference) {
    stat(target,'Diferença para a referência',value==null || reference==null?'Sem comparação':signs(value-reference),
      reference==null?'A referência não tem valor para este recorte.':`Referência de ${displayDate($('compare-date').value)}: ${number(reference)}`);
  }
  function summaryCanon(panel) {
    const key=epochFor(date);
    const rows=data.observations.filter(row=>row.milestone===key || row.value===null);
    const human=rows.find(r=>r.metric==='human');
    panel.append(badge(human?'canon':'unknown'),element('h2','População da Terra'));
    stat(panel,'Humanos vivos sem conversão permanente',number(human?.value),human?dateWindow(human.when):'A série documental tem seis marcos. Selecione um deles para consultar o valor.',true);
    if (human) {
      panel.append(element('p',human.detail,'muted'));
      sourceLink(panel,human.source);
    }
    if (comparing) {
      const reference=data.observations.find(r=>r.metric==='human'&&r.milestone===epochFor($('compare-date').value));
      compareStat(panel,human?.value,reference?.value);
    }
    localCensusList(panel);
    for (const row of rows.filter(r=>r!==human)) stat(panel,row.title,number(row.value),row.detail);
    const cards=currentFeatures().filter(f=>active.has(f.layer) && (f.start ? date>=f.start.start && date.slice(0,7)===f.start.start.slice(0,7) : visibleStatus('undated')));
    if (cards.length) {
      panel.append(element('h3','Fichas documentais'));
      const entries=element('div',undefined,'record-buttons');
      for (const f of cards) entries.append(button(f.title,()=>choose({kind:'feature',id:f.id})));
      panel.append(entries);
    }
  }
  function summaryLegacy(panel) {
    const epoch=legacy.epochs[epochFor(date)];
    panel.append(badge('hypothesis'),element('h2','Cenário mundial V2'));
    stat(panel,'Humanos não convertidos · controle do V2',number(epoch?.total),
      epoch?`62 regiões. Reserva sem localização: ${number(epoch.reserve)}.`:'Selecione um dos 12 marcos do Atlas V2.',true);
    if (epochFor(date)==='D0') panel.append(element('p','D0 documental registra 21,338 bilhões. O V2 adotou 18,5 bilhões como projeção.','notice'));
    if (epoch) {
      const issues=Object.values(epoch.rows).filter(row=>row[5]!==0).length;
      stat(panel,'Verificação da organização regional',`${issues} divergência(s)`,'O aro amarelo sinaliza uma região cuja soma das categorias difere da reserva modelada.');
    }
    if (comparing) compareStat(panel,epoch?.total,legacy.epochs[epochFor($('compare-date').value)]?.total);
    panel.append(element('p','Os símbolos são centros aproximados. As classes do V2 descrevem organização e presença, sem delimitar soberania.','muted'));
    panel.append(element('p','O verde resume humanos não convertidos do cenário. O azul indica manchas de grupos dispersos. Os pontos dourados indicam núcleos de referência. O V2 não fornece um estoque regional de convertidos vivos.','muted'));
    sourceLink(panel,'v2');
  }
  function balancesPanel(panel, row, reference, coverage) {
    for (const key of metrics) {
      const delta=comparing && row && reference ? `Δ ${signs(row[key]-reference[key])} ante a referência` : '';
      stat(panel,metricNames[key],number(row?.[key]),key==='human'?coverage:undefined,key==='human',delta);
    }
  }
  function summaryDemo(panel) {
    panel.append(badge('demo'),element('h2','Distribuição de teste'));
    balancesPanel(panel,state?.global,referenceState?.global,'Brasil e Angola · cobertura parcial sintética');
    const regionButtons=element('div',undefined,'record-buttons');
    for (const r of data.demo.regions) {
      regionButtons.append(button(r.title,()=>focusItem({kind:'demo-region',id:r.id})));
    }
    panel.append(element('h3','Regiões técnicas'),regionButtons);
    const rows=data.demo.events.filter(e=>!e.when || e.when.start<=date);
    if (rows.length) {
      const list=element('ul',undefined,'list-compact');
      rows.forEach(e=>list.append(element('li',e.title+' · '+dateWindow(e.when))));
      panel.append(element('h3','Lançamentos e pendências'),list);
    }
  }
  function renderPanel() {
    const panel=$('panel');
    panel.replaceChildren();
    $('panel-tag').textContent=viewNames[view];
    const id=selection?.id;
    if (!selection) {
      if (view==='canon') summaryCanon(panel);
      if (view==='legacy') summaryLegacy(panel);
      if (view==='demo') summaryDemo(panel);
    } else if (selection.kind==='territory') {
      showTerritory(panel,territories.find(row=>row.id===id));
    } else if (selection.kind==='dispersed') {
      showDispersed(panel,id);
    } else if (selection.kind==='census') {
      showCensus(panel,censuses.find(site=>site.id===id));
    } else if (selection.kind==='cell') {
      const level=parseCell(id).level;
      const values=view==='demo'?queryCell(state,id,data.demo.dataLevel):null;
      panel.append(badge(values?'demo':'unknown'),element('h2',id));
      const bounds=cellBounds(id);
      stat(panel,'Área na esfera de referência',`${decimal.format(cellArea(id))} km²`,bounds.map(p=>p.map(v=>v.toFixed(5)).join(', ')).join('  ↔  '));
      if (values) {
        balancesPanel(panel,values.metrics,queryCell(referenceState,id,data.demo.dataLevel)?.metrics,
          `${values.count} célula(s) de origem${values.partial?' · cobertura parcial':''}`);
        const density=ratio(values.metrics.human,cellArea(id)*values.count/4**(data.demo.dataLevel-level));
        stat(panel,'Densidade na área coberta',`${decimal.format(density)} pessoas/km²`,'Área total das células de origem, inclusive água quando presente.');
      } else {
        panel.append(element('p',view==='demo' && level>data.demo.dataLevel
          ?'A célula está abaixo da resolução populacional cadastrada. Aproxime ou afaste livremente. As contagens só aparecem no nível 10 ou acima na hierarquia.'
          :'Não há contagem localizada nesta célula, para este recorte e data.','muted'));
        if (view==='demo' && level>data.demo.dataLevel) panel.append(button('Consultar célula de origem',()=>choose({kind:'cell',id:parentAt(id,data.demo.dataLevel)})));
      }
    } else if (selection.kind==='feature') {
      const f=data.features.find(row=>row.id===id);
      panel.append(badge(f.confidence),element('h2',f.title),element('p',f.detail));
      stat(panel,'Presença',presences[f.presence],statusNames[featureStatus(f,date)]);
      stat(panel,'Início',dateWindow(f.start),f.end?`Encerramento: ${dateWindow(f.end)}`:null);
      if (!f.geometry) panel.append(element('p','Localização precisa indisponível. Ficha consultável no catálogo.','muted'));
      sourceLink(panel,f.source);
    } else if (selection.kind==='demo-region') {
      const region=data.demo.regions.find(r=>r.id===id);
      panel.append(badge('demo'),element('h2',region.title));
      balancesPanel(panel,state?.regions[id],referenceState?.regions[id],'Subtotal das células cadastradas no recorte.');
      stat(panel,'Subtotal nacional coberto',number(state?.countries[region.country].human),'Cenário sintético de cobertura parcial.');
    } else if (selection.kind==='legacy-region') {
      const epoch=legacy.epochs[epochFor(date)];
      const row=epoch?.rows[id];
      panel.append(badge('hypothesis'),element('h2',legacy.regions[id][0]));
      stat(panel,'Humanos não convertidos · V2',number(row?.[0]),legacy.regions[id][1],true);
      if (row) {
        stat(panel,'Documentados no cadastro V2',number(row[1]));
        stat(panel,'Reserva modelada',number(row[2]),legacyCategories[row[4]]);
        stat(panel,'Cativos registrados separadamente',number(row[3]),'Componente já incluído no total acima.');
        if (row[5]) panel.append(element('p',`Divergência nas categorias: ${signs(row[5])} em relação à reserva modelada.`,'notice'));
      }
      if ((dispersed[epochFor(date)]??[]).some(group=>group.region===id)) {
        panel.append(button('Consultar grupos dispersos desta região',()=>choose({kind:'dispersed',id},true)));
      }
      if (comparing) compareStat(panel,row?.[0],legacy.epochs[epochFor($('compare-date').value)]?.rows[id]?.[0]);
      sourceLink(panel,'v2');
    } else if (selection.kind==='legacy-record') {
      const r=legacy.records[id];
      panel.append(badge('hypothesis'),element('h2',r.title));
      stat(panel,r.scope,number(r.value),`${r.epoch} · ${r.status}`,true);
      panel.append(element('p',r.where??'Localização de referência do cadastro V2.','muted'));
      if (!r.coordinates) panel.append(element('p','Sem ponto geográfico definido.','muted'));
      sourceLink(panel,'v2');
    }
  }

  function refresh() {
    if (view==='demo') {
      state=snapshot(data,date,owners,$('policy').value);
      referenceState=comparing?snapshot(data,$('compare-date').value,owners,$('policy').value):null;
    } else {
      state=null;
      referenceState=null;
    }
    drawGrid();
    drawThemes();
    drawSelection();
    renderPanel();
  }
  function updateTimeline() {
    const index=data.milestones.findLastIndex(t=>t.date<=date);
    const chosen=data.milestones[index];
    const current=chosen?.date===date;
    $('date').value=date;
    $('timeline-range').value=String(Math.max(0,index));
    $('period-title').textContent=current?`${chosen.title} · ${displayDate(date)}`:`${displayDate(date)} · data livre`;
    $('previous').disabled=index<0 || (index===0 && current);
    $('next').disabled=index===data.milestones.length-1;
    document.querySelectorAll('#quick-periods button').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.date===date)));
  }
  function chooseDate(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(Date.parse(value))) return;
    date=value;
    updateTimeline();
    refresh();
  }
  function moveStep(step) {
    const index=data.milestones.findLastIndex(t=>t.date<=date);
    const exact=data.milestones[index]?.date===date;
    const next=index+(step===1?1:exact?-1:0);
    if (data.milestones[next]) chooseDate(data.milestones[next].date);
  }
  function setView(next) {
    view=next;
    selection=null;
    $('search').value='';
    $('results').hidden=true;
    document.querySelectorAll('[data-view]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.view===view)));
    refresh();
  }
  function focusItem(item) {
    let location;
    let targetZoom=9;
    if (item.kind==='territory') {
      const record=territories.find(row=>row.id===item.id);
      if (view!=='canon') setView('canon');
      const geometry=geographicFootprint(record);
      if (geometry) {
        worldFit=false;
        map.fitBounds(L.geoJSON(geometry).getBounds(),{maxZoom:16,padding:[50,55],animate:false});
      } else if (record.anchor) {
        location=[...record.anchor].reverse();
        targetZoom=10;
      }
    } else if (item.kind==='census') {
      const site=censuses.find(row=>row.id===item.id);
      if (!censusRecord(site,date) && site.records[0].at) chooseDate(site.records[0].at);
      if (site.coordinates) location=[...site.coordinates].reverse();
      targetZoom=site.kind==='event'?8:6;
    } else if (item.kind==='feature') {
      const f=data.features.find(row=>row.id===item.id);
      if (featureStatus(f,date)==='future' && f.start) chooseDate(f.start.end);
      if (f.geometry) {
        active.add(f.layer);
        $(`layer-${f.layer}`).checked=true;
        if (f.geometry.type==='Point') location=[...f.geometry.coordinates].reverse();
        else location=L.geoJSON(f.geometry).getBounds().getCenter();
      }
    } else if (item.kind==='demo-region') {
      const region=data.demo.regions.find(row=>row.id===item.id);
      location=L.geoJSON(region.geometry).getBounds().getCenter();
    } else if (item.kind==='legacy-region') {
      const [, ,lon,lat]=legacy.regions[item.id];
      location=[lat,lon];
      targetZoom=5;
    } else if (item.kind==='legacy-record') {
      const record=legacy.records[item.id];
      const target=data.milestones.find(t=>t.id===record.epoch);
      if (target) chooseDate(target.date);
      if (record.coordinates) location=[...record.coordinates].reverse();
      targetZoom=6;
    } else if (item.kind==='cell') {
      const [sw,ne]=cellBounds(item.id);
      location=[(sw[0]+ne[0])/2,(sw[1]+ne[1])/2];
      targetZoom=Math.min(19,Math.max(1,parseCell(item.id).level-2));
    }
    if (location) {
      worldFit=false;
      map.setView(location,targetZoom,{animate:false});
    }
    $('results').hidden=true;
    refresh();
    choose(item,true);
  }
  function search() {
    const raw=$('search').value.trim();
    const value=normalize(raw);
    const results=$('results');
    results.replaceChildren();
    if (!value) {results.hidden=true;return;}
    let candidates=[];
    if (view==='legacy') {
      candidates=Object.entries(legacy.regions).map(([id,row])=>[row[0],{kind:'legacy-region',id}]);
      candidates.push(...legacy.records.map((r,index)=>[`${r.title} · ${r.epoch}`,{kind:'legacy-record',id:index}]));
    } else {
      candidates=currentFeatures().map(f=>[f.title,{kind:'feature',id:f.id}]);
      if (view==='canon') {
        candidates.unshift(...censuses.map(site=>[site.title,{kind:'census',id:site.id}]));
        candidates.push(...territories.map(record=>[record.title,{kind:'territory',id:record.id}]));
      }
      if (view==='demo') candidates.push(...data.demo.regions.map(r=>[r.title,{kind:'demo-region',id:r.id}]));
    }
    const cell=raw.toUpperCase();
    try {parseCell(cell);candidates.unshift([cell,{kind:'cell',id:cell}]);} catch {/* ordinary search */}
    const matches=candidates.filter(([title])=>normalize(title).includes(value)).slice(0,14);
    if (!matches.length) results.append(element('li','Nenhum resultado neste conjunto.','muted'));
    for (const [title,item] of matches) {
      const li=element('li');
      li.append(button(title,()=>focusItem(item)));
      results.append(li);
    }
    results.hidden=false;
  }

  const periodOrder=new Set(['PRE','D0','D10','M1','M6','Y1','Y5','Y10','Y17','Y23']);
  for (const t of data.milestones.filter(t=>periodOrder.has(t.id))) {
    const chip=button(t.id==='PRE'?'Pré-D0':t.title,()=>chooseDate(t.date));
    chip.dataset.date=t.date;
    $('quick-periods').append(chip);
  }
  $('timeline-range').max=String(data.milestones.length-1);
  addOptions($('compare-date'),data.milestones.map(t=>[t.date,`${t.title} · ${t.date.slice(0,4)}`]));
  $('compare-date').value=base.milestones.find(t=>t.id==='PRE').date;
  $('date').addEventListener('change',e=>chooseDate(e.target.value));
  $('timeline-range').addEventListener('input',e=>chooseDate(data.milestones[Number(e.target.value)].date));
  $('previous').addEventListener('click',()=>moveStep(-1));
  $('next').addEventListener('click',()=>moveStep(1));
  $('compare-toggle').addEventListener('click',()=>{
    comparing=!comparing;
    $('compare-controls').hidden=!comparing;
    $('compare-toggle').setAttribute('aria-expanded',String(comparing));
    refresh();
  });
  $('compare-date').addEventListener('change',refresh);
  document.querySelectorAll('[data-view]').forEach(b=>b.addEventListener('click',()=>setView(b.dataset.view)));
  $('search').addEventListener('input',search);
  $('search').addEventListener('keydown',e=>{
    if (e.key==='Escape') $('results').hidden=true;
    if (e.key==='ArrowDown' && !$('results').hidden) {
      e.preventDefault();
      $('results').querySelector('button')?.focus();
    }
  });
  document.addEventListener('click',e=>{if (!e.target.closest('.search-box')) $('results').hidden=true;});
  $('summary').addEventListener('click',()=>choose(null));
  $('world').addEventListener('click',fitWorld);
  $('demo-center').addEventListener('click',()=>{
    setView('demo');
    if (date < data.demo.start) chooseDate(data.milestones.find(t=>t.id==='D0').date);
    focusItem({kind:'demo-region',id:'coast'});
    if (matchMedia('(max-width:790px)').matches) $('map').scrollIntoView({block:'nearest'});
  });
  $('mode').addEventListener('click',()=>{
    mode=mode==='region'?'detail':'region';
    $('mode').textContent=mode==='region'?'Grade regional':'Grade detalhada';
    $('mode').setAttribute('aria-pressed',String(mode==='detail'));
    drawGrid();
  });
  for (const layer of data.layers) {
    const label=element('label');
    const input=element('input');
    input.type='checkbox';
    input.id=`layer-${layer.id}`;
    input.checked=active.has(layer.id);
    input.addEventListener('change',()=>{
      if (input.checked) active.add(layer.id);
      else active.delete(layer.id);
      refresh();
    });
    label.append(input,document.createTextNode(layer.title));
    $('layer-options').append(label);
  }
  $('show-borders').addEventListener('change',()=>{if ($('show-borders').checked) borders.addTo(map);else map.removeLayer(borders);});
  for (const id of ['show-grid','metric','policy','show-uncertain','show-legacy-records','show-territories','show-cities','show-ports','show-schematic','show-dispersed']) $(id).addEventListener('change',refresh);
  $('help-open').addEventListener('click',()=>$('help-dialog').showModal());
  $('layers-open').addEventListener('click',()=>$('layers-dialog').showModal());

  function populateTerritoryCatalog() {
    const category=$('territory-category').value;
    const container=$('territory-list');
    container.replaceChildren();
    const label=normalize($('territory-search').value.trim());
    for (const record of territories) {
      if ((category && record.category!==category) || !normalize(record.title).includes(label)) continue;
      const item=button(record.title,()=>{
        $('territory-dialog').close();
        focusItem({kind:'territory',id:record.id});
      });
      item.append(element('small',`${territoryLabels[record.category]} · ${areaLabel(record.area)}`));
      container.append(item);
    }
    if (!container.childElementCount) container.append(element('p','Nenhuma ficha encontrada.','muted'));
  }
  $('territory-open').addEventListener('click',()=>{
    populateTerritoryCatalog();
    $('territory-dialog').showModal();
  });
  $('territory-category').addEventListener('change',populateTerritoryCatalog);
  $('territory-search').addEventListener('input',populateTerritoryCatalog);
  $('groups-open').addEventListener('click',()=>{
    $('territory-dialog').close();
    setView('legacy');
    if (!dispersed[epochFor(date)]) chooseDate(data.milestones.find(t=>t.id==='Y1').date);
  });

  function persist(proposal) {
    const next=applyChanges(base,proposal);
    ({data,owners}=next);
    changes=proposal;
    try {
      localStorage.setItem(storageKey,JSON.stringify(proposal));
      storageMessage='Ajustes validados e guardados neste navegador.';
    } catch {
      storageMessage='Ajustes aplicados nesta sessão. Exporte o JSON para guardá-los.';
    }
    $('edit-status').textContent=storageMessage;
    refresh();
  }
  function fillEditEvents() {
    addOptions($('edit-existing'),data.demo.events.map(e=>[e.id,e.title]));
  }
  const cellOptions = data.demo.regions.flatMap(r=>r.cells.map(c=>[c.id,`${c.id} · ${r.title}`]));
  for (const id of ['edit-cell','edit-from','edit-to']) addOptions($(id),cellOptions);
  function fillEstimate() {
    const before=snapshot(data,data.demo.start,owners);
    const id=$('edit-cell').value;
    $('edit-value').value=before.balances[owners[id]][id].human;
  }
  function updateEditFields() {
    const kind=$('edit-kind').value;
    const simple=kind==='replace-estimate';
    $('edit-cell-fields').hidden=!simple;
    $('edit-event-fields').hidden=simple;
    $('edit-existing').closest('label').hidden=kind!=='correct-event';
    if (kind==='correct-event') {
      const record=data.demo.events.find(e=>e.id===$('edit-existing').value);
      if (record) {
        const add=record.ops.find(o=>o.delta>0);
        const remove=record.ops.find(o=>o.delta<0);
        $('edit-title').value=record.title;
        $('edit-type').value=record.type;
        $('edit-amount').value=add.delta;
        $('edit-from').value=remove?.cell??add.cell;
        $('edit-to').value=add.cell;
        $('edit-stock').value=remove?.metric??'human';
        $('edit-start').value=record.when?.start??'';
        $('edit-end').value=record.when?.end??'';
      }
    }
    updateEventInputs();
  }
  function updateEventInputs() {
    const type=$('edit-type').value;
    $('edit-from').closest('label').hidden=type==='birth';
    $('edit-to').closest('label').hidden=!['migration','birth'].includes(type);
    $('edit-stock').closest('label').hidden=!['migration','death','disappearance'].includes(type);
  }
  $('edits-open').addEventListener('click',()=>{
    const selected=selection?.kind==='cell'?selection.id:null;
    if (selected && owners[selected]) $('edit-cell').value=selected;
    fillEditEvents();
    fillEstimate();
    updateEditFields();
    $('edit-status').textContent=storageMessage;
    $('edits-dialog').showModal();
  });
  $('edit-kind').addEventListener('change',updateEditFields);
  $('edit-existing').addEventListener('change',updateEditFields);
  $('edit-type').addEventListener('change',updateEventInputs);
  $('edit-cell').addEventListener('change',fillEstimate);
  $('edit-form').addEventListener('submit',e=>{
    e.preventDefault();
    try {
      const kind=$('edit-kind').value;
      let operation;
      if (kind==='replace-estimate') {
        const cell=$('edit-cell').value;
        operation={kind,cell,region:owners[cell],value:$('edit-value').valueAsNumber};
      } else {
        const correcting=kind==='correct-event';
        const previous=data.demo.events.find(e=>e.id===$('edit-existing').value);
        if (correcting && (previous.ops.filter(o=>o.delta>0).length!==1 || previous.ops.filter(o=>o.delta<0).length>1)) throw new Error('Use JSON para corrigir eventos com múltiplos destinos.');
        const id=correcting?previous.id:'local-'+(globalThis.crypto?.randomUUID?.()??`${Date.now()}-${changes.operations.length}`);
        const title=$('edit-title').value.trim();
        const type=$('edit-type').value;
        const quantity=$('edit-amount').valueAsNumber;
        const from=$('edit-from').value;
        const to=$('edit-to').value;
        const first=$('edit-start').value;
        const last=$('edit-end').value;
        if (!title || !Number.isSafeInteger(quantity) || quantity<=0 || Boolean(first)!==Boolean(last)) throw new Error('Informe o título, uma quantidade positiva e as duas datas ou deixe ambas em branco.');
        const stock=type==='conversion'?'human':$('edit-stock').value;
        const metric={conversion:'converted',death:'deaths',disappearance:'missing',birth:'human',migration:stock}[type];
        const ops=type==='birth'?[{cell:to,metric,delta:quantity}]:[
          {cell:from,metric:stock,delta:-quantity},
          {cell:type==='migration'?to:from,metric,delta:quantity}
        ];
        operation={kind,event:{id,title,type,when:first?{start:first,end:last}:null,order:correcting?previous.order:100,ops}};
      }
      const operations=changes.operations.filter(o=>!(kind==='replace-estimate'&&o.kind===kind&&o.cell===operation.cell)
        && !(kind==='correct-event'&&o.kind===kind&&o.event.id===operation.event.id));
      persist({version:1,operations:[...operations,operation]});
      if (view!=='demo') setView('demo');
      const eventSelection=$('edit-existing').value;
      fillEditEvents();
      if (eventSelection) $('edit-existing').value=eventSelection;
    } catch (error) { $('edit-status').textContent=error.message; }
  });
  $('clear-edits').addEventListener('click',()=>{
    if (!changes.operations.length || window.confirm('Descartar os ajustes locais desta demonstração?')) {
      persist({version:1,operations:[]});
      fillEditEvents();
      fillEstimate();
      updateEditFields();
    }
  });
  $('export').addEventListener('click',()=>{
    const url=URL.createObjectURL(new Blob([JSON.stringify(changes,null,2)+'\n'],{type:'application/json'}));
    const a=element('a');
    a.href=url;
    a.download='AHV_Ajustes_Atlas.json';
    a.click();
    setTimeout(()=>URL.revokeObjectURL(url),2000);
  });
  $('import').addEventListener('change',async e=>{
    const file=e.target.files[0];
    if (!file) return;
    try {
      if (file.size>1_000_000) throw new Error('O arquivo de ajustes deve ter até 1 MB.');
      persist(JSON.parse(await file.text()));
      fillEditEvents();
      fillEstimate();
      updateEditFields();
      if (view!=='demo') setView('demo');
    } catch (error) {
      $('edit-status').textContent=`Importação rejeitada. ${error.message}`;
    } finally {
      e.target.value='';
    }
  });

  map.on('dragstart zoomstart',()=>{worldFit=false;});
  map.on('moveend',()=>{
    switchLand();
    drawGrid();
    if (view==='canon') drawThemes();
  });
  map.on('click',e=>{
    if (!$('show-grid').checked || Math.abs(e.latlng.lat)>90 || Math.abs(e.latlng.lng)>180) return;
    choose({kind:'cell',id:cellAt(e.latlng.lat,e.latlng.lng,grid.level)},true);
  });
  $('map').addEventListener('keydown',e=>{
    if (e.target!==$('map') || e.key!=='Enter' || !$('show-grid').checked) return;
    const center=map.getCenter();
    choose({kind:'cell',id:cellAt(center.lat,center.lng,grid.level)},true);
  });
  new ResizeObserver(()=>{
    map.invalidateSize({pan:false,debounceMoveend:true});
    if (worldFit) fitWorld();
  }).observe($('map'));
  fitWorld();
  switchLand();
  updateTimeline();
  refresh();
}

start().catch(error=>{
  $('fatal').hidden=false;
  $('fatal').textContent=`O atlas não iniciou. ${error.message} Se abriu a pasta diretamente, use a versão HTML de arquivo único.`;
  console.error(error);
});

export const ASPECT = 4 / (3 * Math.PI);
export const EARTH_AREA = 4 * Math.PI * 6371.0071809 ** 2;
export const MAX_LEVEL = 20;
const radians = Math.PI / 180;
const clamp = (value, low, high) => Math.min(high, Math.max(low, value));

export function project(lat, lon) {
  return [(lon + 180) / 360, (1 - Math.sin(lat * radians)) / 2 * ASPECT];
}
export function unproject(x, y) {
  return [Math.asin(clamp(1 - 2 * y / ASPECT, -1, 1)) / radians, x * 360 - 180];
}
export function cellAt(lat, lon, level) {
  const [x, y] = project(lat, lon);
  const n = 2 ** level;
  return cellId(level, clamp(Math.floor(x * n), 0, n - 1), clamp(Math.floor(y / ASPECT * n), 0, n - 1));
}
export function cellId(level, col, row) {
  return `EA1/${level}/${col}/${row}`;
}
export function parseCell(id) {
  const match = /^EA1\/(0|[1-9]\d*)\/(0|[1-9]\d*)\/(0|[1-9]\d*)$/.exec(id);
  if (!match) throw new Error(`Célula inválida: ${id}`);
  const [level, col, row] = match.slice(1).map(Number);
  if (level > MAX_LEVEL || col >= 2 ** level || row >= 2 ** level) throw new Error(`Célula fora da grade: ${id}`);
  return {level, col, row};
}
export function parentAt(id, level) {
  const cell = parseCell(id);
  if (level > cell.level || level < 0) return null;
  const factor = 2 ** (cell.level - level);
  return cellId(level, Math.floor(cell.col / factor), Math.floor(cell.row / factor));
}
export function cellBounds(id) {
  const {level, col, row} = parseCell(id);
  const n = 2 ** level;
  return [unproject(col / n, ASPECT * (row + 1) / n), unproject((col + 1) / n, ASPECT * row / n)];
}
export function cellArea(id) {
  return EARTH_AREA / 4 ** parseCell(id).level;
}
export function gridForViewport(a, b, zoom, mode, cap = 240) {
  let level = clamp(Math.floor(zoom + (mode === 'detail' ? 3 : 1)), 1, MAX_LEVEL);
  while (true) {
    const first = parseCell(cellAt(b[0], a[1], level));
    const last = parseCell(cellAt(a[0], b[1], level));
    const count = (last.col - first.col + 1) * (last.row - first.row + 1);
    if (count <= cap || level === 1) return {level, first, last, count};
    level--;
  }
}

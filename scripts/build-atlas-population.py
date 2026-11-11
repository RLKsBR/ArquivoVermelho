"""Build the atlas geographic units from Natural Earth 5.1.2 and WB SP.POP.TOTL (2024).

Args: 50m countries, 50m states/provinces, 10m simple populated places,
World Bank JSON, output JSON. All inputs are their original public downloads.
"""
import collections
import json
import math
import sys
from pathlib import Path

RADIUS = 6371.0071809
# These map units can share a census with another mapped entity.
UNALLOCATED = {"ATA", "SOL", "CYN", "KAS", "SAH", "PSX", "USG", "BJN", "SER"}
CODE_ALIASES = {"KOS": "XKX"}


def read(path):
    return json.loads(Path(path).read_text())


def polys(geometry):
    return [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]


def ring_area(ring):
    total = 0
    for (lon1, lat1), (lon2, lat2) in zip(ring, ring[1:]):
        dlon = (lon2 - lon1 + 180) % 360 - 180
        total += math.radians(dlon) * (math.sin(math.radians(lat1)) + math.sin(math.radians(lat2)))
    return abs(total) * RADIUS**2 / 2


def surface(geometry):
    return sum(ring_area(poly[0]) - sum(map(ring_area, poly[1:])) for poly in polys(geometry))


def distance_sq(p, a, b):
    dx, dy = b[0]-a[0], b[1]-a[1]
    length = dx*dx+dy*dy
    t = max(0, min(1, ((p[0]-a[0])*dx+(p[1]-a[1])*dy)/length)) if length else 0
    return (p[0]-a[0]-t*dx)**2+(p[1]-a[1]-t*dy)**2


def thin(points, tolerance):
    if len(points) <= 2:
        return points
    index = max(range(1, len(points)-1), key=lambda i: distance_sq(points[i], points[0], points[-1]))
    if distance_sq(points[index], points[0], points[-1]) <= tolerance:
        return [points[0], points[-1]]
    return thin(points[:index+1], tolerance)[:-1] + thin(points[index:], tolerance)


def simplified(geometry):
    def ring(points):
        reduced = thin(points, .075**2)
        if len(reduced) < 4:
            reduced = points
        return [[round(lon, 3), round(lat, 3)] for lon, lat in reduced]
    rings = [[ring(r) for r in poly] for poly in polys(geometry)]
    return rings[0] if geometry["type"] == "Polygon" else rings


def contains(ring, x, y):
    inside = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
        if (y1 > y) != (y2 > y) and x < (x2-x1)*(y-y1)/(y2-y1)+x1:
            inside = not inside
    return inside


def urban_weight(geometry, places):
    total = 0
    anchor = None
    for poly in polys(geometry):
        outline = poly[0]
        xs, ys = zip(*outline)
        west, east, south, north = min(xs), max(xs), min(ys), max(ys)
        for point in places:
            x, y, count = point
            if west <= x <= east and south <= y <= north and contains(outline, x, y) and not any(
                    contains(hole, x, y) for hole in poly[1:]):
                total += count
                if anchor is None or count > anchor[2]:
                    anchor = point
    return total, anchor[:2] if anchor else None


def main():
    countries_path, provinces_path, cities_path, population_path, output = sys.argv[1:]
    by_code = {row["countryiso3code"]: row["value"] for row in read(population_path)[1] if row["value"]}
    provinces = collections.defaultdict(list)
    cities = collections.defaultdict(list)
    for feature in read(provinces_path)["features"]:
        provinces[feature["properties"]["adm0_a3"]].append(feature)
    for feature in read(cities_path)["features"]:
        data = feature["properties"]
        if data["pop_max"] > 0:
            cities[data["adm0_a3"]].append([*feature["geometry"]["coordinates"], data["pop_max"]])
    countries = []
    regions = []
    for country in read(countries_path)["features"]:
        p = country["properties"]
        code = p["ADM0_A3"]
        census = by_code.get(CODE_ALIASES.get(code, p["ISO_A3_EH"]))
        population = 0 if code in UNALLOCATED else (census or max(0, round(p["POP_EST"])))
        countries.append([code, p["ISO_A2_EH"], p["ADMIN"], p["CONTINENT"], population,
                          "wb2024" if census and population else "ne" if population else None])
        for feature in provinces.get(code, [country]):
            data = feature["properties"]
            geometry = feature["geometry"]
            area = max(1, round(surface(geometry)))
            is_country = feature is country
            urban, anchor = urban_weight(geometry, cities[code]) if not is_country else (
                sum(c[2] for c in cities[code]), None)
            if is_country:
                anchor = [p["LABEL_X"], p["LABEL_Y"]]
            elif anchor is None:
                anchor = [data["longitude"], data["latitude"]]
            regions.append([code if is_country else data["adm1_code"], code,
                            p["ADMIN"] if is_country else data["name"], area, urban,
                            [round(v, 4) for v in anchor], geometry["type"], simplified(geometry)])
    Path(output).write_text(json.dumps({"countries": countries, "regions": regions},
                                     ensure_ascii=False, separators=(",", ":"))+"\n")


if __name__ == "__main__":
    main()

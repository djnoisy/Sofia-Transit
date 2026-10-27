# The directions of every line as the app prepares them, and the roads trips really take — for CompassTests.kt.
#  - directions: the two headsigns with most trips (GtfsRepository.getDirectionHeadsigns), each with the stops
#    of its trip with most stops, the first in the feed among equals (getStopsForRouteDirection);
#  - the line's shapes in the order the database gives them (first use in trips.txt), as loadCandidateRoads
#    tries them;
#  - every shape the trips of those two headsigns take, with the headsign: the roads walked by the test.
import csv, sys, collections
g, out = sys.argv[1:]
trips = list(csv.DictReader(open(f"{g}/trips.txt", encoding="utf-8")))
count = collections.Counter(); seq = collections.defaultdict(list)
for r in csv.DictReader(open(f"{g}/stop_times.txt", encoding="utf-8")):
    count[r["trip_id"]] += 1
    seq[r["trip_id"]].append((int(r["stop_sequence"]), r["stop_id"]))
stops = {r["stop_id"]: (r["stop_lat"], r["stop_lon"]) for r in csv.DictReader(open(f"{g}/stops.txt", encoding="utf-8"))}
by_route = collections.defaultdict(list)
for t in trips: by_route[t["route_id"]].append(t)
used = set()
with open(f"{out}/directions.csv", "w") as fd, open(f"{out}/walks.csv", "w") as fw, open(f"{out}/order.csv", "w") as fo:
    for route, ts in by_route.items():
        hs = []
        for h, _ in collections.Counter(t["trip_headsign"] for t in ts).most_common():
            if h.strip() and h.strip().upper() not in {x.upper() for x in hs}: hs.append(h.strip())
            if len(hs) == 2: break
        if len(hs) < 2: continue
        for h in hs:
            cand = [t for t in ts if t["trip_headsign"].strip() == h]
            best = max(cand, key=lambda t: count[t["trip_id"]])
            fd.write(route + "|" + h.replace("|", "/") + "|" + ";".join(f"{a},{b}" for a, b in (stops[s] for _, s in sorted(seq[best["trip_id"]]))) + "\n")
            for sh in sorted({t["shape_id"] for t in cand}):
                fw.write(f"{route}|{h.replace('|', '/')}|{sh}\n"); used.add(sh)
        order = []
        for t in ts:
            if t["shape_id"] not in order: order.append(t["shape_id"])
        fo.write(route + "|" + ",".join(order) + "\n"); used.update(order)
pts = collections.defaultdict(list)
for r in csv.DictReader(open(f"{g}/shapes.txt", encoding="utf-8")):
    if r["shape_id"] in used: pts[r["shape_id"]].append((int(r["shape_pt_sequence"]), r["shape_pt_lat"], r["shape_pt_lon"]))
with open(f"{out}/shapes.csv", "w") as f:
    for i, p in pts.items(): f.write(i + "|" + ";".join(f"{a},{b}" for _, a, b in sorted(p)) + "\n")

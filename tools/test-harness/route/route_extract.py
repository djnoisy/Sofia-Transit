# The trips of every line in a static feed, for route/RouteTests.kt. Lines:
#   T|route_id|short name|route_type|trip_id|headsign (trimmed)|service_id|stop_id:lat:lon:time:stop_code;... in order
#     (time: seconds after midnight of the service day the trip leaves the stop, -1 when not given)
#   D|date|service_id,...  — the services running on each of the DATES given (YYYYMMDD, comma-separated)
import csv, sys, collections
g, dates, out = sys.argv[1], sys.argv[2].split(","), sys.argv[3]
runs = collections.defaultdict(set)
for r in csv.DictReader(open(f"{g}/calendar_dates.txt", encoding="utf-8")):
    if r["exception_type"] == "1" and r["date"] in dates: runs[r["date"]].add(r["service_id"])
routes = {r["route_id"]: (r["route_short_name"], r["route_type"]) for r in csv.DictReader(open(f"{g}/routes.txt", encoding="utf-8"))}
place = {r["stop_id"]: (r["stop_lat"], r["stop_lon"], r.get("stop_code", "")) for r in csv.DictReader(open(f"{g}/stops.txt", encoding="utf-8"))}
def sec(h):
    p = (h or "").split(":")
    return int(p[0]) * 3600 + int(p[1]) * 60 + int(p[2]) if len(p) == 3 else -1
seq = collections.defaultdict(list)
for r in csv.DictReader(open(f"{g}/stop_times.txt", encoding="utf-8")):
    seq[r["trip_id"]].append((int(r["stop_sequence"]), r["stop_id"], sec(r["departure_time"] or r["arrival_time"])))
with open(out, "w", encoding="utf-8") as f:
    for d in dates: f.write(f"D|{d}|{','.join(sorted(runs[d]))}\n")
    for t in csv.DictReader(open(f"{g}/trips.txt", encoding="utf-8")):
        s = seq.get(t["trip_id"])
        if not s: continue
        name, kind = routes.get(t["route_id"], ("?", "?"))
        stops = ";".join(f"{x}:{place[x][0]}:{place[x][1]}:{at}:{place[x][2]}" for _, x, at in sorted(s))
        f.write("|".join(["T", t["route_id"], name, kind, t["trip_id"], (t["trip_headsign"] or "").strip(), t["service_id"], stops]) + "\n")

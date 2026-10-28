# Step 2, the proposal (HANDOVER, "Стъпка 2: предложение"): before a vehicle is identified — and for one that never is,
# reporting no positions — the way on from the boarding stop taken from the timetable: the trips of the line due at
# that stop (its stop code) from BEFORE s before to AFTER s after our departure, when they all go on by the same
# stops; else the direction's route of the day, as now. Every stop of every trip running on DATE is boarded once
# (the metro aside), departing DELAY s after the timetable; the due times are the timetable's.
#   python3 -I step2_variant_by_timetable.py <unpacked static feed> <YYYYMMDD> [before s] [after s] [delays, s] [lines]
# Judged by the stops after the boarding stop, by id: right; right, but going on past the end of the run
# (a stop to get off at beyond it is not said to be off it); wrong stops (some never said).
import csv, sys, math, collections, bisect
g, date = sys.argv[1], sys.argv[2]
BEFORE = int(sys.argv[3]) if len(sys.argv) > 3 else 1200
AFTER = int(sys.argv[4]) if len(sys.argv) > 4 else 300
delays = [int(x) for x in sys.argv[5].split(",")] if len(sys.argv) > 5 else [0]
lines = sys.argv[6].split(",") if len(sys.argv) > 6 else []

def sec(h):
    a, b, c = h.split(":"); return int(a) * 3600 + int(b) * 60 + int(c)
svc = {r["service_id"] for r in csv.DictReader(open(f"{g}/calendar_dates.txt", encoding="utf-8"))
       if r["exception_type"] == "1" and r["date"] == date}
routes = {r["route_id"]: (r["route_short_name"], r["route_type"]) for r in csv.DictReader(open(f"{g}/routes.txt", encoding="utf-8"))}
stops = {r["stop_id"]: (float(r["stop_lat"]), float(r["stop_lon"]), r.get("stop_code", "")) for r in csv.DictReader(open(f"{g}/stops.txt", encoding="utf-8"))}
seq = collections.defaultdict(list)
for r in csv.DictReader(open(f"{g}/stop_times.txt", encoding="utf-8")):
    seq[r["trip_id"]].append((int(r["stop_sequence"]), r["stop_id"], sec(r["departure_time"] or r["arrival_time"])))
trips = []
for t in csv.DictReader(open(f"{g}/trips.txt", encoding="utf-8")):
    s = sorted(seq.get(t["trip_id"], []))
    if s: trips.append(dict(route=t["route_id"], trip=t["trip_id"], hs=(t["trip_headsign"] or "").strip(), service=t["service_id"],
                            stops=[x[1] for x in s], times=[x[2] for x in s]))

def pole(s): return stops[s][2] or s
def dist(a, b):
    la, lo, _ = stops[a]; lb, lob, _ = stops[b]
    return math.hypot((la - lb) * 111195.0, (lo - lob) * 111195.0 * math.cos(math.radians((la + lb) / 2)))
# RouteOfTheDay, as in the app.
def along(t, r):
    i = 0
    for s in r:
        if i == len(t): break
        if t[i] == s: i += 1
    return i == len(t)
def busiest(own):
    seen, cands = set(), []
    for t in sorted(own, key=lambda x: x["trip"]):
        k = tuple(t["stops"])
        if k not in seen: seen.add(k); cands.append(t)
    best = None
    for r in cands:
        n = sum(1 for t in own if along(t["stops"], r["stops"]))
        key = (n, len(r["stops"]))
        if n and (best is None or key > best[0] or (key == best[0] and r["trip"] < best[1]["trip"])): best = (key, r)
    return best[1] if best else None
def opposite(a, b):
    at = []
    for s in a["stops"]:
        j = min(range(len(b["stops"])), key=lambda k: dist(s, b["stops"][k]))
        if dist(s, b["stops"][j]) <= 150: at.append(j)
    pairs = list(zip(at, at[1:])); back = sum(1 for x, y in pairs if y < x)
    return back >= 2 and back > sum(1 for x, y in pairs if y > x)
def old_dirs(all_):
    c = collections.Counter(t["hs"] for t in all_ if t["hs"])
    return [max([t for t in all_ if t["hs"] == hs], key=lambda t: len(t["stops"])) for hs, _ in sorted(c.items(), key=lambda e: (-e[1], e[0]))[:2]]
def matched_count(t, r):
    j = n = 0
    for s in t:
        k = j
        while k < len(r) and r[k] != s: k += 1
        if k < len(r): n += 1; j = k + 1
    return n
def rest(route, s):
    p = pole(s)
    for j, x in enumerate(route):
        if pole(x) == p: return route[j + 1:]
    return None
def judge(rem, followed):
    if followed is None or not along(rem, followed): return "C"
    return "B" if followed.index(rem[-1]) < len(followed) - 1 else "A"

by = collections.defaultdict(list)
for t in trips: by[t["route"]].append(t)
res = {d: collections.Counter() for d in delays}; perline = collections.defaultdict(collections.Counter)
for rid, all_ in by.items():
    if routes[rid][1] == "1": continue
    today = [t for t in all_ if t["service"] in svc]
    if not today: continue
    c = collections.Counter(t["hs"] for t in all_ if t["hs"])
    named = []
    for hs, _ in sorted(c.items(), key=lambda e: (-e[1], e[0])):
        r = busiest([t for t in today if t["hs"] == hs])
        if r: named.append(r)
        if len(named) == 2: break
    if len(named) == 2 and not opposite(named[0], named[1]): named = []
    dirs = named or old_dirs(all_)
    calls = collections.defaultdict(list)
    for ti, t in enumerate(today):
        for i, s in enumerate(t["stops"][:-1]): calls[pole(s)].append((t["times"][i], ti, i))
    for v in calls.values(): v.sort()
    name = f'{ {"0": "трамвай", "3": "автобус", "11": "тролей"}.get(routes[rid][1], "?") } {routes[rid][0]}'
    for x in today:
        # The passenger's direction: the one the trip runs along, else the nearest by stops.
        d_now = next((d for d in dirs if along(x["stops"], d["stops"])), None) or max(dirs, key=lambda d: matched_count(x["stops"], d["stops"]))
        for i in range(len(x["stops"]) - 1):
            S = x["stops"][i]; rem = x["stops"][i + 1:]
            now = judge(rem, rest(d_now["stops"], S))
            for D in delays:
                dep = x["times"][i] + D; v = calls[pole(S)]
                lo = bisect.bisect_left(v, (dep - BEFORE, -1, -1)); hi = bisect.bisect_right(v, (dep + AFTER, 10**9, 10**9))
                ways = {tuple(today[ti]["stops"][j + 1:]) for _, ti, j in v[lo:hi]}
                if len(ways) == 1:
                    w = list(next(iter(ways))); new = "A" if w == rem else judge(rem, w); kind = "decided"
                else:
                    new = now; kind = "as now"
                res[D][(kind, now, new)] += 1
                if D == delays[0]: perline[name][(now, new)] += 1
lab = {"A": "right", "B": "right, going on past the end of the run", "C": "wrong stops"}
for D in delays:
    r = res[D]
    print(f"{date}, window -{BEFORE}..+{AFTER} s, departing {D:+d} s after the timetable: {sum(r.values())} boardings, "
          f"decided {sum(v for k, v in r.items() if k[0] == 'decided')}")
    for q in "ABC":
        print(f"   {lab[q]}: now {sum(v for k, v in r.items() if k[1] == q)} -> proposed {sum(v for k, v in r.items() if k[2] == q)}")
    print(f"   worse than now: {sum(v for k, v in r.items() if k[2] > k[1])}")
for ln in lines:
    c = perline.get(ln, collections.Counter())
    n = lambda pos, q: sum(v for k, v in c.items() if k[pos] == q)
    print(f"  {ln} ({delays[0]:+d} s): {sum(c.values())} boardings; wrong stops {n(0, 'C')} -> {n(1, 'C')}, past the end {n(0, 'B')} -> {n(1, 'B')}")

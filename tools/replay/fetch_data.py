"""Pobiera ok. 29 dni swiec 1m kontraktu na zloto (GC=F, Yahoo Finance) do data/gc_1m.csv."""
import csv, json, os, time, urllib.request

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    return urllib.request.urlopen(req, timeout=30).read()

now = int(time.time()); rows = {}; end = now
while now - end < 29 * 86400 - 100:
    p1 = max(end - 7 * 86400 + 60, now - 29 * 86400)
    j = json.loads(get(f"https://query1.finance.yahoo.com/v8/finance/chart/GC=F?interval=1m&period1={p1}&period2={end}&includePrePost=true"))
    r = j["chart"]["result"][0]; q = r["indicators"]["quote"][0]
    for t, o, h, l, c in zip(r.get("timestamp", []), q["open"], q["high"], q["low"], q["close"]):
        if None not in (o, h, l, c): rows[t] = (t, o, h, l, c)
    end = p1
os.makedirs("data", exist_ok=True)
with open("data/gc_1m.csv", "w", newline="") as f:
    w = csv.writer(f); w.writerow(["t", "o", "h", "l", "c"]); w.writerows(rows[k] for k in sorted(rows))
print(len(rows), "swiec zapisanych w data/gc_1m.csv")

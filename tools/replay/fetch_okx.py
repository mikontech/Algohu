"""Swiece 1m XAU-USDT-SWAP z OKX (publiczne API, bez klucza). Uzycie: python3 fetch_okx.py [dni]"""
import json, sys, time, urllib.request, csv
from concurrent.futures import ThreadPoolExecutor
days = int(sys.argv[1]) if len(sys.argv) > 1 else 240
inst = "XAU-USDT-SWAP"; W = 6
now = int(time.time()) // 60 * 60; start = now - days * 86400

def chunk(a, b):
    rows = {}; end = b * 1000; fails = 0
    while end > a * 1000:
        u = f"https://www.okx.com/api/v5/market/history-candles?instId={inst}&bar=1m&limit=100&after={end}"
        try:
            d = json.load(urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0"}), timeout=20))["data"]
            fails = 0
        except Exception as e:
            fails += 1
            if fails > 15: print("blad", e, flush=True); break
            time.sleep(1.0 + fails * 0.5); continue
        if not d: break
        for r in d:
            if r[8] == "1" and int(r[0]) >= a * 1000: rows[int(r[0]) // 1000] = r[1:6]
        end = int(d[-1][0])
        time.sleep(0.45)
    print("czesc", time.strftime("%Y-%m-%d", time.gmtime(a)), len(rows), flush=True)
    return rows

step = (now - start) // 24 + 60
parts = [(s, min(s + step, now + 60)) for s in range(start, now, step)]
rows = {}
with ThreadPoolExecutor(W) as ex:
    for r in ex.map(lambda p: chunk(*p), parts): rows.update(r)
with open("data/okx_xau_1m.csv", "w", newline="") as f:
    w = csv.writer(f); w.writerow(["t", "o", "h", "l", "c", "v"])
    for t in sorted(rows): w.writerow([t] + rows[t])
print("zapisano", len(rows))

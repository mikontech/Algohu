import sys
from replay import *
bars = load(sys.argv[1])
res = {}
for s in ("rt","range","smc","orb"):
    tr,_ = run(bars, P(strats=(s,)))
    res[s]=tr
def gate(tr, n, minn, thr):
    out=[]; hist=[]
    for t in tr:
        last=hist[-n:]
        ok = not (len(last)>=minn and sum(last)/len(last) < thr)
        if ok: out.append(t)
        hist.append(t["r"])
    return out
print("bez bramki:", {s:(len(v), round(sum(t['pnl_pln'] for t in v))) for s,v in res.items()}, "razem", sum(len(v) for v in res.values()), round(sum(t['pnl_pln'] for v in res.values() for t in v)))
for n,minn,thr in [(10,5,0.0),(20,10,0.0),(20,10,-0.1),(30,10,0.0),(10,5,-0.2),(5,3,0.0)]:
    tot=0;cnt=0;d={}
    for s,v in res.items():
        g=gate(v,n,minn,thr); d[s]=(len(g), round(sum(t['pnl_pln'] for t in g))); tot+=sum(t['pnl_pln'] for t in g); cnt+=len(g)
    print(f"ostatnie {n} (min {minn}), prog {thr:+.1f}R:", d, "razem", cnt, round(tot), "na transakcje %.2f"%(tot/cnt))

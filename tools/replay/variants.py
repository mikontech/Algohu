import sys, json
from replay import *
bars = load(sys.argv[1])
half = len(bars)//2
allrows=[]
def go(label, **kw):
    tr,_ = run(bars, P(**kw))
    rows = summarize(tr, label)
    # stabilnosc: pierwsza vs druga polowa okresu
    cut = bars[half][0]
    for s in sorted(set(t["strat"] for t in tr)):
        a=[t["pnl_pln"] for t in tr if t["strat"]==s and t["t"]<cut]; b=[t["pnl_pln"] for t in tr if t["strat"]==s and t["t"]>=cut]
        print(f"      {s}: 1. polowa n={len(a)} suma={sum(a):.0f} | 2. polowa n={len(b)} suma={sum(b):.0f}")
    allrows.extend(rows)
    return tr
print("== KONTROLA: losowe wejscia, ten sam SL/TP/BE ==")
for sd in (1,2,3):
    go(f"losowe co 30 swiec seed{sd}", strats=(), random_every=30, seed=sd)
go("losowe, bez filtra tradera", strats=(), random_every=30, trader=False)
go("losowe, bez BE", strats=(), random_every=30, be=False)
print("== RT ==")
go("RT bez BE/trailingu", strats=("rt",), be=False)
go("RT TP 1R bez BE", strats=("rt",), be=False, tp_r=1.0)
go("RT odwrocony (fade)", strats=("rt",), rt_fade=True)
go("RT bez filtra tradera", strats=("rt",), trader=False)
print("== ZAKRES ==")
go("ZAKRES wlasny SL i cel", strats=("range",), coerce=False, native_tp=True)
go("ZAKRES wlasny SL/cel bez BE", strats=("range",), coerce=False, native_tp=True, be=False)
go("ZAKRES SL15-20 + wlasny cel", strats=("range",), native_tp=True)
go("ZAKRES bez BE", strats=("range",), be=False)
print("== SMC ==")
go("SMC tylko po zamknieciu M1", strats=("smc",), smc_live=False)
go("SMC bez swing 1h", strats=("smc",), smc_swing=False)
go("SMC + filtr trendu M5", strats=("smc",), smc_trend=True)
go("SMC bias scisly", strats=("smc",), smc_bias_strict=True)
go("SMC zamkn.+trend+bias", strats=("smc",), smc_live=False, smc_trend=True, smc_bias_strict=True)
go("SMC bez BE", strats=("smc",), be=False)
tr = go("SMC bazowy", strats=("smc",))
by={}
for t in tr: by.setdefault(t["note"],[]).append(t["pnl_pln"])
for k,v in sorted(by.items()): print("     ",k,"n=",len(v),"suma=",round(sum(v),1))
json.dump(allrows, open(sys.argv[1].replace(".csv","_variants.json"),"w"))

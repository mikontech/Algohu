"""Ile transakcji daja luzniejsze progi i mniej filtrow. Uzycie: python3 freq.py data/gc_1m.csv"""
import sys, statistics as st
from replay2 import *
bars = load(sys.argv[1]); days = (bars[-1][0] - bars[0][0]) / 86400 * 5 / 7
def show(label, **kw):
    tr, _ = run(bars, P(**kw)); n = len(tr); tot = sum(t["pnl_pln"] for t in tr)
    dur = st.median((t["t2"] - t["t"]) / 60 for t in tr) if tr else 0
    print(f"{label:44s} n={n:5d} ({n/days:5.1f}/dzien) suma={tot:8.0f} PLN  na transakcje={tot/max(n,1):+.2f}  mediana czasu={dur:.0f} min", flush=True)
loose = dict(rt_range=1.0, rt_body=0.65, rt_wick=0.20, rt_look=8, range_confirm=0.25, smc_max=3)
show("poprzednio")
show("luzniejsze progi", **loose)
show("luzniejsze progi + TRADER_STRICT=false", **loose, trader_strict=False)

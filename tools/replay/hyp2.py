import numpy as np, pandas as pd, sys
from lab import *
d = load("data/okx_xau_1m.csv"); n = len(d); S = pd.Series
C, H, L, O, M, T = d.c.values, d.h.values, d.l.values, d.o.values, d.m.values, d.t.values
split = T[int(n * 0.6)]; SL = 7.0; day = d.day
def Tt(name, sig, sl=SL, tp=2.5 * SL, **kw): stat(sim(d, sig, sl, tp, **kw), name, split)
# poziomy
dd = d.groupby("day").agg(hi=("h", "max"), lo=("l", "min")); pdh = day.map(dd.hi.shift(1)).values; pdl = day.map(dd.lo.shift(1)).values
asia = d[(d.m >= 60) & (d.m < 480)].groupby("day").agg(hi=("h", "max"), lo=("l", "min"))
ah = np.where(M >= 480, day.map(asia.hi).values, np.nan); al = np.where(M >= 480, day.map(asia.lo).values, np.nan)
h4 = S(H).rolling(240).max().shift(30).values; l4 = S(L).rolling(240).min().shift(30).values
# swieca 5m zamknieta w minucie konczacej sie na 4/9
end5 = (M % 5 == 4); h5 = S(H).rolling(5).max().values; l5 = S(L).rolling(5).min().values; o5 = S(O).shift(4).values
for nm, hi, lo in (("PDH/PDL", pdh, pdl), ("Azja H/L", ah, al), ("4h H/L", h4, l4)):
    sw_dn = end5 & (h5 > hi) & (C < hi) & (o5 < hi); sw_up = end5 & (l5 < lo) & (C > lo) & (o5 > lo)
    s = sw_up.astype(int) - sw_dn.astype(int)
    Tt(f"SWEEP 5m {nm} odwrocenie tp2.5R", s); Tt(f"SWEEP 5m {nm} odwrocenie tp1R", s, tp=SL); Tt(f"SWEEP 5m {nm} odwrocenie tp2.5R BE", s, be=True)
    Tt(f"SWEEP 5m {nm} kontynuacja", -s)
    # stop strukturalny za knotem
    dist = np.where(s > 0, C - l5, h5 - C) + 0.7
    ok = (dist >= 2.1) & (dist <= 8.4)
    Tt(f"SWEEP 5m {nm} SL za knotem tp2.5R", np.where(ok, s, 0), sl=np.where(ok, dist, SL), tp=np.where(ok, 2.5 * dist, SL))
    brk_up = end5 & (C > hi) & (o5 <= hi); brk_dn = end5 & (C < lo) & (o5 >= lo)
    Tt(f"WYBICIE 5m {nm} (zamkniecie za poziomem)", brk_up.astype(int) - brk_dn.astype(int))
# scisniecie -> wybicie
r60 = (S(H).rolling(60).max() - S(L).rolling(60).min()); q = r60.rolling(1440 * 5).quantile(0.2)
sq = (r60 < q).shift(1).fillna(False).values
hh = S(H).rolling(60).max().shift(1).values; ll = S(L).rolling(60).min().shift(1).values
s = (sq & (C > hh)).astype(int) - (sq & (C < ll)).astype(int)
Tt("SCISNIECIE 60m -> wybicie", s); Tt("SCISNIECIE 60m -> wybicie BE", s, be=True); Tt("SCISNIECIE 60m -> FADE", -s, tp=SL)
# duza swieca 5m (impuls) -> kontynuacja / odwrocenie
a5 = S(h5 - l5).rolling(300).mean().values
big = end5 & ((h5 - l5) > 2.5 * a5) & (np.abs(C - o5) > 0.7 * (h5 - l5))
s = np.where(big, np.sign(C - o5), 0).astype(int)
Tt("IMPULS 5m 2.5x -> kontynuacja", s); Tt("IMPULS 5m 2.5x -> odwrocenie tp1R", -s, tp=SL)
# rozne SL/TP dla losowych (czy geometria cos zmienia)
rng = np.random.default_rng(3); sig = np.zeros(n, int); pick = rng.random(n) < 1 / 30; sig[pick] = rng.choice([-1, 1], pick.sum())
for sl, tpr in ((3.5, 2.5), (7, 1), (7, 2.5), (7, 5), (14, 2.5), (25, 2)):
    Tt(f"LOSOWE sl{sl} tp{tpr}R", sig, sl=sl, tp=sl * tpr)

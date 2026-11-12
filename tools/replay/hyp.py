import numpy as np, pandas as pd, sys
from lab import *
fn = sys.argv[1]; d = load(fn)
n = len(d); split = d.t.values[int(n * 0.6)]
print(fn, n, d.dt.iloc[0], d.dt.iloc[-1], "podzial IS/OOS:", pd.to_datetime(split, unit="s"))
C, H, L, O, M = d.c.values, d.h.values, d.l.values, d.o.values, d.m.values
a1 = atr(H, L, C, 14)
print("mediana ATR1m", np.median(a1), "mediana zasiegu 15m", np.median(pd.Series(H).rolling(15).max() - pd.Series(L).rolling(15).min()))
S = pd.Series
def ema(x, k): return S(x).ewm(span=k, adjust=False).mean().values
rng = np.random.default_rng(1)
SL = 7.0
# kontrola: losowe
sig = np.zeros(n, int); pick = rng.random(n) < 1 / 40; sig[pick] = rng.choice([-1, 1], pick.sum())
stat(sim(d, sig, SL, 2.5 * SL, be=True), "LOSOWE sl7 tp2.5R BE", split)
stat(sim(d, sig, SL, 2.5 * SL), "LOSOWE sl7 tp2.5R", split)

res = {}
def T(name, sig, sl=SL, tp=2.5 * SL, **kw):
    tr = sim(d, sig, sl, tp, **kw); res[name] = tr; stat(tr, name, split); return tr

# A. wybicie Donchiana na zamknieciu (N min), z filtrem trendu H1
for N in (30, 60, 120, 240):
    hh = S(H).rolling(N).max().shift(1).values; ll = S(L).rolling(N).min().shift(1).values
    up = (C > hh) & (np.roll(C, 1) <= np.roll(hh, 1)); dn = (C < ll) & (np.roll(C, 1) >= np.roll(ll, 1))
    sig = up.astype(int) - dn.astype(int)
    T(f"A wybicie {N}m", sig); T(f"A wybicie {N}m BE", sig, be=True)
    T(f"A' FADE wybicia {N}m", -sig); T(f"A' FADE wybicia {N}m tp1R", -sig, tp=SL)
    e = ema(C, 60 * 4); f = np.where(C > e, 1, -1)
    T(f"A wybicie {N}m + trend EMA4h", np.where(sig == f, sig, 0))
# B. trend: kierunek EMA szybka/wolna, wejscie na cofnieciu do EMA20(5m)
for fast, slow in ((60, 240), (120, 480), (240, 960)):
    ef, es = ema(C, fast), ema(C, slow); tr = np.sign(ef - es)
    e100 = ema(C, 100)
    pb_up = (tr > 0) & (L <= e100) & (C > e100) & (C > O); pb_dn = (tr < 0) & (H >= e100) & (C < e100) & (C < O)
    T(f"B cofniecie do EMA100 w trendzie {fast}/{slow}", pb_up.astype(int) - pb_dn.astype(int))
    x = np.sign(ef - es); cross = (x != np.roll(x, 1)); T(f"B przeciecie EMA {fast}/{slow}", np.where(cross, x, 0).astype(int))
# C. powrot do sredniej: odchylenie od EMA w jednostkach ATR
for k, z in ((60, 2.5), (60, 3.5), (240, 3.0), (240, 4.5)):
    e = ema(C, k); a = atr(H, L, C, k) * np.sqrt(k) / 3
    dev = (C - e) / a
    sig = np.where((dev < -z / 3) & (np.roll(dev, 1) >= -z / 3), 1, 0) - np.where((dev > z / 3) & (np.roll(dev, 1) <= z / 3), 1, 0)
    T(f"C MR odchylenie EMA{k} z{z}", sig, tp=SL); T(f"C MOM odchylenie EMA{k} z{z}", -sig)
# D. pora dnia: kierunek wg momentum poprzednich X godzin o stalych godzinach
for hm in (9 * 60, 14 * 60 + 30, 15 * 60 + 30, 16 * 60):
    for look in (60, 240):
        mom = np.sign(C - np.roll(C, look)); sig = np.where(M == hm, mom, 0).astype(int)
        T(f"D {hm//60}:{hm%60:02d} momentum {look}m", sig, tp=3 * SL, hold=360)
        T(f"D {hm//60}:{hm%60:02d} kontra {look}m", -sig, tp=3 * SL, hold=360)
# E. zasieg otwarcia (pierwsze R min po godzinie X), wybicie na zamknieciu
day = d.day.values
for start, R in ((9 * 60, 30), (9 * 60, 60), (15 * 60 + 30, 15), (15 * 60 + 30, 30), (1 * 60, 420)):
    sig = np.zeros(n, int)
    df = pd.DataFrame(dict(day=day, M=M, H=H, L=L))
    w = df[(df.M >= start) & (df.M < start + R)].groupby("day").agg(hi=("H", "max"), lo=("L", "min"))
    hi = pd.Series(day).map(w.hi).values; lo = pd.Series(day).map(w.lo).values
    act = (M >= start + R) & (M < start + R + 240)
    up = act & (C > hi) & (np.roll(C, 1) <= hi); dn = act & (C < lo) & (np.roll(C, 1) >= lo)
    # tylko pierwsze wybicie dnia
    s = up.astype(int) - dn.astype(int); first = pd.Series(s != 0).groupby(day).cumsum().values == 1
    s = np.where(first, s, 0)
    T(f"E ORB {start//60}:{start%60:02d}+{R}m", s); T(f"E ORB {start//60}:{start%60:02d}+{R}m FADE", -s, tp=SL)

import numpy as np, pandas as pd
from lab import *
d = load("data/okx_xau_1m.csv"); n = len(d); S = pd.Series
C, H, L, O, M, T = d.c.values, d.h.values, d.l.values, d.o.values, d.m.values, d.t.values
split = T[int(n * 0.6)]
# ciaglosc: indeks swiecy h minut pozniej (po czasie), bez przechodzenia przez przerwy > 90 min
def fwd(h):
    j = np.searchsorted(T, T + h * 60); ok = (j < n); j = np.minimum(j, n - 1)
    r = C[j] - C; ok &= (T[j] - T <= h * 60 + 5400)
    return np.where(ok, r, np.nan)
def back(h):
    j = np.searchsorted(T, T - h * 60); return C - C[j]
vol = S(np.abs(np.diff(C, prepend=C[0]))).rolling(240).mean().values * np.sqrt(60)   # ~ sd 1h
F = {}
for h in (5, 15, 30, 60, 120, 240, 480, 1440): F[f"mom{h}"] = back(h) / vol
dayhi = d.groupby("day").h.cummax().values; daylo = d.groupby("day").l.cummin().values
F["pos_dnia"] = (C - (dayhi + daylo) / 2) / np.maximum(dayhi - daylo, 1e-9)
for k in (60, 240): 
    hh = S(H).rolling(k).max().values; ll = S(L).rolling(k).min().values
    F[f"pos{k}"] = (C - (hh + ll) / 2) / np.maximum(hh - ll, 1e-9)
F["zmiennosc_wzgl"] = S(np.abs(np.diff(C, prepend=C[0]))).rolling(30).mean().values * np.sqrt(60) / vol - 1
F["okragla"] = ((C % 50) - 25) / 25
tick = (M % 15 == 0)
print("cecha -> kierunek = znak cechy; srednia brutto w USD na transakcje (koszt 0,70). t liczone na probkach co horyzont.")
for h in (30, 60, 120, 240):
    r = fwd(h); print(f"--- horyzont {h} min, sd ruchu {np.nanstd(r[tick]):.1f} USD")
    samp = (M % max(15, min(h, 240)) == 0) & ~np.isnan(r)
    for name, f in F.items():
        out = []
        for part in (T < split, T >= split):
            m = samp & part & ~np.isnan(f)
            thr = np.nanquantile(np.abs(f[samp & (T < split) & ~np.isnan(f)]), 0.6)     # tylko silne odczyty (prog z IS)
            m2 = m & (np.abs(f) >= thr)
            x = np.sign(f[m2]) * r[m2]
            out.append((x.mean(), x.mean() / (x.std() / len(x) ** 0.5), len(x)))
        flag = "  <<<" if out[0][0] * out[1][0] > 0 and abs(out[0][1]) > 2 and abs(out[1][1]) > 1.5 else ""
        print(f"{name:16s} IS {out[0][0]:+6.2f} (t={out[0][1]:+5.1f}, n={out[0][2]:5d}) | OOS {out[1][0]:+6.2f} (t={out[1][1]:+5.1f}, n={out[1][2]:5d}){flag}")
print("--- pora dnia: sredni ruch w USD w kolejnej godzinie (Warszawa)")
r = fwd(60)
for hr in range(0, 23):
    m = (M == hr * 60 + 5) & ~np.isnan(r); a = r[m & (T < split)]; b = r[m & (T >= split)]
    print(f"{hr:02d}:05  IS {a.mean():+5.2f} (t={a.mean()/(a.std()/len(a)**.5):+4.1f})  OOS {b.mean():+5.2f} (t={b.mean()/(b.std()/len(b)**.5):+4.1f})")

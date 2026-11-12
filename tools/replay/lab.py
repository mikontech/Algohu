"""Laboratorium wejsc: sygnaly na ZAMKNIETYCH swiecach, wejscie na otwarciu nastepnej swiecy 1m.
Konserwatywnie: gdy SL i TP w tej samej swiecy -> liczymy SL. Spread 0,70 USD, 0,006 lota."""
import numpy as np, pandas as pd, sys
SPREAD = 0.70; PLN = 0.006 * 399.0

def load(fn):
    d = pd.read_csv(fn)
    d["dt"] = pd.to_datetime(d.t, unit="s", utc=True).dt.tz_convert("Europe/Warsaw")
    m = d.dt.dt.hour * 60 + d.dt.dt.minute; wd = d.dt.dt.weekday
    ok = (wd <= 4) & (m >= 5) & (m < 23 * 60)          # godziny GOLD w XTB (przyblizenie)
    d = d[ok].reset_index(drop=True)
    d["m"] = d.dt.dt.hour * 60 + d.dt.dt.minute; d["day"] = d.dt.dt.normalize()
    return d

def resample(d, n):
    g = d.set_index("dt").resample(f"{n}min", label="right", closed="left").agg(o=("o", "first"), h=("h", "max"), l=("l", "min"), c=("c", "last")).dropna()
    return g

def atr(h, l, c, n=14):
    pc = np.roll(c, 1); pc[0] = c[0]
    tr = np.maximum(h - l, np.maximum(abs(h - pc), abs(l - pc)))
    return pd.Series(tr).ewm(alpha=1 / n, adjust=False).mean().values

def sim(d, sig, sl, tp, hold=None, be=False, cooldown=0):
    """sig: tablica {-1,0,1} na zamknieciu swiecy i; sl/tp: odleglosci USD (skalar albo tablica na i)."""
    O, H, L, C, M = d.o.values, d.h.values, d.l.values, d.c.values, d.m.values
    T = d.t.values; n = len(d); half = SPREAD / 2
    sl = np.broadcast_to(sl, n); tp = np.broadcast_to(tp, n)
    out = []; i = 0; idx = np.flatnonzero(sig); k = 0; free = 0
    while k < len(idx):
        i = idx[k]; k += 1
        if i < free or i + 1 >= n or M[i] >= 22 * 60 + 30 or T[i + 1] - T[i] > 600: continue
        dr = sig[i]; e = O[i + 1] + dr * half; s = e - dr * sl[i]; t = e + dr * tp[i]; r = sl[i]; best = e
        j = i + 1; px = None
        while j < n:
            lo, hi = L[j] - dr * half, H[j] - dr * half       # ceny wyjscia (bid dla long, ask dla short)
            adverse = lo if dr > 0 else hi; fav = hi if dr > 0 else lo
            if (adverse - s) * dr <= 0: px = s; break
            if (fav - t) * dr >= 0: px = t; break
            if be:
                best = max(best, fav) if dr > 0 else min(best, fav)
                if (best - e) * dr >= r:
                    ns = max(e + dr * SPREAD, best - dr * r) if dr > 0 else min(e + dr * SPREAD, best - dr * r)
                    if (ns - s) * dr > 0: s = ns
            if M[j] >= 22 * 60 + 50 or (hold and j - i >= hold) or (j + 1 < n and T[j + 1] - T[j] > 3600):
                px = C[j] - dr * half; break
            j += 1
        if px is None: px = C[n - 1] - dr * half; j = n - 1
        out.append((T[i + 1], dr, (px - e) * dr * PLN, (px - e) * dr / r, j - i))
        free = j + cooldown
    return pd.DataFrame(out, columns=["t", "dir", "pln", "r", "bars"])

def stat(tr, label="", split=None):
    def one(x):
        if len(x) == 0: return "n=0"
        m = x.pln.mean(); se = x.pln.std() / max(1, len(x)) ** 0.5
        return f"n={len(x):5d} sr={m:+6.2f}±{se:4.2f} suma={x.pln.sum():+8.0f} win={100*(x.pln>0).mean():3.0f}%"
    s = f"{label:46s} {one(tr)}"
    if split is not None:
        s += f" | IS {one(tr[tr.t < split])} | OOS {one(tr[tr.t >= split])}"
    print(s, flush=True)

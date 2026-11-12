"""Przyblizone odtworzenie regul wejsc Algo HUD (RT impuls, ZAKRES, SMC, ORB) na swiecach 1m zlota.
Uproszczenia: tiki wewnatrz swiecy sa syntetyczne (O->L->H->C dla swiecy wzrostowej, O->H->L->C dla spadkowej),
brak turnieju (Learner), brak bramki jakosci danych, SL/TP realizowane po poziomie (bez poslizgu),
brak ciasnego trailingu po +2R (zalezy od liczby tikow)."""
import csv, sys, random, math
from datetime import datetime
from zoneinfo import ZoneInfo

WAW = ZoneInfo("Europe/Warsaw")
SPREAD = 0.70
PER_PRICE = 0.006 * 399.0          # PLN za 1 USD ruchu przy 0,006 lota
SL_MIN, SL_MAX, SMC_SL_MIN = 15.0, 20.0, 8.0
NT = 20                            # tikow na swiece


class P:  # parametry / warianty
    def __init__(self, **kw):
        self.strats = ("rt", "range", "smc", "orb")
        self.tp_r = 2.5
        self.coerce = True            # SL 15-20 PLN dla nie-SMC
        self.native_tp = False        # zachowaj cel strategii (ZAKRES)
        self.be = True                # BE po +1R i trailing 1R
        self.smc_live = True          # CHoCH na swiecy w trakcie
        self.smc_swing = True         # poziom "swing 1h"
        self.smc_trend = False        # filtr trendu M5 takze dla SMC
        self.smc_bias_strict = False  # bias 0 => tylko PDH/PDL
        self.rt_fade = False
        self.trader = True
        self.random_every = 0         # kontrola: losowe wejscie co N swiec
        self.seed = 1
        self.rt_range = 1.2; self.rt_body = 0.70; self.rt_wick = 0.15; self.rt_look = 10
        self.range_confirm = 0.3; self.smc_max = 2
        self.trader_strict = True
        self.__dict__.update(kw)


def load(fn):
    out = []
    for r in csv.DictReader(open(fn)):
        t = int(r["t"]); t -= t % 60
        o, h, l, c = float(r["o"]), float(r["h"]), float(r["l"]), float(r["c"])
        if out and out[-1][0] == t:
            continue
        out.append((t, o, max(h, o, c), min(l, o, c), c))
    return out


def ticks(o, h, l, c):
    pts = [o, l, h, c] if c >= o else [o, h, l, c]
    seg = [abs(pts[i + 1] - pts[i]) for i in range(3)]
    tot = sum(seg)
    if tot <= 0:
        return [o] * 2
    out = [o]
    for i in range(3):
        n = max(1, int(round(NT * seg[i] / tot)))
        for k in range(1, n + 1):
            out.append(pts[i] + (pts[i + 1] - pts[i]) * k / n)
    return out


def run(bars, p, verbose=False):
    rnd = random.Random(p.seed)
    n = len(bars)
    T = [b[0] for b in bars]; O = [b[1] for b in bars]; H = [b[2] for b in bars]; L = [b[3] for b in bars]; C = [b[4] for b in bars]
    # czas lokalny
    loc = [datetime.fromtimestamp(t, WAW) for t in T]
    mins = [d.hour * 60 + d.minute for d in loc]
    day = [d.toordinal() for d in loc]
    wd = [d.weekday() for d in loc]
    # wskazniki po zamknieciu swiecy i (Wilder ATR14, EMA20, EMA50)
    atr = [None] * n; e20 = [None] * n; e50 = [None] * n
    a = None; trs = []
    k20, k50 = 2 / 21, 2 / 51
    for i in range(n):
        if i > 0:
            tr = max(H[i] - L[i], abs(H[i] - C[i - 1]), abs(L[i] - C[i - 1]))
            if a is None:
                trs.append(tr)
                if len(trs) == 14: a = sum(trs) / 14
            else:
                a = (a * 13 + tr) / 14
        atr[i] = a
        if i == 19: e20[i] = sum(C[:20]) / 20
        elif i > 19: e20[i] = C[i] * k20 + e20[i - 1] * (1 - k20)
        if i == 49: e50[i] = sum(C[:50]) / 50
        elif i > 49: e50[i] = C[i] * k50 + e50[i - 1] * (1 - k50)
    # M5: zamkniete swiece 5m (lista), i dla kazdej swiecy 1m indeks ostatniej zamknietej 5m
    m5 = []; m5_at = [0] * n   # m5_at[i] = liczba zamknietych m5 gdy swieca i jest zamknieta... (przyblizenie: kubelek zamkniety gdy nastepna 1m nalezy do innego)
    cur = None
    for i in range(n):
        b5 = T[i] - T[i] % 300
        if cur is None or cur[0] != b5:
            if cur is not None: m5.append(tuple(cur))
            cur = [b5, O[i], H[i], L[i], C[i]]
        else:
            cur[2] = max(cur[2], H[i]); cur[3] = min(cur[3], L[i]); cur[4] = C[i]
        m5_at[i] = len(m5)          # liczba zamknietych 5m w chwili, gdy trwa/konczy sie 1m i
    m5loc = [datetime.fromtimestamp(b[0], WAW) for b in m5]
    m5day = [d.toordinal() for d in m5loc]; m5hour = [d.hour for d in m5loc]
    # trend M5 i ATR5 (running)
    m5e20 = [None] * len(m5); m5e50 = [None] * len(m5); m5atr = [None] * len(m5)
    a = None; trs = []
    for j in range(len(m5)):
        c5 = m5[j][4]
        if j > 0:
            tr = max(m5[j][2] - m5[j][3], abs(m5[j][2] - m5[j - 1][4]), abs(m5[j][3] - m5[j - 1][4]))
            if a is None:
                trs.append(tr)
                if len(trs) == 14: a = sum(trs) / 14
            else: a = (a * 13 + tr) / 14
        m5atr[j] = a
        if j == 19: m5e20[j] = sum(x[4] for x in m5[:20]) / 20
        elif j > 19: m5e20[j] = c5 * k20 + m5e20[j - 1] * (1 - k20)
        if j == 49: m5e50[j] = sum(x[4] for x in m5[:50]) / 50
        elif j > 49: m5e50[j] = c5 * k50 + m5e50[j - 1] * (1 - k50)

    def session(m):
        if 60 <= m < 480: return "ASIA"
        if 480 <= m < 540: return "PRE"
        if 540 <= m < 720: return "LKZ"
        if 720 <= m < 870: return "LON"
        if 870 <= m < 1020: return "NKZ"
        if 1020 <= m < 1320: return "NY"
        return "OFF"

    def blackout(m):
        return (865 <= m < 880) or (955 <= m < 970) or (1195 <= m < 1235)

    def session_block(i):
        m = mins[i]
        if wd[i] > 4 or m < 5 or m >= 23 * 60: return True
        return (23 * 60 - m) < 30

    def bias_h1(nm5):
        lo = max(0, nm5 - 300)
        groups = {}
        for j in range(lo, nm5):
            groups.setdefault(m5[j][0] // 3600, []).append(j)
        h1 = []
        for key in sorted(groups):
            g = groups[key]
            if len(g) >= 6:
                h1.append((max(m5[j][2] for j in g), min(m5[j][3] for j in g)))
        if len(h1) < 8: return 0
        def sw(idx, high):
            out = []
            for i in range(2, len(h1) - 2):
                v = h1[i][idx]
                ok = all((v >= h1[i + d][idx]) if high else (v <= h1[i + d][idx]) for d in (-2, -1, 1, 2))
                if ok: out.append(v)
            return out
        hs, ls = sw(0, True), sw(1, False)
        if len(hs) < 2 or len(ls) < 2: return 0
        hh, hl = hs[-1] > hs[-2], ls[-1] > ls[-2]
        return 1 if (hh and hl) else (-1 if (not hh and not hl) else 0)

    def levels(i):  # po zamknieciu swiecy i
        nm5 = m5_at[i]; lo = max(0, nm5 - 300)
        today = day[i]
        prevd = None
        for j in range(nm5 - 1, lo - 1, -1):
            if m5day[j] < today: prevd = m5day[j]; break
        pdh = pdl = None
        if prevd is not None:
            g = [j for j in range(lo, nm5) if m5day[j] == prevd]
            if len(g) >= 24:
                pdh = max(m5[j][2] for j in g); pdl = min(m5[j][3] for j in g)
        ah = al = None
        s = session(mins[i])
        if s not in ("ASIA", "OFF"):
            g = [j for j in range(lo, nm5) if m5day[j] == today and 1 <= m5hour[j] < 8]
            if len(g) >= 12:
                ah = max(m5[j][2] for j in g); al = min(m5[j][3] for j in g)
        a0 = max(0, i + 1 - 90, i + 1 - 300); a1 = i + 1 - 30
        sh = sl = None
        if a1 - a0 >= 20 and p.smc_swing:
            sh = max(H[a0:a1]); sl = min(L[a0:a1])
        return {"PDH": pdh, "PDL": pdl, "Asia High": ah, "Asia Low": al, "swing H 1h": sh, "swing L 1h": sl}

    def m5_trend(i):
        j = m5_at[i] - 1
        if j < 59 or m5e20[j] is None or m5e50[j] is None or not m5atr[j]: return None
        return (m5e20[j] - m5e50[j]) / m5atr[j]

    def trader_check(i, dir_, price, stop, tp, cur_o, cur_c):
        if not p.trader: return None
        if blackout(mins[i + 1]): return "news"
        a1 = atr[i]
        if a1 is None: return None
        if SPREAD > 0.2 * abs(price - stop): return "spread"
        if a1 < SPREAD: return "dead"
        if not p.trader_strict: return None
        tr = m5_trend(i)
        if tr is not None and tr * dir_ < -0.5: return "trend5m"
        if (cur_c - cur_o) * dir_ > 2 * a1: return "chase-candle"
        if e20[i] is not None and (price - e20[i]) * dir_ > 2.5 * a1: return "chase-ema"
        lo = max(0, i - 59)
        wall = max(H[lo:i + 1]) if dir_ > 0 else min(L[lo:i + 1])
        room = (wall - price) * dir_
        if 0 < room < abs(price - stop): return "wall"
        return None

    trades = []; blocks = {}
    pos = None
    rt_last = -1; range_last = -1
    low_seen = math.inf; high_seen = -math.inf
    smc_taken = set(); smc_sess = {}
    orb = None

    def open_pos(strat, dir_, fill, stop, tp, i, note=""):
        nonlocal pos
        pos = dict(strat=strat, dir=dir_, entry=fill, stop=stop, init=stop, tp=tp, best=fill, i=i, note=note, t=T[i])

    def build_nonsmc(strat, dir_, px, raw_stop, tp_native, i, cur_o, tp_r=None):
        entry = px + SPREAD / 2 * dir_
        a1 = atr[i] or 0.0
        if p.coerce:
            min_dist = max(3 * SPREAD, entry * 0.0005, 1.5 * a1)
            stop = entry - dir_ * min_dist if abs(entry - raw_stop) < min_dist else raw_stop
            loss = abs(entry - stop) * PER_PRICE
            tgt = min(max(loss, SL_MIN), SL_MAX)
            stop = entry - dir_ * tgt / PER_PRICE
        else:
            min_dist = max(3 * SPREAD, entry * 0.0005)
            stop = entry - dir_ * min_dist if abs(entry - raw_stop) < min_dist else raw_stop
        tp = tp_native if (p.native_tp and tp_native is not None) else entry + dir_ * (tp_r or p.tp_r) * abs(entry - stop)
        why = trader_check(i, dir_, entry, stop, tp, cur_o, px)
        if why:
            blocks[(strat, why)] = blocks.get((strat, why), 0) + 1
            return False
        open_pos(strat, dir_, entry, stop, tp, i + 1)
        return True

    def close(px_level, reason, i):
        nonlocal pos
        pnl = (px_level - pos["entry"]) * pos["dir"]
        r = abs(pos["entry"] - pos["init"])
        trades.append(dict(strat=pos["strat"], dir=pos["dir"], t=pos["t"], t2=T[i], entry=pos["entry"], exit=px_level,
                           pnl_usd=pnl, pnl_pln=pnl * PER_PRICE, r=pnl / r, reason=reason, risk_pln=r * PER_PRICE, note=pos["note"]))
        pos = None

    for i in range(320, n - 1):
        f = i + 1                       # swieca w trakcie
        if T[f] - T[i] > 3 * 3600:      # przerwa weekendowa: reset stanow dziennych
            low_seen = math.inf; high_seen = -math.inf
        a_ = atr[i]
        tk = ticks(O[f], H[f], L[f], C[f])
        ch = O[f]; cl = O[f]
        m = mins[f]
        sblock = session_block(f)
        # --- przygotowanie SMC dla tej swiecy
        smc_cands = []
        if "smc" in p.strats and a_ and not blackout(m):
            lv = levels(i)
            tol = 0.05 * a_
            start = f + 1 - 30          # m1 = zamkniete[..i] + biezaca f ; size = f+1 (indeksy absolutne)
            for dir_ in (-1, 1):
                for name in (("PDH", "Asia High", "swing H 1h") if dir_ < 0 else ("PDL", "Asia Low", "swing L 1h")):
                    lp = lv[name]
                    if lp is None: continue
                    b0 = max(0, start - 30)
                    if dir_ < 0:
                        if max(H[b0:start]) > lp + tol: continue
                        if max(max(H[start:f]), H[f]) <= lp + tol: continue
                    else:
                        if min(L[b0:start]) < lp - tol: continue
                        if min(min(L[start:f]), L[f]) >= lp - tol: continue
                    smc_cands.append((dir_, name, lp))
            if smc_cands:
                bias = bias_h1(m5_at[i])
        # --- ORB
        if "orb" in p.strats and wd[f] <= 4:
            for ev in (540, 930):
                if ev <= m < ev + 15:
                    key = (day[f], ev)
                    if orb is None or orb["key"] != key:
                        orb = dict(key=key, hi=-math.inf, lo=math.inf, fired=False, ev=ev)
        do_random = p.random_every and (i % p.random_every == 0)
        rand_tick = rnd.randrange(len(tk)) if do_random else -1
        for ti, px in enumerate(tk):
            ch = max(ch, px); cl = min(cl, px)
            # ---------- zarzadzanie pozycja ----------
            if pos is not None:
                d = pos["dir"]; half = SPREAD / 2
                exit_px = px - d * half          # cena, po ktorej zamknie XTB (bid dla longa, ask dla shorta)
                if (exit_px - pos["stop"]) * d <= 0:
                    lvl = pos["stop"] if ti > 0 else min(pos["stop"], exit_px) if d > 0 else max(pos["stop"], exit_px)
                    close(lvl, "SL" if pos["stop"] == pos["init"] else "BE/trail", f)
                elif (exit_px - pos["tp"]) * d >= 0:
                    close(pos["tp"], "TP", f)
                else:
                    pos["best"] = max(pos["best"], px) if d > 0 else min(pos["best"], px)
                    r = abs(pos["entry"] - pos["init"])
                    if p.be and (pos["best"] - pos["entry"]) * d >= max(r, 8.0 / PER_PRICE):
                        be = pos["entry"] + d * SPREAD
                        trail = pos["best"] - d * r
                        tgt = trail if (trail - be) * d > 0 else be
                        if (tgt - pos["stop"]) * d > 0: pos["stop"] = tgt
            if pos is not None:
                continue
            if a_ is None: continue
            # ---------- kontrola: losowe wejscie ----------
            if do_random and ti == rand_tick and not sblock:
                dir_ = rnd.choice((-1, 1))
                build_nonsmc("random", dir_, px, px, None, i, O[f])
                continue
            # ---------- ORB ----------
            if orb is not None and orb["key"][0] == day[f] and orb["ev"] <= m < orb["ev"] + 15 and "orb" in p.strats:
                if m < orb["ev"] + 5:
                    orb["hi"] = max(orb["hi"], px); orb["lo"] = min(orb["lo"], px)
                elif not orb["fired"] and orb["hi"] > orb["lo"]:
                    dir_ = 1 if px > orb["hi"] else (-1 if px < orb["lo"] else 0)
                    if dir_ and not sblock:
                        orb["fired"] = True
                        opp = orb["lo"] if dir_ > 0 else orb["hi"]
                        capped = px - dir_ * 1.5 * a_
                        stop = max(opp, capped) if dir_ > 0 else min(opp, capped)
                        rt_last = f
                        if build_nonsmc("orb", dir_, px, stop, None, i, O[f]): continue
            # ---------- RT impuls ----------
            if "rt" in p.strats and rt_last != f and i >= 25:
                rng = ch - cl
                if rng >= p.rt_range * a_:
                    body = abs(px - O[f]); dir_ = 1 if px > O[f] else -1
                    wick = (ch - px) if dir_ > 0 else (px - cl)
                    level = max(H[i - p.rt_look + 1:i + 1]) if dir_ > 0 else min(L[i - p.rt_look + 1:i + 1])
                    brk = px > level if dir_ > 0 else px < level
                    if body >= p.rt_body * rng and wick <= p.rt_wick * rng and brk:
                        rt_last = f
                        if not sblock:
                            base = cl - 0.1 * a_ if dir_ > 0 else ch + 0.1 * a_
                            atr_stop = px - dir_ * 0.8 * a_
                            stop = max(base, atr_stop) if dir_ > 0 else min(base, atr_stop)
                            if p.rt_fade:
                                dir_ = -dir_; stop = px - dir_ * 1.5 * a_
                            if build_nonsmc("rt", dir_, px, stop, None, i, O[f], tp_r=None): continue
            # ---------- ZAKRES ----------
            if "range" in p.strats and e20[i] is not None and e50[i] is not None:
                hi = max(H[i - 29:i + 1]); lo = min(L[i - 29:i + 1]); width = hi - lo
                trend_strong = abs(e20[i] - e50[i]) > 1.0 * a_
                narrow = width < max(2.0 * a_, 8 * SPREAD)
                if trend_strong or narrow:
                    low_seen = math.inf; high_seen = -math.inf
                elif range_last != f:
                    bottom = lo + 0.2 * width; top = hi - 0.2 * width
                    if px <= bottom: low_seen = min(low_seen, px)
                    if px >= top: high_seen = max(high_seen, px)
                    if px > lo + 0.5 * width: low_seen = math.inf
                    if px < hi - 0.5 * width: high_seen = -math.inf
                    up = low_seen != math.inf and px - low_seen >= p.range_confirm * a_ and px <= lo + 0.4 * width
                    dn = high_seen != -math.inf and high_seen - px >= p.range_confirm * a_ and px >= hi - 0.4 * width
                    if up or dn:
                        dir_ = 1 if up else -1
                        stop = (min(low_seen, lo) - 0.3 * a_) if dir_ > 0 else (max(high_seen, hi) + 0.3 * a_)
                        target = top if dir_ > 0 else bottom
                        entry = px + SPREAD / 2 * dir_
                        low_seen = math.inf; high_seen = -math.inf
                        if (target - entry) * dir_ >= 1.0 * abs(entry - stop):
                            range_last = f
                            if not sblock and build_nonsmc("range", dir_, px, stop, target, i, O[f]): continue
            # ---------- SMC ----------
            if smc_cands and not sblock:
                # m1[k] dla k<=i zamkniete; k==f biezaca (high=ch, low=cl, close=px)
                def hi_(k): return ch if k == f else H[k]
                def lo_(k): return cl if k == f else L[k]
                def cl_(k): return px if k == f else C[k]
                size = f + 1; start = size - 30
                best = None
                buf = max(0.2 * a_, 1.5 * SPREAD)
                last_k = f if p.smc_live else i
                size_eff = last_k + 1
                for dir_, name, lp in smc_cands:
                    sw_i = None
                    for k in range(start, size_eff):
                        if (hi_(k) > lp + tol) if dir_ < 0 else (lo_(k) < lp - tol): sw_i = k; break
                    if sw_i is None: continue
                    rec = None
                    for k in range(sw_i, size_eff):
                        if (cl_(k) < lp) if dir_ < 0 else (cl_(k) > lp): rec = k; break
                    if rec is None: continue
                    ext = sw_i
                    for k in range(sw_i, size_eff):
                        if (hi_(k) > hi_(ext)) if dir_ < 0 else (lo_(k) < lo_(ext)): ext = k
                    extreme = hi_(ext) if dir_ < 0 else lo_(ext)
                    if abs(extreme - lp) > 2.5 * a_: continue
                    # lastSwing(m1, max(0,ext-15), ext+1, high = dir>0)
                    swv = None
                    for k in range(ext - 2, max(ext - 15 + 2, 2) - 1, -1):
                        if k + 2 >= size_eff: continue
                        if dir_ > 0:
                            v = hi_(k); ok = all(v >= hi_(k + d) for d in (-2, -1, 1, 2))
                        else:
                            v = lo_(k); ok = all(v <= lo_(k + d) for d in (-2, -1, 1, 2))
                        if ok: swv = v; break
                    if swv is None: continue
                    choch = None
                    for k in range(max(ext, rec), size_eff):
                        if (cl_(k) < swv) if dir_ < 0 else (cl_(k) > swv): choch = k; break
                    if choch is None: continue
                    if size_eff - 1 - choch > 1: continue
                    strong = name in ("PDH", "PDL")
                    if bias != 0 and bias != dir_ and not strong: continue
                    if p.smc_bias_strict and bias != dir_ and not strong: continue
                    if p.smc_trend:
                        tr = m5_trend(i)
                        if tr is not None and tr * dir_ < -0.5: continue
                    stop = extreme - dir_ * buf
                    risk = abs(px - stop)
                    if risk <= 0 or (px - stop) * dir_ <= 0: continue
                    if SPREAD > 0.2 * risk: continue
                    if (swv - px) * dir_ < 0 and abs(px - swv) > 0.35 * risk: continue
                    names = ("PDL", "Asia Low", "swing L 1h") if dir_ < 0 else ("PDH", "Asia High", "swing H 1h")
                    tg = sorted([(abs(lv[x] - px), x, lv[x]) for x in names if lv[x] is not None and (lv[x] - px) * dir_ > 0])
                    sel = None
                    for _, x, lpx in tg:
                        tp = lpx - dir_ * 0.1 * a_
                        rr = (abs(tp - px) - SPREAD) / (risk + SPREAD)
                        if rr >= 2.5: sel = (tp, rr, x); break
                    if sel is None: continue
                    s = dict(dir=dir_, stop=stop, tp=sel[0], rr=sel[1], key=(name, T[ext]), name=name, live=(choch == f))
                    if best is None or s["rr"] > best["rr"]: best = s
                if best is not None and best["key"] not in smc_taken:
                    sk = (session(m), day[f])
                    if smc_sess.get(sk, 0) < p.smc_max:
                        smc_taken.add(best["key"])
                        d = best["dir"]; stop = best["stop"]
                        loss = abs(px - stop) * PER_PRICE
                        ok = loss <= SL_MAX
                        if ok:
                            tgt = min(max(loss, SMC_SL_MIN), SL_MAX)
                            if abs(loss - tgt) > 0.01: stop = px - d * tgt / PER_PRICE
                            rr = abs(best["tp"] - px) / abs(px - stop)
                            ok = rr >= 2.5
                        if ok:
                            smc_sess[sk] = smc_sess.get(sk, 0) + 1
                            fill = px + d * SPREAD / 2
                            open_pos("smc", d, fill, stop, best["tp"], f, note=best["name"] + ("/live" if best["live"] else "/close"))
    return trades, blocks


def summarize(trades, label):
    by = {}
    for t in trades: by.setdefault(t["strat"], []).append(t)
    rows = []
    for s, ts in sorted(by.items()):
        n = len(ts); w = sum(1 for t in ts if t["pnl_pln"] > 0.5); l = sum(1 for t in ts if t["pnl_pln"] < -0.5)
        tot = sum(t["pnl_pln"] for t in ts); avg = tot / n
        sd = (sum((t["pnl_pln"] - avg) ** 2 for t in ts) / max(1, n - 1)) ** 0.5
        se = sd / n ** 0.5 if n > 1 else float("nan")
        tp = sum(1 for t in ts if t["reason"] == "TP"); sl = sum(1 for t in ts if t["reason"] == "SL")
        rows.append((label, s, n, w, l, tp, sl, n - tp - sl, tot, avg, se, sum(t["r"] for t in ts) / n))
        print(f"{label:28s} {s:7s} n={n:4d} zysk={w:4d} strata={l:4d} TP={tp:3d} SL={sl:4d} BE/trail={n-tp-sl:4d} suma={tot:9.1f} PLN  sr={avg:6.2f}±{se:4.2f} PLN  srR={rows[-1][-1]:+.3f}")
    return rows


if __name__ == "__main__":
    fn = sys.argv[1]
    bars = load(fn)
    print(fn, len(bars), datetime.fromtimestamp(bars[0][0], WAW), datetime.fromtimestamp(bars[-1][0], WAW))
    for s in ("rt", "range", "smc", "orb"):
        tr, bl = run(bars, P(strats=(s,)))
        summarize(tr, "jak w aplikacji/osobno")
        print("   blokady:", {k[1]: v for k, v in bl.items()})
    tr, bl = run(bars, P())
    summarize(tr, "jak w aplikacji/razem")
    print("   RAZEM:", len(tr), "transakcji, suma", round(sum(t["pnl_pln"] for t in tr), 1), "PLN")

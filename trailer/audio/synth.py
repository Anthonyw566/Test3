"""
A small synthesis toolkit: everything the score needs, built from numpy.

No samples, no sample libraries: every sound in the trailer starts as a sine,
a sawtooth or white noise and is shaped here with envelopes, filters, physical
models (Karplus-Strong strings, additive bells) and a convolution reverb whose
impulse response is itself synthesized.
"""
import numpy as np
from scipy import signal

SR = 48000
RNG = np.random.default_rng(7)


def seconds(n):
    return np.arange(int(round(n * SR))) / SR


def midi(m):
    return 440.0 * 2.0 ** ((np.asarray(m, dtype=float) - 69.0) / 12.0)


def db(x):
    return 10.0 ** (x / 20.0)


# --------------------------------------------------------------- oscillators

def phase_of(freq, n):
    """Phase (in cycles) for a constant or per-sample frequency."""
    if np.isscalar(freq):
        return np.arange(n) * (freq / SR)
    return np.cumsum(np.asarray(freq) / SR)


def sine(freq, dur, phase0=0.0):
    n = int(round(dur * SR))
    return np.sin(2 * np.pi * (phase_of(freq, n) + phase0))


def saw(freq, dur, phase0=None):
    """Band-limited sawtooth (polyBLEP), constant or swept frequency."""
    n = int(round(dur * SR))
    ph = phase_of(freq, n) + (RNG.random() if phase0 is None else phase0)
    t = ph % 1.0
    dt = (np.full(n, freq) if np.isscalar(freq) else np.asarray(freq)) / SR
    y = 2.0 * t - 1.0
    m = t < dt
    x = t[m] / dt[m]
    y[m] -= x + x - x * x - 1.0
    m = t > 1.0 - dt
    x = (t[m] - 1.0) / dt[m]
    y[m] -= x * x + x + x + 1.0
    return y


def supersaw(freq, dur, voices=7, detune_cents=18.0, stereo=True):
    """Detuned saw stack; returns (L, R)."""
    offs = np.linspace(-1, 1, voices) * detune_cents
    L = np.zeros(int(round(dur * SR)))
    R = np.zeros_like(L)
    for i, c in enumerate(offs):
        f = freq * 2 ** (c / 1200.0)
        v = saw(f, dur)
        p = (i / (voices - 1)) if stereo else 0.5
        L += v * np.cos(p * np.pi / 2)
        R += v * np.sin(p * np.pi / 2)
    return L / voices * 1.6, R / voices * 1.6


def noise(dur):
    return RNG.standard_normal(int(round(dur * SR)))


def pink(dur):
    # Voss-McCartney-ish: filter white noise with the classic 3-pole approximation.
    w = noise(dur)
    b = [0.049922035, -0.095993537, 0.050612699, -0.004408786]
    a = [1, -2.494956002, 2.017265875, -0.522189400]
    return signal.lfilter(b, a, w) * 6.0


def brown(dur):
    w = noise(dur)
    y = signal.lfilter([1.0], [1.0, -0.995], w)
    return y / (np.max(np.abs(y)) + 1e-9)


# --------------------------------------------------------------- envelopes

def adsr(n, a=0.01, d=0.1, s=0.7, r=0.3, hold=None):
    """ADSR over n samples; release starts at `hold` seconds (default: end - r)."""
    t = np.arange(n) / SR
    total = n / SR
    hold = total - r if hold is None else hold
    env = np.where(t < a, t / max(a, 1e-6), 1.0)
    dec = (t >= a) & (t < a + d)
    env[dec] = 1.0 - (1.0 - s) * (t[dec] - a) / max(d, 1e-6)
    env[t >= a + d] = s
    rel = t >= hold
    env[rel] = s * np.exp(-(t[rel] - hold) / max(r / 4.0, 1e-4))
    return env


def expdecay(n, tau):
    return np.exp(-np.arange(n) / SR / tau)


def fade(x, fin=0.005, fout=0.02):
    n = len(x)
    a, b = int(fin * SR), int(fout * SR)
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[-b:] *= np.linspace(1, 0, b)
    return x


# --------------------------------------------------------------- filters

def _sos(kind, fc, q=0.707, order=2):
    fc = np.clip(fc, 10, SR * 0.45)
    if kind == 'band':
        bw = fc / q
        lo, hi = max(10, fc - bw / 2), min(SR * 0.45, fc + bw / 2)
        return signal.butter(order, [lo, hi], btype='band', fs=SR, output='sos')
    return signal.butter(order, fc, btype=kind, fs=SR, output='sos')


def lowpass(x, fc, order=2):
    return signal.sosfilt(_sos('low', fc, order=order), x)


def highpass(x, fc, order=2):
    return signal.sosfilt(_sos('high', fc, order=order), x)


def bandpass(x, fc, q=1.0, order=2):
    return signal.sosfilt(_sos('band', fc, q, order), x)


def biquad_coeffs(kind, fc, q):
    w0 = 2 * np.pi * np.clip(fc, 10, SR * 0.45) / SR
    alpha = np.sin(w0) / (2 * q)
    c = np.cos(w0)
    if kind == 'low':
        b = [(1 - c) / 2, 1 - c, (1 - c) / 2]
    elif kind == 'high':
        b = [(1 + c) / 2, -(1 + c), (1 + c) / 2]
    else:  # band (constant peak gain)
        b = [alpha, 0, -alpha]
    a = [1 + alpha, -2 * c, 1 - alpha]
    return np.array(b) / a[0], np.array(a) / a[0]


def sweep(x, fc, q=0.9, kind='low', block=128):
    """Time-varying resonant biquad; fc is a per-sample array (or callable of t)."""
    n = len(x)
    if callable(fc):
        fc = fc(np.arange(n) / SR)
    fc = np.broadcast_to(fc, (n,))
    y = np.zeros(n)
    zi = np.zeros(2)
    for s in range(0, n, block):
        e = min(n, s + block)
        b, a = biquad_coeffs(kind, float(fc[s]), q)
        y[s:e], zi = signal.lfilter(b, a, x[s:e], zi=zi)
    return y


def formant(x, vowel='a'):
    table = {'a': [(800, 1.0), (1150, 0.5), (2900, 0.25)], 'o': [(450, 1.0), (800, 0.5), (2830, 0.15)],
             'u': [(325, 1.0), (700, 0.3), (2530, 0.1)]}
    return sum(bandpass(x, f, q=8, order=1) * g for f, g in table[vowel])


# --------------------------------------------------------------- instruments

def pluck(freq, dur, bright=0.5, decay=0.996, seed=None):
    """Karplus-Strong plucked string, vectorized one period at a time and
    resampled so the pitch is exact."""
    rng = np.random.default_rng(seed)
    N = max(2, int(round(SR / freq)))
    total = int(dur * SR) + 4
    y = np.zeros(total + N + 1)
    burst = rng.uniform(-1, 1, N)
    burst = lowpass(burst, 600 + 9000 * bright, order=1)
    y[1:N + 1] = burst
    for s in range(N + 1, total + N + 1, N):
        e = min(total + N + 1, s + N)
        idx = np.arange(s, e)
        y[s:e] = decay * 0.5 * (y[idx - N] + y[idx - N - 1])
    y = y[1:]  # keep the first period: it's the pick attack
    ratio = N * freq / SR
    pos = np.arange(int(dur * SR)) * ratio
    out = np.interp(pos, np.arange(len(y)), y)
    return fade(out / (np.max(np.abs(out)) + 1e-9), 0.0005, 0.05)


RISSET = [(0.56, 1.0, 1.0), (0.56, 0.67, 0.9), (0.92, 1.0, 0.65), (0.92, 1.8, 0.55), (1.19, 2.67, 0.325),
          (1.7, 1.67, 0.35), (2.0, 1.46, 0.25), (2.74, 1.33, 0.2), (3.0, 1.33, 0.15), (3.76, 1.0, 0.1), (4.07, 1.33, 0.075)]


def bell(freq, dur, decay=2.0, bright=1.0):
    """Risset's additive bell: inharmonic partials, higher ones die faster."""
    t = seconds(dur)
    y = np.zeros_like(t)
    for ratio, amp, d in RISSET:
        f = freq * ratio * 1.79  # tuned so the strike tone sits on `freq`
        if f > SR * 0.45:
            continue
        a = amp * (bright if ratio > 2 else 1.0)
        y += a * np.sin(2 * np.pi * f * t + RNG.random() * 6.28) * np.exp(-t / (decay * d))
    y *= 1 - np.exp(-t / 0.002)
    return y / 3.0


def taiko(dur=1.3, f0=110, f1=46, tau=0.42, slap=0.6):
    t = seconds(dur)
    f = f1 + (f0 - f1) * np.exp(-t / 0.05)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / tau)
    skin = np.sin(2 * np.pi * 190 * t) * np.exp(-t / 0.05) * 0.4
    s = bandpass(noise(dur), 900, q=0.8) * np.exp(-t / 0.025) * slap
    y = np.tanh(1.8 * (body + skin + s))
    return fade(y, 0.0005, 0.05)


def thump(dur=0.5, f0=70, f1=38, tau=0.12):
    t = seconds(dur)
    f = f1 + (f0 - f1) * np.exp(-t / 0.03)
    return fade(np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / tau), 0.0005, 0.02)


def heartbeat(dur=0.9, strength=1.0):
    y = np.zeros(int(dur * SR))
    for off, amp in ((0.0, 1.0), (0.2, 0.7)):
        h = thump(0.45, 62, 34, 0.09) * amp
        h += lowpass(noise(0.45), 120) * np.exp(-seconds(0.45) / 0.03) * 0.25 * amp
        i = int(off * SR)
        y[i:i + len(h)] += h[: len(y) - i]
    return np.tanh(y * 1.6 * strength)


def impact(dur=3.0, sub=1.0, crack=1.0, body=1.0):
    """Trailer hit: sub drop, noise body with a closing filter, a crack on top."""
    t = seconds(dur)
    f = 32 + 48 * np.exp(-t / 0.25)
    s = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 1.1) * sub
    b = sweep(noise(dur), lambda tt: 200 + 7000 * np.exp(-tt / 0.08), q=0.7) * np.exp(-t / 0.5) * 0.5 * body
    c = highpass(noise(dur), 2500) * np.exp(-t / 0.015) * 0.6 * crack
    y = np.tanh(1.5 * (s + b + c))
    return fade(y, 0.0003, 0.2)


def braam(root, dur=3.5, cutoff=1800, drive=2.2):
    """Low brass 'braam': stacked detuned saws through a closing filter, driven."""
    L = np.zeros(int(dur * SR))
    R = np.zeros_like(L)
    for m, g in ((root - 12, 1.0), (root, 0.8), (root + 7, 0.5), (root + 12, 0.35)):
        l, r = supersaw(midi(m), dur, voices=5, detune_cents=14)
        L += l * g
        R += r * g
    t = seconds(dur)
    fc = lambda tt: 120 + cutoff * np.exp(-tt / 0.6) + 300 * np.exp(-tt / 2.5)
    env = (1 - np.exp(-t / 0.03)) * np.exp(-t / 2.2)
    L = np.tanh(sweep(L, fc, q=1.2) * drive) * env
    R = np.tanh(sweep(R, fc, q=1.2) * drive) * env
    s = sine(midi(root - 12), dur) * env * 0.6
    return fade(L + s, 0.001, 0.3), fade(R + s, 0.001, 0.3)


def pad(notes, dur, cutoff=1400, attack=0.6, release=0.8, detune=12, bright_end=None):
    """String-ish pad from supersaws, with an optional filter opening."""
    L = np.zeros(int(dur * SR))
    R = np.zeros_like(L)
    for m in notes:
        l, r = supersaw(midi(m), dur, voices=5, detune_cents=detune)
        L += l
        R += r
    L /= len(notes) ** 0.7
    R /= len(notes) ** 0.7
    c1 = bright_end or cutoff
    fc = lambda tt: cutoff + (c1 - cutoff) * np.clip(tt / dur, 0, 1)
    L = sweep(L, fc, q=0.8)
    R = sweep(R, fc, q=0.8)
    env = adsr(len(L), a=attack, d=0.3, s=0.85, r=release)
    return L * env, R * env


def choir(notes, dur, attack=1.2, release=1.5, vowel='a'):
    L = np.zeros(int(dur * SR))
    R = np.zeros_like(L)
    for m in notes:
        vib = midi(m) * (1 + 0.004 * np.sin(2 * np.pi * 5.1 * seconds(dur) + RNG.random() * 6))
        l, r = supersaw(vib, dur, voices=5, detune_cents=9)
        L += formant(l, vowel)
        R += formant(r, vowel)
    env = adsr(len(L), a=attack, d=0.5, s=0.9, r=release)
    g = 1.2 / len(notes) ** 0.6
    return L * env * g, R * env * g


def shepard(dur, rate=0.12, base=40.0, octaves=9):
    """Endlessly rising Shepard-Risset glissando."""
    t = seconds(dur)
    y = np.zeros_like(t)
    for k in range(octaves):
        pos = (k + t * rate) % octaves
        f = base * 2 ** pos
        amp = np.exp(-0.5 * ((pos - octaves / 2) / (octaves / 6)) ** 2)
        y += amp * np.sin(2 * np.pi * np.cumsum(f) / SR)
    return y / octaves * 3


def whoosh(dur=0.9, f_lo=250, f_hi=3200, peak=0.75):
    """Air past the camera: band-passed noise sweeping up then down."""
    t = seconds(dur)
    k = t / dur
    fc = np.where(k < peak, f_lo * (f_hi / f_lo) ** (k / peak), f_hi * (f_lo / f_hi) ** ((k - peak) / (1 - peak)))
    y = sweep(noise(dur), fc, q=1.3, kind='band')
    env = np.where(k < peak, (k / peak) ** 2.2, np.exp(-(k - peak) / (1 - peak) * 4))
    return fade(y * env, 0.002, 0.02)


def riser(dur=2.0, f0=200, f1=3000):
    t = seconds(dur)
    k = t / dur
    n = sweep(noise(dur), f0 * (f1 / f0) ** k, q=0.9, kind='band')
    tone = sine(f0 * 0.5 * (f1 / f0) ** (k * 0.6), dur) * 0.15
    return (n + tone) * k ** 2.5


def crackle(dur, density=28.0, seed=3):
    """Fire: a low roar plus Poisson-timed crackles and the odd deeper pop."""
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    y = lowpass(brown(dur), 500) * 0.25
    count = rng.poisson(density * dur)
    for _ in range(count):
        i = rng.integers(0, n - 2000)
        ln = rng.integers(60, 600)
        amp = rng.pareto(2.5) * 0.15
        g = highpass(rng.standard_normal(ln), 1500 if rng.random() < 0.8 else 400) * np.exp(-np.arange(ln) / (ln / 5))
        y[i:i + ln] += g * min(amp, 1.0)
    return y


def wind(dur, seed=5):
    rng = np.random.default_rng(seed)
    t = seconds(dur)
    lfo = 0.5 + 0.5 * np.sin(2 * np.pi * (0.07 * t + rng.random()))
    fc = 260 + 900 * lfo
    y = sweep(pink(dur), fc, q=1.4, kind='band')
    return y * (0.55 + 0.45 * np.sin(2 * np.pi * 0.11 * t + 1.3) ** 2)


def glass(dur=1.2, seed=11):
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    L = np.zeros(n)
    R = np.zeros(n)
    hit = highpass(noise(0.05), 2000) * np.exp(-seconds(0.05) / 0.006)
    L[:len(hit)] += hit
    R[:len(hit)] += hit
    for _ in range(55):
        i = int(rng.uniform(0, 0.6) ** 1.6 * SR)
        f = rng.uniform(2800, 9500)
        ln = int(rng.uniform(0.02, 0.12) * SR)
        p = sine(f, ln / SR) * np.exp(-np.arange(ln) / ln * 5) * rng.uniform(0.05, 0.3)
        pan = rng.random()
        e = min(n, i + ln)
        L[i:e] += p[: e - i] * np.cos(pan * np.pi / 2)
        R[i:e] += p[: e - i] * np.sin(pan * np.pi / 2)
    shat = highpass(noise(dur), 3000) * np.exp(-seconds(dur) / 0.12) * 0.25
    return L + shat, R + shat


def crumble(dur=1.6, seed=13):
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    y = np.zeros(n)
    for _ in range(140):
        i = int(rng.exponential(0.25) * SR)
        if i >= n - 4000:
            continue
        ln = int(rng.uniform(0.01, 0.07) * SR)
        g = lowpass(rng.standard_normal(ln), rng.uniform(300, 1800)) * np.exp(-np.arange(ln) / (ln / 4))
        y[i:i + ln] += g * rng.uniform(0.2, 1.0)
    rum = lowpass(brown(dur), 90) * np.exp(-seconds(dur) / 0.6) * 1.5
    return fade(np.tanh(y * 0.8 + rum), 0.0005, 0.3)


def zap(dur=0.22, f0=2400, f1=180):
    t = seconds(dur)
    f = f0 * (f1 / f0) ** (t / dur)
    m = np.sin(2 * np.pi * np.cumsum(f * 1.5) / SR) * 3
    y = np.sin(2 * np.pi * np.cumsum(f) / SR + m) * np.exp(-t / (dur / 3))
    return fade(y, 0.001, 0.02)


def portal(dur=1.4):
    t = seconds(dur)
    f = 180 * 2 ** (1.5 * np.sin(np.pi * t / dur)) * (1 + 0.03 * np.sin(2 * np.pi * 7 * t))
    idx = 2 + 4 * np.sin(np.pi * t / dur)
    y = np.sin(2 * np.pi * np.cumsum(f) / SR + idx * np.sin(2 * np.pi * np.cumsum(f * 1.41) / SR))
    # flanger: mix with a modulated short delay
    d = (0.002 + 0.0015 * np.sin(2 * np.pi * 0.8 * t)) * SR
    idx_d = np.clip(np.arange(len(y)) - d, 0, len(y) - 1)
    y = y + np.interp(idx_d, np.arange(len(y)), y)
    return y * np.sin(np.pi * t / dur) ** 1.5 * 0.5


def hiss(dur=1.2):
    t = seconds(dur)
    y = sweep(noise(dur), lambda tt: 3000 + 2500 * np.sin(2 * np.pi * 1.3 * tt), q=1.5, kind='band')
    env = (1 - np.exp(-t / 0.03)) * np.exp(-t / 0.5)
    b = np.zeros_like(y)
    for _ in range(18):
        i = int(RNG.uniform(0, dur * 0.8) * SR)
        ln = int(0.035 * SR)
        f = RNG.uniform(260, 700) * 2 ** (np.arange(ln) / ln * 1.2)
        bb = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-np.arange(ln) / ln * 4) * 0.4
        e = min(len(b), i + ln)
        b[i:e] += bb[: e - i]
    return y * env + b


def growl(dur=2.2, f=52):
    t = seconds(dur)
    jitter = 1 + 0.05 * lowpass(noise(dur), 12) * 8
    s = saw(f * jitter, dur)
    s = formant(s, 'o') * 2 + lowpass(noise(dur), 900) * 0.3
    env = np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 0.8
    return np.tanh(s * env * 2.5) * 0.6


def tick(f=3200, dur=0.03):
    return bandpass(noise(dur), f, q=4) * np.exp(-seconds(dur) / 0.004)


def clunk(seed=0):
    rng = np.random.default_rng(seed)
    dur = 0.5
    t = seconds(dur)
    y = thump(dur, 90, 55, 0.05) * 0.8
    y += bandpass(noise(dur), 4000, q=2) * np.exp(-t / 0.004) * 0.8
    for f, a in ((1130, 0.25), (1745, 0.18), (2660, 0.12)):
        y += np.sin(2 * np.pi * f * rng.uniform(0.97, 1.03) * t) * np.exp(-t / 0.09) * a
    return fade(y, 0.0003, 0.05)


# --------------------------------------------------------------- spaces & dynamics

def reverb_ir(dur=3.2, predelay=0.018, damp=0.6, seed=21):
    """Synthetic hall: decorrelated stereo noise with frequency-dependent decay
    (highs die faster) and a few early reflections."""
    rng = np.random.default_rng(seed)
    t = seconds(dur)
    out = []
    for ch in range(2):
        n = rng.standard_normal(len(t))
        bands = [(0, 400, 1.0), (400, 2000, 0.75), (2000, 6000, 0.5 * (1 - damp) + 0.25), (6000, 20000, 0.22)]
        ir = np.zeros_like(t)
        for lo, hi, k in bands:
            if lo == 0:
                b = lowpass(n, hi)
            elif hi >= 20000:
                b = highpass(n, lo)
            else:
                b = bandpass(n, np.sqrt(lo * hi), q=np.sqrt(lo * hi) / (hi - lo))
            ir += b * np.exp(-t / (dur / 6.9 * k))
        for i in range(8):
            d = int(rng.uniform(0.005, 0.06) * SR)
            ir[d] += rng.uniform(-0.6, 0.6)
        pre = int(predelay * SR)
        ir = np.concatenate([np.zeros(pre), ir])[: len(t)]
        out.append(ir / np.sqrt(np.sum(ir ** 2)))
    return out


def convolve_stereo(L, R, ir):
    return signal.fftconvolve(L, ir[0])[: len(L)], signal.fftconvolve(R, ir[1])[: len(R)]


def k_weight(x):
    # ITU-R BS.1770 pre-filter + RLB high-pass, at 48 kHz.
    b1 = [1.53512485958697, -2.69169618940638, 1.19839281085285]
    a1 = [1.0, -1.69065929318241, 0.73248077421585]
    b2 = [1.0, -2.0, 1.0]
    a2 = [1.0, -1.99004745483398, 0.99007225036621]
    return signal.lfilter(b2, a2, signal.lfilter(b1, a1, x))


def lufs(L, R):
    """Integrated loudness (BS.1770-4, absolute + relative gating)."""
    kl, kr = k_weight(L), k_weight(R)
    blk, hop = int(0.4 * SR), int(0.1 * SR)
    z = []
    for s in range(0, len(L) - blk, hop):
        z.append(np.mean(kl[s:s + blk] ** 2) + np.mean(kr[s:s + blk] ** 2))
    z = np.array(z)
    lk = -0.691 + 10 * np.log10(z + 1e-12)
    z = z[lk > -70]
    rel = -0.691 + 10 * np.log10(np.mean(z) + 1e-12) - 10
    z = z[(-0.691 + 10 * np.log10(z + 1e-12)) > rel]
    return -0.691 + 10 * np.log10(np.mean(z) + 1e-12)


def limiter(L, R, ceiling_db=-1.0, lookahead=0.004, release=0.12):
    from scipy.ndimage import minimum_filter1d
    c = db(ceiling_db)
    peak = np.maximum(np.abs(L), np.abs(R))
    g = np.minimum(1.0, c / (peak + 1e-12))
    la = int(lookahead * SR)
    g = minimum_filter1d(g, size=2 * la + 1)
    # smooth: instant attack (already looked ahead), exponential release
    a = np.exp(-1.0 / (release * SR))
    g_s = signal.lfilter([1 - a], [1, -a], g)
    g = np.minimum(g, g_s)
    g = np.concatenate([g[la:], np.full(la, g[-1])])
    return L * g, R * g


def glue(L, R, threshold_db=-18.0, ratio=2.0, attack=0.01, release=0.25):
    """Gentle RMS bus compressor."""
    lvl = np.sqrt(signal.lfilter([1 - np.exp(-1 / (attack * SR))], [1, -np.exp(-1 / (attack * SR))], (L ** 2 + R ** 2) / 2) + 1e-12)
    ldb = 20 * np.log10(lvl)
    over = np.maximum(0, ldb - threshold_db)
    gr = -over * (1 - 1 / ratio)
    a = np.exp(-1 / (release * SR))
    gr = signal.lfilter([1 - a], [1, -a], gr)
    g = db(gr)
    return L * g, R * g

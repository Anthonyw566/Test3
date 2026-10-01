"""
The score and sound design, composed against timeline.json.

The picture is cut to a 96 BPM bar grid (one bar = 2.5 s), so the music and
the edit share one clock: every ring crossing lands on a downbeat, every Heat
jump on a beat. Every effect below is placed from the same cue times the
renderer animates from, which is what keeps sound and picture in sync.

    python3 audio/score.py            -> out/audio.wav
"""
import json
import os
import sys
import numpy as np
from scipy.io import wavfile

sys.path.insert(0, os.path.dirname(__file__))
from synth import *  # noqa: E402,F403

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TL = json.load(open(os.path.join(ROOT, 'timeline.json')))
DUR = TL['duration']
N = int(round(DUR * SR))
BEAT = 60.0 / TL['bpm']
BAR = 4 * BEAT
CUE = TL['cues']
CUT = 35.0  # the hard drop to silence before the Heat gauge


# ------------------------------------------------------------------ mixing desk

class Layer:
    """A set of stereo buses plus a reverb send. Two layers exist so the
    trailer can cut to dead silence at 35 s: everything before the cut,
    reverb tails included, lives in layer A and is gated off."""

    def __init__(self):
        self.bus = {k: [np.zeros(N), np.zeros(N)] for k in ('music', 'sfx', 'amb')}
        self.send = [np.zeros(N), np.zeros(N)]

    def place(self, bus, sig, t, gain_db=0.0, pan=0.0, send=0.0):
        if isinstance(sig, tuple):
            L, R = sig
        else:
            a = (pan + 1) * np.pi / 4
            L, R = sig * np.cos(a) * 1.414, sig * np.sin(a) * 1.414
        i = int(round(t * SR))
        if i >= N:
            return
        j = max(0, -i)
        i = max(0, i)
        n = min(len(L) - j, N - i)
        if n <= 0:
            return
        g = db(gain_db)
        B = self.bus[bus]
        B[0][i:i + n] += L[j:j + n] * g
        B[1][i:i + n] += R[j:j + n] * g
        if send > 0:
            self.send[0][i:i + n] += L[j:j + n] * g * send
            self.send[1][i:i + n] += R[j:j + n] * g * send


A, B = Layer(), Layer()
DUCKS = []  # (time, depth_db, release_s): music ducks under big hits


def layer(t):
    return A if t < CUT else B


def P(bus, sig, t, gain_db=0.0, pan=0.0, send=0.0):
    layer(t).place(bus, sig, t, gain_db, pan, send)


def hit(t, gain_db=-8.0, sub=1.0, duck=6.0, send=0.35):
    P('sfx', impact(3.0, sub=sub), t, gain_db, 0.0, send)
    DUCKS.append((t, duck, 0.6))


def pluck_note(m, t, dur=1.4, gain_db=-20, pan=0.0, bright=0.5, send=0.25, decay=0.996):
    P('music', pluck(float(midi(m)), dur, bright=bright, decay=decay, seed=int(t * 1000) % 99991), t, gain_db + 3.5, pan, send)


def bass_note(m, t, dur, gain_db=-14, drive=1.0, cutoff=260):
    tt = seconds(dur)
    f = float(midi(m))
    y = sine(f, dur) + 0.45 * lowpass(saw(f, dur), cutoff)
    env = (1 - np.exp(-tt / 0.004)) * np.exp(-tt / max(0.08, dur * 0.6))
    y = np.tanh(y * env * drive * 1.4)
    P('music', fade(y, 0.001, 0.02), t, gain_db, 0.0, 0.05)


def arpeggio(notes, t0, dur, step, gain_db, bright, pattern='updown', pan_w=0.3, accent=None):
    tones = sorted(n + 12 for n in notes)
    seq = tones + tones[-2:0:-1] if pattern == 'updown' else tones
    k = 0
    t = t0
    while t < t0 + dur - 1e-6:
        m = seq[k % len(seq)]
        g = gain_db + (3 if accent and k % accent == 0 else 0)
        pluck_note(m, t, dur=min(1.2, step * 6), gain_db=g, pan=pan_w * (1 if k % 2 else -1), bright=bright)
        k += 1
        t += step


# ================================================================== A. OPEN (0-10)

def open_section():
    # Hearth fire, then silenced by the frontier.
    fire_end = CUE['frontierNot']
    for ch, seed in ((-0.6, 3), (0.6, 4)):
        f = crackle(fire_end - 0.3 + 0.2, density=26, seed=seed)
        f *= np.minimum(1, seconds(len(f) / SR) / 1.2)
        f = fade(f, 0.01, 0.08)
        P('amb', f, 0.3, -14, ch, 0.15)
    # Warm, homely chord and a lullaby figure.
    P('music', pad([46, 50, 53, 57, 60], 4.3, cutoff=700, attack=1.6, release=0.4, detune=8, bright_end=1300), 0.8, -21, 0, 0.5)
    for t, m in ((1.25, 74), (1.875, 69), (2.5, 65), (3.125, 72), (3.75, 69), (4.375, 62)):
        pluck_note(m, t, 2.4, -19, pan=0.15 * np.sin(t * 3), bright=0.3, send=0.6, decay=0.998)
    # The drone that has been there all along.
    d = sine(float(midi(26)), 9.6) + 0.3 * lowpass(saw(float(midi(26)), 9.6), 220)
    d *= np.minimum(1, seconds(9.6) / 3.0)
    P('music', fade(d, 0.5, 0.4), 0.5, -24, 0, 0.1)

    # "The frontier does not." — braam, hit, and the fire cut dead.
    P('sfx', riser(0.9, 300, 5000), CUE['frontierNot'] - 0.9, -16, 0, 0.3)
    P('music', braam(38, 4.5, cutoff=2200), CUE['frontierNot'], -7, 0, 0.45)
    hit(CUE['frontierNot'], -7, sub=1.2)

    # Low pulse under the map, one beat at a time.
    t = CUE['mapRings'][0]
    while t < CUE['mapDive']:
        P('music', thump(0.6, 70, 36, 0.18), t, -13, 0, 0.1)
        P('sfx', tick(2600), t + BEAT / 2, -30, 0.2)
        t += BEAT
    # Each ring inks itself: a strike, a bell climbing the chord, the pen's swish.
    for i, (t, m) in enumerate(zip(CUE['mapRings'], (62, 65, 69, 72, 74))):
        pan = -0.4 + 0.2 * i
        P('music', bell(float(midi(m)), 3.0, decay=1.6), t, -15, pan, 0.6)
        P('sfx', taiko(1.0, 95, 48, 0.3, 0.4), t, -14, 0, 0.2)
        w = whoosh(0.5, 400, 2600, 0.6)
        P('sfx', w, t + 0.02, -24, pan + 0.2, 0.2)
    # Dive.
    dive_len = 10.0 - CUE['mapDive']
    P('sfx', riser(dive_len, 300, 7000), CUE['mapDive'], -11, 0, 0.3)
    P('sfx', whoosh(0.8, 300, 4000, 0.9), 10.0 - 0.72, -9, 0, 0.3)


# ================================================================== B. TERRAIN (10-35)

CHORDS = [
    (38, [50, 53, 57, 64]),      # Dm9      Hearth
    (34, [46, 50, 53, 57]),      # Bbmaj7
    (38, [50, 53, 57, 62]),      # Dm       Verge
    (34, [46, 50, 53, 58]),      # Bb
    (43, [50, 55, 58, 62]),      # Gm       Wildmarch
    (33, [49, 52, 57, 61]),      # A
    (34, [46, 50, 53, 58, 62]),  # Bb       Duskreach
    (36, [48, 52, 55, 60, 64]),  # C
    (38, [50, 53, 57, 62, 65]),  # Dm       Ashenfront
    (33, [49, 52, 55, 57, 61]),  # A7
]
RING_BELL = {1: 81, 2: 77, 3: 74, 4: 69}


def terrain_section():
    t0 = 10.0
    # Impact into the landscape.
    hit(t0, -10, sub=0.7, duck=4)
    P('music', bell(float(midi(86)), 3, 1.4), t0, -22, 0.3, 0.8)
    P('music', bell(float(midi(81)), 3, 1.4), t0 + 0.04, -22, -0.3, 0.8)

    # Wind rises with every ring; the Ashenfront adds a burning roar.
    for pan, seed in ((-0.7, 5), (0.7, 6)):
        w = wind(25.0, seed) * np.linspace(0.5, 1.3, int(25 * SR))
        P('amb', fade(w, 0.4, 0.01), t0, -21, pan, 0.1)
    roar = crackle(5.0, density=60, seed=9) + lowpass(brown(5.0), 160) * 0.8
    P('amb', fade(roar, 0.8, 0.01), 30.0, -18, 0, 0.1)

    for k, (root, notes) in enumerate(CHORDS):
        bt = t0 + k * BAR
        ring = k // 2
        cut = 700 + 3800 * (k / 9) ** 1.3
        P('music', pad(notes, BAR + 0.7, cutoff=cut, attack=0.9 if k == 0 else 0.25, release=0.7, detune=12 + k),
          bt, -16 + 0.5 * k, 0, 0.35)
        # Bass grows from a held root to a driving eighth-note line.
        if ring == 0:
            bass_note(root, bt, BAR, -15, cutoff=180)
        elif ring == 1:
            for b in range(4):
                bass_note(root, bt + b * BEAT, BEAT * 0.95, -14)
        else:
            for e in range(8):
                m = root + (12 if ring >= 3 and e % 4 == 3 else 0)
                bass_note(m, bt + e * BEAT / 2, BEAT / 2 * 0.9, -13 + (1 if e % 2 == 0 else 0), drive=1 + 0.4 * (ring - 2))
        # Plucked-string arpeggio: eighths at home, sixteenths further out.
        step = BEAT / 2 if ring < 2 else BEAT / 4
        arpeggio(notes, bt, BAR, step, gain_db=-23 + 1.2 * ring, bright=0.35 + 0.1 * ring, accent=4 if ring >= 3 else None)
        # Percussion enters at the Wildmarch.
        if ring == 2:
            for b in (0, 2):
                P('sfx', taiko(1.2, 105, 46, 0.4), bt + b * BEAT, -12, 0, 0.25)
        elif ring >= 3:
            pattern = [0, 1.5, 2, 3, 3.5] if ring == 3 else [0, 0.5, 1, 1.5, 2, 2.5, 3, 3.25, 3.5, 3.75]
            for b in pattern:
                acc = b in (0, 2)
                P('sfx', taiko(1.1, 115 if acc else 140, 50 if acc else 70, 0.35, 0.7), bt + b * BEAT,
                  -9 if acc else -14, 0.15 * np.sin(b * 3), 0.2)
            for s16 in range(16):
                sh = highpass(noise(0.06), 6000) * np.exp(-seconds(0.06) / 0.012)
                P('sfx', sh, bt + s16 * BEAT / 4, -27 + (4 if s16 % 4 == 2 else 0), 0.35, 0.05)
            if k % 2 == 0:
                P('music', braam(root, 2.4, cutoff=1400, drive=1.8), bt, -13, 0, 0.3)

    # Crossing a ring border: rush of air, a hit, and that ring's bell.
    for ring in TL['rings'][1:]:
        idx = TL['rings'].index(ring)
        tc = ring['start']
        P('sfx', riser(1.6, 400, 6000), tc - 1.6, -17, 0, 0.2)
        P('sfx', whoosh(1.1, 220, 3600, 0.88), tc - 0.97, -8, -0.2, 0.25)
        hit(tc, -10 + idx, sub=0.6 + 0.15 * idx, duck=5)
        P('music', bell(float(midi(RING_BELL[idx])), 3.5, 1.6), tc + 0.03, -16, 0.25, 0.7)
        # Skull stamps on the chapter card, one per danger level.
        for d in range(ring['danger']):
            ts = tc + 0.35 + 0.625 + d * 0.156
            P('sfx', clunk(seed=idx * 10 + d) * 0.8, ts, -17, -0.5, 0.15)

    # "Something out here watches back."
    P('sfx', growl(2.6, 44), CUE['eyes'] - 0.2, -17, 0.2, 0.4)
    for i in range(6):
        te = CUE['eyes'] + i * 0.22
        P('sfx', bell(float(midi(96 + (i % 2))), 0.6, 0.25), te, -30, -0.6 + 0.24 * i, 0.5)

    # Build to the cut: riser and a drum roll that ends on nothing.
    P('sfx', riser(2.0, 200, 9000), CUT - 2.0, -9, 0, 0.2)
    P('sfx', fade(shepard(2.5, rate=0.6, base=55) * np.linspace(0.2, 1, int(2.5 * SR)), 0.3, 0.005), CUT - 2.5, -19, 0, 0.2)
    t = CUT - BAR / 2
    step = BEAT / 4
    while t < CUT - 1e-3:
        k = (t - (CUT - BAR / 2)) / (BAR / 2)
        P('sfx', taiko(0.5, 190, 90, 0.12, 1.0), t, -18 + 10 * k, 0.2 * np.sin(t * 9), 0.15)
        t += step if k < 0.5 else step / 2


# ================================================================== C. GAUGE (35-45)

def gauge_section():
    steps = TL['heatSteps']
    end = 45.0
    PAN = -0.35  # the instrument sits on the left of frame
    # Heartbeat, accelerating with Heat.
    t = 35.25
    while t < end - 0.4:
        k = (t - 35.0) / 10.0
        P('sfx', heartbeat(0.9, 0.8 + 0.6 * k), t, -9 + 4 * k, 0, 0.15)
        t += 0.95 - 0.5 * k
    # Clock-work: a tick every eighth, a clunk and a drum on every Heat jump.
    t = steps[0][0]
    i = 0
    while t < steps[-1][0]:
        P('sfx', tick(3100 if i % 2 else 2500), t, -25, PAN, 0.05)
        t += BEAT / 2
        i += 1
    for i, (ts, v) in enumerate(steps):
        # Starts small and gets heavier with every jump.
        P('sfx', clunk(seed=i), ts, -15 + 0.55 * i, PAN, 0.2)
        P('music', taiko(0.9, 100, 45, 0.3, 0.3), ts, -20 + 0.75 * i, 0, 0.2)
    # Tension that only rises.
    sh = shepard(8.8, rate=0.18, base=36) * np.linspace(0.15, 1.0, int(8.8 * SR))
    P('music', fade(sh, 1.0, 0.05), 35.6, -16, 0, 0.3)
    dr = lowpass(saw(float(midi(26)), 9.7), 140) * (0.8 + 0.2 * np.sin(2 * np.pi * 2.4 * seconds(9.7)))
    P('music', fade(dr, 1.5, 0.3), 35.3, -16, 0, 0.1)
    # Thresholds: Restless, Hunted, Marked.
    for j, m in enumerate(TL['heatMessages']):
        P('music', braam(38 if j < 2 else 39, 2.2, cutoff=1200 + 500 * j), m['t'], -13 + 3 * j, 0, 0.4)
        P('music', bell(float(midi((69, 72, 74)[j])), 3.0, 1.4), m['t'], -17, PAN, 0.6)
        if j == 2:
            P('music', bell(float(midi(75)), 3.0, 1.4), m['t'] + 0.01, -19, -PAN, 0.6)
        DUCKS.append((m['t'], 3, 0.4))
    # MARKED: a two-tone alarm pulsing with the panel light.
    tm = TL['heatMessages'][2]['t']
    ad = end - tm - 0.2
    tt = seconds(ad)
    alarm = bandpass(saw(np.where((tt * 1.6) % 1 < 0.5, 587.0, 554.0), ad), 1200, q=2)
    alarm *= (0.5 + 0.5 * np.sin(2 * np.pi * 1.6 * tt)) * np.linspace(0.3, 1, len(tt))
    P('sfx', fade(alarm, 0.2, 0.1), tm, -27, PAN, 0.3)
    # 100: the glass breaks.
    tc = steps[-1][0]
    P('sfx', glass(1.4), tc, -5, 0, 0.4)
    hit(tc, -6, sub=1.2, duck=8)
    P('music', braam(38, 3.0, cutoff=2600, drive=2.6), tc, -8, 0, 0.5)
    P('sfx', sine(3400, 1.2) * np.sin(np.pi * seconds(1.2) / 1.2) ** 2, tc + 0.05, -33, 0, 0)
    P('sfx', riser(0.6, 300, 6000), end - 0.6, -12, 0, 0.2)


# ================================================================== D. CHAMPIONS (45-52.5)

def champions_section():
    ch = TL['champions']
    hunter = TL['hunter']['t']
    chords = [(38, [50, 53, 57, 62]), (34, [46, 50, 53, 58]), (43, [50, 55, 58, 62]), (33, [49, 52, 57, 61])]
    for c, (root, notes) in zip(ch, chords):
        t0 = c['t']
        P('music', pad(notes, 1.25 + 0.3, cutoff=2600, attack=0.05, release=0.3, detune=16), t0, -17, 0, 0.3)
        for e in range(4):
            bass_note(root, t0 + e * BEAT / 2, BEAT / 2 * 0.9, -12, drive=1.6)
        for b in (0, 0.5, 1.0, 1.5):
            P('sfx', taiko(1.0, 120, 50, 0.3, 0.7), t0 + b * BEAT, -10 if b == 0 else -15, 0, 0.2)
        hit(t0, -9, sub=0.8, duck=5)
        P('music', braam(root, 1.4, cutoff=1600), t0, -13, 0, 0.3)
        fx = c['fx']
        if fx == 'vile':
            P('sfx', hiss(1.2), t0 + 0.05, -14, 0, 0.3)
        elif fx == 'unseen':
            P('sfx', zap(0.2, 2600, 160), t0 + 0.50, -11, -0.35, 0.4)
            P('sfx', zap(0.2, 160, 2600), t0 + 0.555, -11, 0.0, 0.4)
            P('sfx', whoosh(0.3, 800, 5000, 0.5), t0 + 0.45, -17, -0.2, 0.2)
        elif fx == 'sunder':
            P('sfx', crumble(1.4), t0, -7, 0, 0.3)
            P('sfx', thump(0.6, 60, 30, 0.2), t0, -6, 0, 0.1)
        elif fx == 'warp':
            P('sfx', portal(1.25), t0, -9, 0, 0.5)
            P('sfx', fade(whoosh(1.1, 200, 2400, 0.5)[::-1].copy(), 0.05, 0.01), t0 + 0.1, -15, 0, 0.3)
    # The Hunter: the music drops to a pulse and something breathes.
    hit(hunter, -7, sub=1.4, duck=10)
    P('sfx', growl(2.3, 50), hunter + 0.08, -11, 0, 0.45)
    t = hunter + 0.2
    while t < 52.3:
        P('sfx', heartbeat(0.9, 1.3), t, -6, 0, 0.2)
        t += BEAT
    dr = lowpass(saw(float(midi(26)), 2.5), 110)
    P('music', fade(dr, 0.05, 0.3), hunter, -15, 0, 0.2)
    P('music', choir([38, 39, 45], 2.5, attack=0.6, release=0.4, vowel='o'), hunter, -20, 0, 0.5)


# ================================================================== E. BANK (52.5-57.5)

def bank_section():
    t0 = CUE['bankHome']
    for ch, seed in ((-0.6, 31), (0.6, 32)):
        f = crackle(5.0, density=22, seed=seed)
        f *= np.minimum(1, seconds(5.0) / 0.7)
        P('amb', fade(f, 0.01, 0.4), t0, -17, ch, 0.15)
    P('music', pad([50, 54, 57, 64], 2.8, cutoff=900, attack=0.6, release=0.6, detune=7, bright_end=1800), t0, -17, 0, 0.5)
    P('music', pad([43, 50, 55, 59, 62], 2.6, cutoff=1400, attack=0.3, release=0.6, detune=7, bright_end=2400), 55.0, -17, 0, 0.5)
    bass_note(38, t0, 2.5, -17, cutoff=160)
    bass_note(31, 55.0, 2.5, -17, cutoff=160)
    for i, m in enumerate((74, 78, 81, 76, 74, 78, 81, 83)):
        pluck_note(m, t0 + 0.1 + i * BEAT / 2, 2.0, -21, pan=0.25 * (1 if i % 2 else -1), bright=0.35, send=0.55, decay=0.998)
    # Ledger: a chime per line, coins while the total counts, a chord when it lands.
    for t, m in zip(CUE['bankLines'], (81, 85, 88)):
        P('music', bell(float(midi(m)), 2.0, 1.0), t, -17, 0.2, 0.5)
    b = TL['bank']
    l3, end = CUE['bankLines'][2], CUE['bankCountEnd']
    prev = None
    for s in range(400):
        tt = l3 + (end - l3) * s / 400
        k = 1 - (1 - s / 400) ** 3
        v = int((b['field'] + (b['banked'] - b['field']) * k) // 10)
        if prev is not None and v != prev:
            P('sfx', bell(float(midi(98 + (v % 3))), 0.25, 0.12), tt, -27, 0.3 * np.sin(v), 0.2)
        prev = v
    for m, p in ((86, -0.3), (90, 0.0), (93, 0.3)):
        P('music', bell(float(midi(m)), 3.0, 1.6), end, -15, p, 0.6)
    P('sfx', impact(2.0, sub=0.5, crack=0.3, body=0.4), end, -17, 0, 0.3)
    P('sfx', riser(0.9, 300, 8000), CUE['titleHit'] - 0.9, -10, 0, 0.3)


# ================================================================== F. TITLE (57.5-65)

def title_section():
    t0 = CUE['titleHit']
    P('music', braam(38, 5.5, cutoff=2600, drive=2.4), t0, -5, 0, 0.5)
    hit(t0, -5, sub=1.4, duck=6)
    P('music', bell(float(midi(74)), 4, 2.0), t0, -15, -0.2, 0.7)
    P('music', bell(float(midi(81)), 4, 2.0), t0 + 0.02, -16, 0.2, 0.7)
    P('music', choir([50, 54, 57, 62, 66], 6.8, attack=1.6, release=1.6), t0 + 0.2, -14, 0, 0.6)
    d = sine(float(midi(26)), 7.2) + 0.25 * lowpass(saw(float(midi(26)), 7.2), 150)
    P('music', fade(d, 0.05, 1.5), t0, -17, 0, 0.1)
    P('music', bell(float(midi(74)), 3.5, 1.8), CUE['tagline'], -19, 0, 0.7)
    for t, m in ((60.0, 74), (60.625, 69), (61.25, 66), (61.875, 73), (62.5, 69), (63.125, 62)):
        pluck_note(m, t, 2.6, -20, pan=0.2 * np.sin(t * 2), bright=0.3, send=0.7, decay=0.998)
    P('sfx', impact(3.0, sub=0.6, crack=0.0, body=0.3), CUE['codeLine'], -18, 0, 0.5)


# ================================================================== mixdown

def duck_env():
    g = np.zeros(N)
    t = np.arange(N) / SR
    for td, depth, rel in DUCKS:
        m = t >= td - 0.005
        dt = np.clip(t[m] - td, 0, None)
        g[m] = np.minimum(g[m], -depth * np.exp(-dt / rel))
    return db(g)


def render_layer(Ly, ir, duck):
    mL, mR = Ly.bus['music']
    mL, mR = mL * duck, mR * duck
    rL, rR = convolve_stereo(Ly.send[0], Ly.send[1], ir)
    L = mL + Ly.bus['sfx'][0] + Ly.bus['amb'][0] + rL * db(-4)
    R = mR + Ly.bus['sfx'][1] + Ly.bus['amb'][1] + rR * db(-4)
    return L, R


def main():
    print('composing…')
    open_section()
    terrain_section()
    gauge_section()
    champions_section()
    bank_section()
    title_section()

    print('mixing…')
    ir = reverb_ir(3.4)
    duck = duck_env()
    aL, aR = render_layer(A, ir, duck)
    bL, bR = render_layer(B, ir, duck)
    # Hard cut: everything from the first half stops dead at 35.000 s.
    t = np.arange(N) / SR
    gate = np.clip((CUT - t) / 0.004, 0, 1)
    L = aL * gate + bL
    R = aR * gate + bR
    # Fade the end.
    tail = np.clip((DUR - 0.05 - t) / (DUR - 0.05 - CUE['fadeOut']), 0, 1) ** 1.5
    L *= tail
    R *= tail

    print('mastering…')
    L, R = highpass(L, 24, 4), highpass(R, 24, 4)
    # Tone: tame the sub (trailer hits stack up), lift presence and air so
    # it still reads on laptop and phone speakers.
    def tone(x):
        x = x + lowpass(x, 110, 2) * (db(-5.0) - 1)
        x = x + bandpass(x, 2600, q=0.7) * (db(3.5) - 1)
        x = x + highpass(x, 9000, 2) * (db(2.5) - 1)
        return x
    L, R = tone(L), tone(R)
    L, R = glue(L, R, threshold_db=-20, ratio=2.0)
    loud = lufs(L, R)
    g = db(-14.0 - loud)
    L, R = L * g, R * g
    L, R = limiter(L, R, ceiling_db=-1.0)
    print(f'  integrated loudness before gain {loud:.1f} LUFS -> {lufs(L, R):.1f} LUFS, peak {20 * np.log10(np.max(np.abs([L, R]))):.2f} dBFS')

    os.makedirs(os.path.join(ROOT, 'out'), exist_ok=True)
    path = os.path.join(ROOT, 'out', 'audio.wav')
    wavfile.write(path, SR, np.stack([L, R], axis=1).astype(np.float32))
    print('wrote', path)


if __name__ == '__main__':
    main()

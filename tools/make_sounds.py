#!/usr/bin/env python3
"""Synthesizes every Distant Frontiers sound effect from scratch.

No samples, no recordings: oscillators, noise, filters, envelopes and a
convolution reverb. Output is mono 44.1 kHz OGG Vorbis (Minecraft needs mono
for positional audio) into resourcepack/assets/distantfrontiers/sounds/.

Usage (needs numpy, scipy, soundfile):
    python3 tools/make_sounds.py [--preview preview.png]
"""
import argparse
import os

import numpy as np
import soundfile as sf
from scipy import signal

SR = 44100
OUT = "resourcepack/assets/distantfrontiers/sounds"


# ----------------------------------------------------------------------------- building blocks

def n_samples(dur):
    return int(round(SR * dur))


def time(dur):
    return np.arange(n_samples(dur)) / SR


def glide(f0, f1, dur, curve="exp"):
    """Per-sample frequency moving from f0 to f1."""
    x = np.linspace(0, 1, n_samples(dur))
    if curve == "exp":
        return f0 * (f1 / f0) ** x
    return f0 + (f1 - f0) * x


def osc(freq, dur, shape="sine", phase=0.0):
    """Phase-accumulating oscillator; freq may be a scalar or per-sample array."""
    n = n_samples(dur)
    f = np.broadcast_to(np.asarray(freq, dtype=float), (n,))
    ph = phase + 2 * np.pi * np.cumsum(f) / SR
    if shape == "sine":
        return np.sin(ph)
    if shape == "saw":
        return 2 * ((ph / (2 * np.pi)) % 1.0) - 1
    if shape == "square":
        return np.sign(np.sin(ph))
    if shape == "tri":
        return 2 * np.abs(2 * ((ph / (2 * np.pi)) % 1.0) - 1) - 1
    raise ValueError(shape)


def noise(dur, seed):
    return np.random.default_rng(seed).uniform(-1, 1, n_samples(dur))


def decay(dur, tau):
    return np.exp(-time(dur) / tau)


def adsr(dur, a, d, s, r):
    n = n_samples(dur)
    na, nd, nr = n_samples(a), n_samples(d), n_samples(r)
    ns = max(0, n - na - nd - nr)
    e = np.concatenate([
        np.linspace(0, 1, na, endpoint=False),
        np.linspace(1, s, nd, endpoint=False),
        np.full(ns, s),
        np.linspace(s, 0, nr),
    ])
    return np.pad(e, (0, max(0, n - len(e))))[:n]


def lowpass(x, cutoff, order=2):
    sos = signal.butter(order, min(cutoff, SR / 2 * 0.99), "low", fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def highpass(x, cutoff, order=2):
    sos = signal.butter(order, cutoff, "high", fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def bandpass(x, lo, hi, order=2):
    sos = signal.butter(order, [lo, min(hi, SR / 2 * 0.99)], "band", fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def sweep_lowpass(x, c0, c1, blocks=64):
    """Time-varying low-pass: filter in blocks, carrying filter state across them."""
    out = np.zeros_like(x)
    edges = np.linspace(0, len(x), blocks + 1).astype(int)
    zi = None
    for i in range(blocks):
        frac = i / max(1, blocks - 1)
        cutoff = c0 * (c1 / c0) ** frac
        sos = signal.butter(2, min(cutoff, SR / 2 * 0.99), "low", fs=SR, output="sos")
        if zi is None:
            zi = np.zeros((sos.shape[0], 2))
        seg, zi = signal.sosfilt(sos, x[edges[i]:edges[i + 1]], zi=zi)
        out[edges[i]:edges[i + 1]] = seg
    return out


def sweep_bandpass(x, c0, c1, q=4.0, blocks=64):
    out = np.zeros_like(x)
    edges = np.linspace(0, len(x), blocks + 1).astype(int)
    zi = None
    for i in range(blocks):
        frac = i / max(1, blocks - 1)
        c = c0 * (c1 / c0) ** frac
        lo, hi = c / (1 + 1 / q), min(c * (1 + 1 / q), SR / 2 * 0.99)
        sos = signal.butter(1, [lo, hi], "band", fs=SR, output="sos")
        if zi is None:
            zi = np.zeros((sos.shape[0], 2))
        seg, zi = signal.sosfilt(sos, x[edges[i]:edges[i + 1]], zi=zi)
        out[edges[i]:edges[i + 1]] = seg
    return out


def reverb(x, seconds=1.2, mix=0.25, seed=7, damp=4000):
    """Convolution with decaying, darkened noise: a cheap, smooth hall."""
    ir = noise(seconds, seed) * np.exp(-time(seconds) / (seconds / 5))
    ir = lowpass(ir, damp)
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
    wet = signal.fftconvolve(np.concatenate([x, np.zeros(n_samples(seconds))]), ir)[: len(x) + n_samples(seconds)]
    dry = np.concatenate([x, np.zeros(n_samples(seconds))])
    return dry * (1 - mix) + wet * mix * 3


def place(total, part, at):
    """Mix `part` into `total` starting at `at` seconds (growing total if needed)."""
    start = n_samples(at)
    end = start + len(part)
    if end > len(total):
        total = np.pad(total, (0, end - len(total)))
    total[start:end] += part
    return total


def drive(x, amount):
    return np.tanh(x * amount) / np.tanh(amount)


def finish(x, peak=0.85, fade_ms=8):
    x = x - np.mean(x)
    fade = n_samples(fade_ms / 1000)
    x[:fade] *= np.linspace(0, 1, fade)
    x[-fade * 4:] *= np.linspace(1, 0, fade * 4)
    # Trim trailing near-silence so files stay small.
    loud = np.where(np.abs(x) > 1e-3 * np.max(np.abs(x)))[0]
    if len(loud):
        x = x[: min(len(x), loud[-1] + n_samples(0.05))]
    return x / (np.max(np.abs(x)) + 1e-9) * peak


def note(name):
    names = {"C": -9, "C#": -8, "D": -7, "D#": -6, "E": -5, "F": -4, "F#": -3,
             "G": -2, "G#": -1, "A": 0, "A#": 1, "B": 2}
    pitch, octave = name[:-1], int(name[-1])
    return 440.0 * 2 ** ((names[pitch] + 12 * (octave - 4)) / 12)


def pluck(freq, dur, tau=0.25, bright=0.3):
    return (osc(freq, dur) + bright * osc(freq * 2, dur) + bright * 0.4 * osc(freq * 3, dur)) * decay(dur, tau)


def bell(freq, dur, partials=((0.5, 0.6, 2.0), (1.0, 1.0, 1.6), (1.19, 0.5, 1.0), (1.56, 0.45, 0.8),
                              (2.0, 0.4, 0.7), (2.52, 0.3, 0.5), (3.01, 0.22, 0.35), (4.17, 0.15, 0.25))):
    out = np.zeros(n_samples(dur))
    for ratio, amp, tau in partials:
        out += amp * osc(freq * ratio, dur) * decay(dur, tau)
    return out


# ----------------------------------------------------------------------------- the sounds

def ring_deeper():
    """Crossing outward: a low, ominous bell toll over a swelling drone."""
    d = 3.0
    toll = bell(note("G2"), d)
    strike = lowpass(noise(0.05, 1), 1500) * decay(0.05, 0.01) * 0.6
    drone = (osc(note("G1"), d, "saw") + osc(note("G1") * 1.005, d, "saw")) * 0.15
    drone = lowpass(drone, 300) * adsr(d, 0.8, 0.5, 0.6, 1.4)
    x = place(toll + drone, strike, 0)
    return finish(reverb(x, 1.8, 0.35))


def ring_home():
    """Crossing into the Hearth: a warm, rising chime."""
    x = np.zeros(1)
    for i, n in enumerate(["C5", "E5", "G5", "C6"]):
        x = place(x, pluck(note(n), 1.2, tau=0.45, bright=0.15) * (1 - 0.1 * i), 0.09 * i)
    return finish(reverb(x, 1.4, 0.3))


def hex_curse():
    """Becoming hexed: a dissonant swell that slams into a dark hit."""
    d = 1.3
    cluster = sum(osc(note(n) * glide(1.0, 1.06, d), d, "saw") for n in ["B2", "F3", "C4", "F#4"]) * 0.18
    cluster = sweep_lowpass(cluster, 300, 4000) * np.linspace(0, 1, n_samples(d)) ** 2
    rush = sweep_bandpass(noise(d, 2), 300, 6000, q=2) * np.linspace(0, 1, n_samples(d)) ** 3 * 0.8
    swell = cluster + rush
    hit_d = 1.6
    hit = (osc(glide(90, 40, hit_d), hit_d) * decay(hit_d, 0.35) * 1.2
           + drive(sum(osc(note(n), hit_d, "saw") for n in ["B1", "F2", "C3"]) * 0.3, 3)
           * decay(hit_d, 0.5) * 0.6)
    hit = lowpass(hit, 2500)
    x = place(swell, hit, d)
    return finish(reverb(x, 2.0, 0.35))


def hex_pass():
    """Tag, you're it: a slap and a zapping chirp."""
    slap = bandpass(noise(0.06, 3), 800, 5000) * decay(0.06, 0.012)
    d = 0.35
    mod = osc(glide(600, 90, d), d) * 6
    chirp = np.sin(2 * np.pi * np.cumsum(glide(1600, 180, d) * (1 + 0.02 * mod)) / SR) * decay(d, 0.12)
    zing = osc(glide(900, 2400, 0.18), 0.18, "tri") * decay(0.18, 0.05) * 0.4
    x = place(slap * 1.2, chirp, 0.01)
    x = place(x, zing, 0.12)
    return finish(reverb(x, 0.6, 0.2))


def hex_ambush():
    """Something answers the Hex: a far-off war horn, two falling notes."""
    def horn(freq, dur):
        vib = 1 + 0.006 * np.sin(2 * np.pi * 5.5 * time(dur))
        tone = osc(freq * vib, dur, "saw") + 0.5 * osc(freq * 2 * vib, dur, "saw")
        return lowpass(tone, 900) * adsr(dur, 0.12, 0.2, 0.75, 0.3)
    x = place(horn(note("A2"), 0.7), horn(note("F2"), 1.0), 0.62)
    return finish(reverb(lowpass(x, 1800), 2.2, 0.45, damp=2500))


def hex_survive():
    """Outlasting the Hex: a short brass fanfare."""
    def brass(freq, dur):
        tone = osc(freq, dur, "saw") + 0.3 * osc(freq * 1.003, dur, "saw")
        return sweep_lowpass(tone, 900, 3000) * adsr(dur, 0.03, 0.1, 0.7, 0.15)
    x = np.zeros(1)
    for at, n, dur in [(0.0, "C4", 0.14), (0.15, "E4", 0.14), (0.30, "G4", 0.14)]:
        x = place(x, brass(note(n), dur), at)
    for n in ["C4", "G4", "C5", "E5"]:
        x = place(x, brass(note(n), 0.9) * 0.7, 0.45)
    return finish(reverb(x, 1.6, 0.3))


def downed_fall():
    """Going down: a heavy body thud, then a slow heartbeat."""
    d = 0.5
    thud = osc(glide(110, 38, d), d) * decay(d, 0.12) + lowpass(noise(d, 4), 400) * decay(d, 0.05) * 0.8
    def beat(strength):
        b = osc(glide(70, 45, 0.18), 0.18) * decay(0.18, 0.05) * strength
        return lowpass(b, 300)
    x = place(thud * 1.2, beat(1.0), 0.55)
    x = place(x, beat(0.7), 0.72)
    x = place(x, beat(0.9), 1.25)
    x = place(x, beat(0.6), 1.42)
    return finish(reverb(x, 0.9, 0.2, damp=1500))


def downed_revive():
    """Pulled back up: a rising sparkle and an upward whoosh."""
    x = np.zeros(1)
    for i, n in enumerate(["C5", "E5", "G5", "C6", "E6", "G6"]):
        x = place(x, pluck(note(n), 0.6, tau=0.2, bright=0.25) * 0.7, 0.06 * i)
    whoosh = sweep_bandpass(noise(0.6, 5), 400, 5000, q=3) * adsr(0.6, 0.3, 0.1, 0.6, 0.2) * 0.6
    x = place(x, whoosh, 0.0)
    x = place(x, sum(osc(note(n), 0.8) for n in ["C5", "E5", "G5"]) * decay(0.8, 0.3) * 0.3, 0.36)
    return finish(reverb(x, 1.2, 0.3))


def warper_warp():
    """Space folds: a descending 'vworp' with a phasing shimmer."""
    d = 0.8
    f = glide(1100, 110, d)
    tone = osc(f, d, "saw") * 0.5 + osc(f * 1.5, d, "sine") * 0.4
    trem = 0.6 + 0.4 * np.sin(2 * np.pi * glide(30, 6, d, "lin") * time(d))
    tone = sweep_lowpass(tone * trem, 5000, 600) * adsr(d, 0.01, 0.2, 0.7, 0.4)
    shimmer = sweep_bandpass(noise(d, 6), 7000, 500, q=6) * decay(d, 0.3) * 0.5
    comb = tone + np.roll(tone, n_samples(0.004)) * 0.6  # cheap phaser-ish comb
    return finish(reverb(comb + shimmer, 1.0, 0.3))


def thief_steal():
    """Snatched: a quick swish, two bright plinks and a nasty little giggle."""
    swish = sweep_bandpass(noise(0.16, 7), 1500, 7000, q=3) * adsr(0.16, 0.04, 0.05, 0.5, 0.07)
    x = place(swish, pluck(note("E6"), 0.25, tau=0.06, bright=0.2) * 0.6, 0.08)
    x = place(x, pluck(note("B6"), 0.25, tau=0.06, bright=0.2) * 0.6, 0.15)
    for i, f in enumerate([1300, 1150, 1000]):
        blip = osc(glide(f, f * 0.8, 0.06), 0.06, "tri") * adsr(0.06, 0.005, 0.02, 0.5, 0.02) * 0.45
        x = place(x, blip, 0.32 + i * 0.08)
    return finish(reverb(x, 0.5, 0.15))


def magnetic_charge():
    """Wind-up: a rising electric hum that tightens before the pull."""
    d = 1.5
    f = glide(70, 150, d)
    hum = osc(f, d, "saw") + 0.6 * osc(f * 3.01, d, "saw")
    rate = glide(4, 22, d)
    trem = 0.55 + 0.45 * np.sin(2 * np.pi * np.cumsum(rate) / SR)
    hum = sweep_lowpass(hum * trem, 200, 3500) * np.linspace(0.1, 1, n_samples(d)) ** 1.5
    crackle = highpass(noise(d, 8), 4000) * (np.random.default_rng(9).random(n_samples(d)) > 0.995) * 0.6
    return finish(hum + crackle)


def magnetic_pulse():
    """The yank: a deep, room-shaking whoomp."""
    d = 0.9
    boom = osc(glide(150, 32, d), d) * decay(d, 0.22)
    air = lowpass(noise(d, 10), 700) * decay(d, 0.08) * 0.9
    x = drive(boom * 1.3 + air, 2.0)
    return finish(reverb(x, 1.2, 0.25, damp=2000))


def volatile_fuse():
    """Ticking down: fizzing sparks and beeps that speed up until the end."""
    d = 1.5
    fizz = highpass(noise(d, 11), 3000) * 0.25
    pops = (np.random.default_rng(12).random(n_samples(d)) > 0.997) * np.random.default_rng(13).uniform(0.3, 1, n_samples(d))
    fizz += lowpass(pops, 6000) * 0.8
    x = fizz * np.linspace(0.6, 1, n_samples(d))
    at, gap = 0.0, 0.26
    while at < d - 0.05:
        x = place(x, osc(1900, 0.045, "square") * adsr(0.045, 0.002, 0.01, 0.6, 0.01) * 0.35, at)
        at += gap
        gap = max(0.045, gap * 0.8)
    return finish(x[: n_samples(d)])


def champion_slain():
    """A champion falls: a gong strike blooming into a major chord."""
    d = 2.4
    gong = bell(note("D3"), d, partials=((1.0, 1.0, 1.4), (1.48, 0.6, 1.1), (2.03, 0.5, 0.9),
                                        (2.74, 0.35, 0.7), (3.53, 0.25, 0.5), (4.41, 0.18, 0.35)))
    chord = sum(osc(note(n), d, "saw") + 0.3 * osc(note(n) * 1.004, d, "saw") for n in ["D4", "A4", "D5", "F#5"])
    chord = sweep_lowpass(chord * 0.12, 600, 3500) * adsr(d, 0.35, 0.3, 0.6, 1.0)
    return finish(reverb(gong + chord, 2.2, 0.35))


def warded_deflect():
    """A hit glances off: a hard metallic ting."""
    d = 0.5
    ting = sum(a * osc(f, d) * decay(d, t) for f, a, t in [(2400, 1.0, 0.12), (3720, 0.6, 0.08), (5130, 0.4, 0.05), (1250, 0.5, 0.15)])
    click = highpass(noise(0.02, 14), 2000) * decay(0.02, 0.004)
    return finish(reverb(place(ting, click, 0), 0.5, 0.15))


SOUNDS = {
    "ring/deeper": ring_deeper,
    "ring/home": ring_home,
    "hex/curse": hex_curse,
    "hex/pass": hex_pass,
    "hex/ambush": hex_ambush,
    "hex/survive": hex_survive,
    "downed/fall": downed_fall,
    "downed/revive": downed_revive,
    "warper/warp": warper_warp,
    "thief/steal": thief_steal,
    "magnetic/charge": magnetic_charge,
    "magnetic/pulse": magnetic_pulse,
    "volatile/fuse": volatile_fuse,
    "elite/champion_slain": champion_slain,
    "warded/deflect": warded_deflect,
}


def preview(rendered, path):
    """Waveform + spectrogram strip per sound, for eyeballing without speakers."""
    from PIL import Image, ImageDraw
    rows, w, h = len(rendered), 900, 110
    img = Image.new("RGB", (w, rows * h), (14, 14, 20))
    draw = ImageDraw.Draw(img)
    for r, (name, x) in enumerate(rendered.items()):
        top = r * h
        f, t, z = signal.spectrogram(x, SR, nperseg=1024, noverlap=768)
        z = 10 * np.log10(z[f < 8000] + 1e-12)
        z = np.clip((z - z.max() + 70) / 70, 0, 1)[::-1]
        spec = Image.fromarray((z * 255).astype(np.uint8)).resize((w - 330, h - 10))
        spec = Image.merge("RGB", (spec, spec.point(lambda v: v * 0.6), spec.point(lambda v: 255 - v // 2 if v else 0)))
        img.paste(spec, (320, top + 5))
        step = max(1, len(x) // 300)
        mid = top + h // 2
        for i in range(300):
            seg = x[i * step:(i + 1) * step]
            if len(seg):
                draw.line([(10 + i, mid - seg.max() * 40), (10 + i, mid - seg.min() * 40)], fill=(120, 200, 255))
        draw.text((12, top + 4), f"{name}  {len(x) / SR:.2f}s", fill=(255, 255, 255))
    img.save(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", help="write a waveform/spectrogram sheet here")
    args = parser.parse_args()
    rendered = {}
    for name, make in SOUNDS.items():
        x = make().astype(np.float32)
        path = os.path.join(OUT, name + ".ogg")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        sf.write(path, x, SR, format="OGG", subtype="VORBIS")
        rendered[name] = x
        print(f"{name:24s} {len(x) / SR:5.2f}s  {os.path.getsize(path) / 1024:6.1f} KB")
    if args.preview:
        preview(rendered, args.preview)
        print("preview:", args.preview)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Synthesizes the optional pack's few sounds from scratch.

Most of the mod uses vanilla sounds on purpose. These are the handful that
vanilla has no good match for, kept soft and short so they sit in the
background like Minecraft's own: sines and filtered noise only, low peaks,
small rooms. Output is mono 44.1 kHz OGG Vorbis (Minecraft needs mono for
positional audio) into resourcepack/assets/furtherout/sounds/.

Usage (needs numpy, scipy, soundfile):
    python3 tools/make_sounds.py [--preview preview.png]
"""
import argparse
import os

import numpy as np
import soundfile as sf
from scipy import signal

SR = 44100
OUT = "resourcepack/assets/furtherout/sounds"


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

def soft_tone(freq, dur, attack, release, warmth=0.15):
    """A rounded sine with a touch of its octave, slow in and out."""
    tone = osc(freq, dur) + warmth * osc(freq * 2, dur) + warmth * 0.3 * osc(freq * 3, dur)
    return tone * adsr(dur, attack, 0.1, 0.8, release)


def air(dur, lo, hi, seed, shape):
    """Filtered breath of noise with an envelope."""
    return bandpass(noise(dur, seed), lo, hi) * shape


def danger_up():
    """Further from spawn: a low, quiet swell that sinks away. Barely there."""
    d = 2.2
    pad = soft_tone(note("D2"), d, 0.6, 1.2) + 0.6 * soft_tone(note("F2"), d, 0.8, 1.2)
    pad = sweep_lowpass(pad, 900, 250)
    wind = sweep_bandpass(noise(d, 21), 700, 220, q=1.5) * adsr(d, 0.7, 0.4, 0.5, 1.0) * 0.5
    return finish(reverb(pad + wind, 1.6, 0.3, damp=1800), peak=0.55)


def danger_down():
    """Back toward spawn: two warm, muted notes rising a fifth."""
    x = soft_tone(note("G3"), 1.6, 0.05, 1.2, warmth=0.1) * decay(1.6, 0.6)
    x = place(x, soft_tone(note("D4"), 1.6, 0.05, 1.2, warmth=0.1) * decay(1.6, 0.6) * 0.8, 0.22)
    return finish(reverb(lowpass(x, 1800), 1.4, 0.3, damp=2500), peak=0.5)


def marked_gain():
    """Being marked: a low muffled toll with a slightly sour overtone."""
    d = 2.0
    toll = bell(note("C3"), d, partials=((1.0, 1.0, 0.9), (1.41, 0.35, 0.6), (2.0, 0.3, 0.5), (2.83, 0.15, 0.3)))
    under = soft_tone(note("C2"), d, 0.3, 1.2) * 0.5
    x = lowpass(toll + under, 1400)
    return finish(reverb(x, 1.8, 0.35, damp=1500), peak=0.6)


def marked_pass():
    """Passing the mark: a short soft rush from one player to the other."""
    d = 0.6
    rush = sweep_bandpass(noise(d, 22), 400, 2400, q=2.5) * adsr(d, 0.15, 0.1, 0.6, 0.3)
    tone = soft_tone(glide(note("C3"), note("G3"), d), d, 0.1, 0.3) * 0.4
    return finish(reverb(rush + tone, 0.8, 0.25, damp=2500), peak=0.55)


def marked_end():
    """The mark fades: a falling breath and a gentle low settle."""
    d = 1.4
    breath = sweep_bandpass(noise(d, 23), 2000, 300, q=2) * adsr(d, 0.05, 0.3, 0.4, 0.9) * 0.8
    settle = soft_tone(note("F2"), d, 0.2, 1.0) * 0.5
    return finish(reverb(breath + settle, 1.2, 0.3, damp=2000), peak=0.5)


def downed_heartbeat():
    """While down: one slow lub-dub, felt more than heard."""
    def beat(strength):
        b = osc(glide(62, 38, 0.16), 0.16) * decay(0.16, 0.045) * strength
        return lowpass(b, 220)
    x = place(beat(1.0), beat(0.65), 0.2)
    return finish(np.pad(x, (0, n_samples(0.1))), peak=0.7, fade_ms=4)


def downed_revive():
    """Helped up: a soft breath in and a warm note that opens up."""
    d = 1.1
    breath = sweep_bandpass(noise(d, 24), 300, 1800, q=2) * adsr(d, 0.4, 0.1, 0.5, 0.5) * 0.6
    lift = soft_tone(note("A3"), d, 0.25, 0.7) * 0.5 + soft_tone(note("E4"), d, 0.35, 0.7) * 0.35
    return finish(reverb(breath + lowpass(lift, 2000), 1.0, 0.25, damp=2500), peak=0.55)


SOUNDS = {
    "danger/up": danger_up,
    "danger/down": danger_down,
    "marked/gain": marked_gain,
    "marked/pass": marked_pass,
    "marked/end": marked_end,
    "downed/heartbeat": downed_heartbeat,
    "downed/revive": downed_revive,
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

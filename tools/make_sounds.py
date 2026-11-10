"""Builds the sound library from the recipes in tools/sounds/.

    python tools/make_sounds.py                              writes everything below
    python tools/make_sounds.py --inspect fire.meteor_strike prints one sound's loudness over time, writes nothing

Needs the packages pinned in tools/requirements-sounds.txt. Writes:
    src/main/resources/assets/minecraftfx/sounds/CATEGORY/NAME.ogg   one mono 44.1 kHz Ogg Vorbis file per recipe
    src/main/resources/assets/minecraftfx/sounds.json                  sound events "CATEGORY.NAME"
    src/main/resources/assets/minecraftfx/lang/en_us.json              the "subtitles.minecraftfx.*" keys only
    docs/SOUND_LIBRARY.md                                              the catalogue table

The recipe format is documented in docs/SOUNDS.md.
"""
import json
import sys
import zlib
from pathlib import Path

import numpy as np
import soundfile

SR = 44100
ROOT = Path(__file__).resolve().parent.parent
RECIPES = ROOT / "tools" / "sounds"
ASSETS = ROOT / "src" / "main" / "resources" / "assets" / "minecraftfx"
MOD_ID = "minecraftfx"

MAX_SECONDS = 3.5
DEFAULT_RANGE = 24
# Loudness: the loudest 50 ms of every sound is brought to this RMS, unless that would clip.
TARGET_RMS = 0.26
PEAK_LIMIT = 0.95

SOUND_KEYS = {"about", "layers", "range", "echo", "crush", "rate", "level"}
LAYER_KEYS = {"use", "wave", "freq", "glide", "duty", "fm", "at", "dur", "vol", "attack", "shape", "power", "release",
              "vib", "trem", "low", "high", "band", "repeat", "every", "step", "notes", "scatter", "fade", "jitter"}
WAVES = {"sine", "triangle", "square", "saw", "noise", "hiss"}
SHAPES = {"decay", "hold", "swell", "bump"}


def sweep(spec, u, geometric):
    """A number, or [from, to] travelled over the note: geometrically for pitches, linearly otherwise."""
    if isinstance(spec, (int, float)):
        return np.full(u.shape, float(spec))
    start, end = spec
    return start * (end / start) ** u if geometric else start + (end - start) * u


def lowpass(x, cutoff):
    """Two one-pole filters in a row (12 dB per octave). The cutoff may change every sample, so this is a plain loop."""
    a = (1 - np.exp(-2 * np.pi * np.clip(cutoff, 20, SR * 0.45) / SR)).tolist()
    samples = x.tolist()
    out = [0.0] * len(samples)
    y1 = y2 = 0.0
    for i, sample in enumerate(samples):
        y1 += a[i] * (sample - y1)
        y2 += a[i] * (y1 - y2)
        out[i] = y2
    return np.array(out)


def highpass(x, cutoff):
    return x - lowpass(x, cutoff)


def note(layer, pitch, rng):
    """One note of a layer, with its envelope, at unit volume."""
    n = max(1, int(layer["dur"] * SR))
    index = np.arange(n)
    t = index / SR
    u = index / n
    freq = sweep(layer.get("freq", 440), u ** layer.get("glide", 1), True) * pitch
    if "vib" in layer:
        rate, semitones = layer["vib"]
        freq = freq * 2 ** (semitones / 12 * np.sin(2 * np.pi * rate * t))
    phase = np.cumsum(freq) / SR

    wave = layer["wave"]
    if wave == "sine":
        modulation = 0
        if "fm" in layer:
            ratio, depth = layer["fm"]
            modulation = depth * (1 - u) * np.sin(2 * np.pi * phase * ratio)
        x = np.sin(2 * np.pi * phase + modulation)
    elif wave == "triangle":
        x = 2 * np.abs(2 * (phase % 1) - 1) - 1
    elif wave == "square":
        x = np.where(phase % 1 < sweep(layer.get("duty", 0.5), u, False), 1.0, -1.0)
    elif wave == "saw":
        x = 2 * (phase % 1) - 1
    elif wave == "noise":
        # A new random level 'freq' times a second: low rates rumble, high rates hiss. The NES noise channel.
        x = rng.uniform(-1, 1, int(phase[-1]) + 2)[phase.astype(int)]
    else:
        x = rng.uniform(-1, 1, n)

    if "low" in layer:
        x = lowpass(x, sweep(layer["low"], u, True))
    if "high" in layer:
        x = highpass(x, sweep(layer["high"], u, True))
    if "band" in layer:
        centre = sweep(layer["band"], u, True)
        x = highpass(lowpass(x, centre * 1.5), centre / 1.5)
    peak = np.max(np.abs(x))
    if peak > 0:
        x = x / peak  # filters change the level; 'vol' should mean the same thing for every layer

    power = layer.get("power", 2.0)
    shape = layer.get("shape", "decay")
    until_end = (n - index) / SR
    if shape == "decay":
        envelope = (1 - u) ** power
    elif shape == "hold":
        envelope = np.minimum(1, until_end / layer.get("release", 0.02))
    elif shape == "swell":
        envelope = u ** power * np.minimum(1, until_end / 0.01)
    else:
        envelope = np.sin(np.pi * u) ** power
    envelope = envelope * np.minimum(1, t / layer.get("attack", 0.004))
    if "trem" in layer:
        rate, depth = layer["trem"]
        envelope = envelope * (1 - depth * (0.5 + 0.5 * np.sin(2 * np.pi * rate * t)))
    return x * envelope


def check(condition, where, message):
    if not condition:
        raise SystemExit(f"{where}: {message}")


def resolve(layer, voices, where):
    """Fills a layer in from the voice it uses, and rejects anything the renderer would ignore."""
    check(not set(layer) - LAYER_KEYS, where, f"unknown layer keys {sorted(set(layer) - LAYER_KEYS)}")
    if "use" in layer:
        check(layer["use"] in voices, where, f"unknown voice '{layer['use']}'")
        layer = {**voices[layer["use"]], **layer}
        del layer["use"]
    check(layer.get("wave") in WAVES, where, f"'wave' must be one of {sorted(WAVES)}")
    check("dur" in layer and layer["dur"] > 0, where, "'dur' is required and must be positive")
    check(layer.get("shape", "decay") in SHAPES, where, f"'shape' must be one of {sorted(SHAPES)}")
    check("fm" not in layer or layer["wave"] == "sine", where, "'fm' only applies to wave 'sine'")
    check("duty" not in layer or layer["wave"] == "square", where, "'duty' only applies to wave 'square'")
    check("release" not in layer or layer.get("shape") == "hold", where, "'release' only applies to shape 'hold'")
    check("notes" not in layer or "repeat" not in layer, where, "'notes' sets the repeat count; drop 'repeat'")
    return layer


def render(recipe, voices, name):
    """The finished mono samples of one sound, between -1 and 1."""
    check(not set(recipe) - SOUND_KEYS, name, f"unknown keys {sorted(set(recipe) - SOUND_KEYS)}")
    check("about" in recipe and recipe.get("layers"), name, "'about' and at least one layer are required")
    rng = np.random.default_rng(zlib.crc32(name.encode()))  # the same recipe always gives the same samples
    mix = np.zeros(int(MAX_SECONDS * SR))
    for number, raw in enumerate(recipe["layers"]):
        layer = resolve(raw, voices, f"{name} layer {number + 1}")
        notes = layer.get("notes", [0] * layer.get("repeat", 1))
        every = layer.get("every", layer["dur"])
        for k, semitones in enumerate(notes):
            semitones += rng.uniform(-1, 1) * layer.get("scatter", 0)
            pitch = 2 ** (semitones / 12) * layer.get("step", 1) ** k
            at = layer.get("at", 0) + k * every + rng.uniform(-0.5, 0.5) * layer.get("jitter", 0) * every
            start = int(max(0.0, at) * SR)
            samples = note(layer, pitch, rng) * layer.get("vol", 1) * layer.get("fade", 1) ** k
            samples = samples[:max(0, len(mix) - start)]
            mix[start:start + len(samples)] += samples

    if "echo" in recipe:
        delay, feedback = recipe["echo"]
        dry = mix.copy()
        for tap in range(1, 6):
            shift = int(tap * delay * SR)
            if shift < len(mix):
                mix[shift:] += dry[:len(mix) - shift] * feedback ** tap
    if "rate" in recipe:
        hold = max(1, round(SR / recipe["rate"]))
        mix = mix[np.arange(len(mix)) // hold * hold]
    if "crush" in recipe:
        levels = 2 ** (recipe["crush"] - 1)
        mix = np.round(mix * levels) / levels

    audible = np.nonzero(np.abs(mix) > 1e-4)[0]
    check(len(audible) > 0, name, "the sound is silent")
    mix = mix[:audible[-1] + 1]
    fade = min(len(mix), int(0.01 * SR))
    mix[-fade:] *= np.linspace(1, 0, fade)  # never end on a click

    window = int(0.05 * SR)
    energy = np.convolve(mix ** 2, np.ones(window) / window, mode="full")
    loudest = np.sqrt(np.max(energy))
    gain = min(PEAK_LIMIT / np.max(np.abs(mix)), TARGET_RMS / loudest)
    return mix * gain * recipe.get("level", 1.0)


def fingerprint(samples):
    """Length, then loudness and brightness in eight slices: enough to tell whether two sounds are near-copies."""
    features = [np.log2(len(samples) / SR) * 2]
    for piece in np.array_split(samples, 8):
        spectrum = np.abs(np.fft.rfft(piece))
        freqs = np.fft.rfftfreq(len(piece), 1 / SR)
        total = spectrum.sum()
        features.append(np.log10(np.sqrt(np.mean(piece ** 2)) + 1e-4))
        features.append(np.log2((spectrum * freqs).sum() / total + 20) / 2 if total > 0 else 0)
    return np.array(features)


def inspect(samples):
    window = int(0.05 * SR)
    for i in range(0, len(samples), window):
        piece = samples[i:i + window]
        loud = np.sqrt(np.mean(piece ** 2))
        spectrum = np.abs(np.fft.rfft(piece))
        centre = (spectrum * np.fft.rfftfreq(len(piece), 1 / SR)).sum() / max(spectrum.sum(), 1e-9)
        print(f"{i / SR:5.2f}s {'#' * round(loud * 120):<40} {centre:6.0f} Hz")


def load():
    voices = json.loads((RECIPES / "_voices.json").read_text(encoding="utf-8"))
    sounds = {}
    for path in sorted(RECIPES.glob("*.json")):
        if path.name.startswith("_"):
            continue
        for name, recipe in json.loads(path.read_text(encoding="utf-8")).items():
            sounds[f"{path.stem}.{name}"] = recipe
    return voices, sounds


def main():
    voices, sounds = load()
    if len(sys.argv) == 3 and sys.argv[1] == "--inspect":
        inspect(render(sounds[sys.argv[2]], voices, sys.argv[2]))
        return
    check(len(sys.argv) == 1, "usage", "make_sounds.py [--inspect CATEGORY.NAME]")

    events = {}
    subtitles = {}
    rows = {}
    prints = {}
    total_bytes = 0
    for name, recipe in sounds.items():
        samples = render(recipe, voices, name)
        category, short = name.split(".")
        path = ASSETS / "sounds" / category / f"{short}.ogg"
        path.parent.mkdir(parents=True, exist_ok=True)
        soundfile.write(path, samples.astype(np.float32), SR, format="OGG", subtype="VORBIS")
        total_bytes += path.stat().st_size
        reach = recipe.get("range", DEFAULT_RANGE)
        events[name] = {"subtitle": f"subtitles.{MOD_ID}.{name}",
                        "sounds": [{"name": f"{MOD_ID}:{category}/{short}", "attenuation_distance": reach}]}
        subtitles[f"subtitles.{MOD_ID}.{name}"] = short.replace("_", " ").title()
        rows.setdefault(category, []).append(
            f"| `{MOD_ID}:{name}` | {len(samples) / SR:.2f} | {reach} | {recipe['about']} |")
        prints[name] = fingerprint(samples)

    (ASSETS / "sounds.json").write_text(json.dumps(events, indent=2) + "\n", encoding="utf-8")

    lang_path = ASSETS / "lang" / "en_us.json"
    lang = {key: text for key, text in json.loads(lang_path.read_text(encoding="utf-8")).items()
            if not key.startswith(f"subtitles.{MOD_ID}.")}
    lang_path.write_text(json.dumps({**lang, **subtitles}, indent=2) + "\n", encoding="utf-8")

    lines = ["# Sound library", "",
             "Generated by `tools/make_sounds.py` from the recipes in `tools/sounds/`. Do not edit by hand.",
             "How to play, pair and change these sounds is in [SOUNDS.md](SOUNDS.md).", "",
             f"{len(sounds)} sounds. Length is in seconds; range is the distance in blocks at which the sound fades out."]
    for category, table in rows.items():
        lines += ["", f"## {category}", "", "| Sound | Length | Range | What you hear |", "|---|---|---|---|", *table]
    (ROOT / "docs" / "SOUND_LIBRARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    names = list(prints)
    matrix = np.array([prints[name] for name in names])
    distance = np.linalg.norm(matrix[:, None, :] - matrix[None, :, :], axis=2)
    distance[np.tril_indices(len(names))] = np.inf
    print(f"wrote {len(sounds)} sounds, {total_bytes / 1024:.0f} KiB")
    print("closest pairs (fingerprint distance; 0 would be identical):")
    for flat in np.argsort(distance, axis=None)[:15]:
        a, b = np.unravel_index(flat, distance.shape)
        print(f"  {distance[a, b]:.2f}  {names[a]}  ~  {names[b]}")


if __name__ == "__main__":
    main()

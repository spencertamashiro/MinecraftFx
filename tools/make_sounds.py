"""Builds the sound library from the recipes in tools/sounds/.

    python tools/make_sounds.py                              writes everything below
    python tools/make_sounds.py --inspect fire.meteor_strike prints one sound's loudness over time, writes nothing

Needs the packages pinned in tools/requirements-sounds.txt. Writes:
    src/main/resources/assets/minecraftfx/sounds/CATEGORY/NAME.ogg   one mono 44.1 kHz Ogg Vorbis file per recipe
    src/main/resources/assets/minecraftfx/sounds.json                  sound events "CATEGORY.NAME"
    src/main/resources/assets/minecraftfx/lang/en_us.json              the "subtitles.minecraftfx.*" keys only
    docs/SOUND_LIBRARY.md                                              the catalogue table

A recipe with 'loop' is written as one steady period plus a short seam, for a sound that is played again every period
(an effect replayed by a command block, say). Every other sound ends where its tail falls 60 dB below its peak, or at
its 'length'. The recipe format is documented in docs/SOUNDS.md. A full run takes about two and a half minutes.
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

MAX_SECONDS = 3.5        # how long a sound may run, unless its recipe sets 'length'; most end sooner, at TAIL_FLOOR
LONGEST_SECONDS = 15     # the most 'length' may ask for (the mega effects run up to 10 s plus a tail)
DEFAULT_RANGE = 24
# Loudness: the loudest 50 ms of every sound is brought to this RMS, unless that would clip.
TARGET_RMS = 0.26
PEAK_LIMIT = 0.95
TAIL_FLOOR = 10 ** (-60 / 20)  # a sound ends where it stays 60 dB below its peak

SOUND_KEYS = {"about", "layers", "range", "echo", "crush", "rate", "level", "length", "tape", "clip", "reverb",
              "silence", "loop", "reflect"}
STACK_KEYS = {"ratio", "share", "mix", "pin"}  # how one part of a stack voice differs from the note played
LOOP_SEAM = 0.5  # seconds a looping sound runs past its period, faded out, while the next play fades in
LAYER_KEYS = {"use", "wave", "freq", "glide", "duty", "fm", "at", "dur", "vol", "attack", "shape", "power", "release",
              "vib", "trem", "low", "high", "band", "repeat", "every", "step", "notes", "scatter", "fade", "jitter",
              "drive", "ensemble", "formant", "partials", "effort", "breath"}
DUCK_DEPTH = 0.6  # 'duck' 1 lowers the reverb tail by this fraction (8 dB) while the dry sound is at its loudest
WAVES = {"sine", "triangle", "square", "saw", "noise", "hiss", "partials"}
SHAPES = {"decay", "hold", "swell", "bump"}

# Resonances, as [centre Hz, bandwidth Hz, gain]: sung vowels (a low male choir, and sopranos) and the bodies of bowed strings and
# low brass. A bowed string really does vibrate as a sawtooth; the body and the section are what make it a cello.
FORMANTS = {
    "a": [[650, 90, 1.0], [1080, 110, 0.5], [2650, 160, 0.25], [2900, 170, 0.2], [3250, 200, 0.1]],
    "o": [[400, 80, 1.0], [800, 90, 0.45], [2600, 150, 0.12], [2800, 160, 0.1]],
    "u": [[350, 70, 1.0], [600, 80, 0.3], [2700, 150, 0.06]],
    "e": [[400, 80, 1.0], [1700, 120, 0.35], [2600, 160, 0.22]],
    "soprano": [[800, 100, 1.0], [1150, 120, 0.6], [2900, 200, 0.3], [3900, 250, 0.15]],  # an open 'ah' sung high
    "cello": [[110, 60, 0.6], [220, 90, 1.0], [470, 140, 0.8], [1100, 300, 0.4], [2500, 700, 0.35]],
    "violin": [[280, 110, 0.6], [500, 160, 0.8], [1000, 320, 0.6], [2800, 900, 0.75]],
    "brass": [[300, 160, 0.8], [600, 260, 1.0], [1200, 420, 0.6], [2400, 700, 0.3]],
}


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


def formant(x, spec):
    """Shapes x by a set of resonances (a vowel or an instrument body). Fixed, so done in one pass in the spectrum."""
    peaks = FORMANTS[spec] if isinstance(spec, str) else spec
    freqs = np.fft.rfftfreq(len(x), 1 / SR)
    response = np.full(freqs.shape, 0.04)
    for centre, width, gain in peaks:
        response += gain / (1 + ((freqs - centre) / (width / 2)) ** 2)
    return np.fft.irfft(np.fft.rfft(x) * response, len(x))


def rounded(x):
    """0 to 1 along a raised cosine as x goes 0 to 1: starts and lands without a corner, so no edge clicks or buzzes."""
    return 0.5 - 0.5 * np.cos(np.pi * np.clip(x, 0, 1))


def wander(n, rng, seconds, spread):
    """
    A slow random wobble, n samples long, that keeps returning towards 0 (spread is its typical size, seconds how long
    it takes to forget where it was). Players drift like this, never in step and never in a repeating cycle; a sine
    drift is a cycle, and the ear hears the machine in it. Drawn at 200 points a second and joined smoothly.
    """
    points = int(n / SR * 200) + 2
    keep = np.exp(-1 / (200 * seconds))
    kicks = rng.standard_normal(points) * spread * np.sqrt(1 - keep * keep)
    path = np.empty(points)
    path[0] = rng.standard_normal() * spread
    for i in range(1, points):
        path[i] = keep * path[i - 1] + kicks[i]
    return np.interp(np.arange(n) * (200 / SR), np.arange(points), path)


def blep(p, step):
    """
    The correction that takes the jump out of a sawtooth or square wave's edge (polyBLEP), at phase p with a per-sample
    phase step 'step'. A naive edge jumps within one sample and its overtones fold back below the top of hearing as a
    gritty, digital whine; smoothed over a sample either side it rings like a real string or reed.
    """
    out = np.zeros(len(p))
    rising = p < step
    x = p[rising] / step[rising]
    out[rising] = x + x - x * x - 1
    falling = p > 1 - step
    x = (p[falling] - 1) / step[falling]
    out[falling] = x * x + x + x + 1
    return out


def oversampled(x, shape, factor=4):
    """
    Applies a waveshaper (clipping, saturation) at four times the sample rate and filters the result back down. The
    overtones clipping makes run far above the top of hearing; at the plain rate they would fold back as harsh hash.
    """
    n = len(x)
    size = 1 << int(np.ceil(np.log2(2 * n)))  # padded, so the shaped edges ring off the end rather than wrap round
    spectrum = np.fft.rfft(x, size)
    wide = np.zeros(size * factor // 2 + 1, dtype=complex)
    wide[:len(spectrum)] = spectrum * factor
    shaped = np.fft.rfft(shape(np.fft.irfft(wide, size * factor)))[:len(spectrum)] / factor
    return np.fft.irfft(shaped, size)[:n]


def bowing(n, rng, rate, depth):
    """
    One player's tremolo: a bow change about 'rate' times a second, each a little early or late, each a short dip in
    level. Real tremolo is many players changing bow out of step; one shared sine across the section buzzes.
    """
    period = 1 / rate
    count = int(n / SR * rate) + 3
    gaps = np.clip(period * (1 + 0.15 * rng.standard_normal(count)), 0.6 * period, 1.6 * period)
    changes = (rng.uniform(0, period) + np.cumsum(gaps)) * SR
    changes = changes[changes < n].astype(int)
    width = int(min(rng.uniform(0.012, 0.03), 0.3 * period) * SR) * 2 + 1
    dips = np.zeros(n)
    dips[changes] = 1
    dips = np.convolve(dips, np.hanning(width), mode="same")
    return 1 - 0.6 * depth * np.clip(dips, 0, 1)


def oscillator(layer, pitch, t, u, rng, loose):
    """
    One player's raw waveform. A 'loose' player is one of an ensemble: its vibrato, tremolo, tuning drift, loudness
    and phase differ a little from its neighbours', which is what turns one oscillator into a section of real players.
    """
    n = len(t)
    freq = sweep(layer.get("freq", 440), u ** layer.get("glide", 1), True) * pitch
    if "vib" in layer:
        rate, semitones = layer["vib"]
        offset = 0.0
        depth = np.full(n, 1.0)
        if loose:
            rate *= rng.uniform(0.85, 1.15)
            offset = rng.uniform(0, 2 * np.pi)
            if semitones < 0.5:
                # A player's expressive vibrato: none on the attack, then it creeps in and breathes, as players do.
                # Wide vibrato (half a semitone and up) is an effect, a wobble, and starts at once.
                depth = rounded((t - rng.uniform(0.15, 0.35)) / 0.45) * (1 + 0.25 * wander(n, rng, 0.6, 1))
        freq = freq * 2 ** (semitones / 12 * depth * np.sin(2 * np.pi * rate * t + offset))
    if loose:
        freq = freq * 2 ** (wander(n, rng, rng.uniform(0.3, 2.0), layer["ensemble"][1] / 3) / 1200)
    phase = np.cumsum(freq) / SR
    if loose:
        phase = phase + rng.uniform(0, 1)

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
        duty = sweep(layer.get("duty", 0.5), u, False)
        step = np.clip(freq / SR, 1e-6, 0.5)
        x = np.where(phase % 1 < duty, 1.0, -1.0) + blep(phase % 1, step) - blep((phase - duty) % 1, step)
    elif wave == "saw":
        x = 2 * (phase % 1) - 1 - blep(phase % 1, np.clip(freq / SR, 1e-6, 0.5))
    elif wave == "noise":
        # A new random level 'freq' times a second: low rates rumble, high rates hiss. The NES noise channel.
        x = rng.uniform(-1, 1, int(phase[-1]) + 2)[phase.astype(int)]
    elif wave == "partials":
        # Inharmonic modes, each ringing for its own time: drum skins, bells, anvils, a tam-tam. An optional fourth
        # value fades a mode in, for the bloom of a gong whose energy climbs into its upper modes after the strike.
        x = np.zeros(n)
        for mode in layer["partials"]:
            ratio, amp, decay = mode[:3]
            ring = amp * np.exp(-6.9 * t / decay)
            if len(mode) > 3 and mode[3] > 0:
                ring = ring * (1 - np.exp(-3 * t / mode[3]))
            x += ring * np.sin(2 * np.pi * phase * ratio + (rng.uniform(0, 2 * np.pi) if loose else 0))
    else:
        x = rng.uniform(-1, 1, n)
    if loose and "trem" in layer:
        rate, depth = layer["trem"]
        if wave in ("noise", "hiss"):
            # a texture (whispered syllables, crackle): its flutter is the sound itself, so it stays a smooth pulse
            x = x * (1 - depth * (0.5 + 0.5 * np.sin(2 * np.pi * rate * rng.uniform(0.8, 1.2) * t + rng.uniform(0, 2 * np.pi))))
        else:
            x = x * bowing(n, rng, rate * rng.uniform(0.9, 1.1), depth)
    if loose and wave not in ("noise", "hiss"):
        x = x * 10 ** (wander(n, rng, rng.uniform(0.3, 1.0), 0.5) / 20)  # no player holds a perfectly steady level
    return x


def envelope_of(layer, t, u):
    """The note's loudness over time, 0 to 1. Every edge is rounded: an attack or release never starts on a corner."""
    n = len(t)
    power = layer.get("power", 2.0)
    shape = layer.get("shape", "decay")
    until_end = (n - np.arange(n)) / SR
    if shape == "decay":
        envelope = (1 - u) ** power
    elif shape == "hold":
        envelope = rounded(until_end / layer.get("release", 0.02))
    elif shape == "swell":
        envelope = u ** power * rounded(until_end / 0.01)
    else:
        envelope = np.sin(np.pi * u) ** power
    return envelope * rounded(t / layer.get("attack", 0.004))


def note(layer, pitch, rng):
    """One note of a layer, with its envelope, at unit volume."""
    n = max(1, int(layer["dur"] * SR))
    index = np.arange(n)
    t = index / SR
    u = index / n
    envelope = envelope_of(layer, t, u)
    players = layer.get("ensemble", [1])[0]
    if players == 1:
        x = oscillator(layer, pitch, t, u, rng, False)
    else:
        # A section: detuned players who each come in a few milliseconds apart.
        cents = layer["ensemble"][1]
        spread = layer["ensemble"][2] if len(layer["ensemble"]) > 2 else 0.03
        x = np.zeros(n)
        for _ in range(players):
            player = oscillator(layer, pitch * 2 ** (rng.uniform(-1, 1) * cents / 1200), t, u, rng, True)
            late = int(rng.uniform(0, spread) * SR)
            x[late:] += player[:n - late]

    cutoff = sweep(layer["low"], u, True) if "low" in layer else None
    if "effort" in layer:
        # Played harder, an instrument gets brighter, not just louder: the cutoff rides the envelope, from 'low' Hz
        # when quiet to 'high' Hz at full strength, and an optional 'blat' overshoots it on the attack, the brassy
        # rasp of a note struck hard, dying away over about 0.1 s.
        low, high = layer["effort"][:2]
        blat = layer["effort"][2] if len(layer["effort"]) > 2 else 1.0
        strength = envelope / max(np.max(envelope), 1e-9)
        attack = layer.get("attack", 0.004)
        effort = low * (high / low) ** strength * (1 + (blat - 1) * rounded(t / attack) * np.exp(-np.maximum(t - attack, 0) / 0.08))
        cutoff = effort if cutoff is None else np.minimum(cutoff, effort)
    if cutoff is not None:
        x = lowpass(x, cutoff)
    if "high" in layer:
        x = highpass(x, sweep(layer["high"], u, True))
    if "band" in layer:
        centre = sweep(layer["band"], u, True)
        x = highpass(lowpass(x, centre * 1.5), centre / 1.5)
    if "formant" in layer:
        x = formant(x, layer["formant"])
    peak = np.max(np.abs(x))
    if peak > 0:
        x = x / peak  # filters change the level; 'vol' should mean the same thing for every layer
    if "breath" in layer:
        # The air in a horn, the hair of a bow, the breath of a singer: band-limited noise under the tone, 'dB' below
        # it, never steady, with a puff where the note is tongued or bowed. Without it a tone is a machine's.
        db, bottom, top = layer["breath"]
        air = highpass(lowpass(rng.standard_normal(n), np.full(n, float(top))), np.full(n, float(bottom)))
        air *= 10 ** (wander(n, rng, 0.25, 2.5) / 20) * (1 + 1.5 * np.exp(-t / 0.04))
        sounding = envelope > 0.1 * np.max(envelope)
        if sounding.any():
            tone = np.sqrt(np.mean(x[sounding] ** 2))
            air *= tone * 10 ** (db / 20) / max(np.sqrt(np.mean(air[sounding] ** 2)), 1e-12)
            x = x + air

    if "trem" in layer and players == 1:  # an ensemble's players each bowed their own tremolo above
        rate, depth = layer["trem"]
        envelope = envelope * (1 - depth * (0.5 + 0.5 * np.sin(2 * np.pi * rate * t)))
    if "drive" in layer:
        # Hard clip: the loud start of a decaying note squares off and tears, then comes clean as it dies away.
        # Clipped at four times the sample rate, so it tears like an overdriven amplifier, not like a broken file.
        drive = layer["drive"]
        return oversampled(x * envelope * drive, lambda y: np.clip(y, -1, 1))
    return x * envelope


def follow(x, release):
    """The level of x: jumps up at once, falls back over 'release' seconds. Drives the ducking of the reverb."""
    level = np.convolve(np.abs(x), np.ones(int(0.01 * SR)) / int(0.01 * SR), mode="same").tolist()
    fall = np.exp(-1 / (release * SR))
    out = [0.0] * len(level)
    held = 0.0
    for i, value in enumerate(level):
        held = value if value > held else held * fall
        out[i] = held
    return np.array(out)


def reverb(dry, seconds, wet, duck, rng):
    """
    A cathedral: the dry sound convolved with a synthetic stone hall. Lows ring for 'seconds' (-60 dB), mids for three
    quarters of that and highs for under half, as they do in stone; a dozen early reflections come off the near walls
    first and the diffuse tail thickens in behind them. The tail is ducked by the dry sound's own level, a sidechain,
    so the hit stays crisp and the hall blooms once it lets go.
    """
    n = int(seconds * SR)
    t = np.arange(n) / SR
    size = 1 << int(np.ceil(np.log2(len(dry) + n)))
    freqs = np.fft.rfftfreq(size, 1 / SR)
    lows = 1 / (1 + (freqs / 350) ** 4)
    highs = 1 / (1 + (2500 / np.maximum(freqs, 1)) ** 4)
    hall = np.zeros(len(freqs), dtype=complex)
    # A real hall's echoes start sparse, a reflection here and there, and multiply until they merge into a smooth
    # wash about 150 ms in. Smooth noise from the first instant is the flat, sprayed-on reverb of a cheap plugin.
    density = np.clip((t - 0.035) / 0.12, 0, 1) ** 2
    floor = 0.003
    for weight, share in ((lows, 1.0), (np.clip(1 - lows - highs, 0, 1), 0.75), (highs, 0.4)):
        band = rng.uniform(-1, 1, n) * np.exp(-6.9 * t / (seconds * share)) * (1 - np.exp(-t / 0.08))
        sparse = rng.random(n) < np.maximum(density, floor)
        band = band * sparse / np.sqrt(np.maximum(density, floor))
        band[:int(0.035 * SR)] = 0  # pre-delay keeps the transient apart from its room
        hall += np.fft.rfft(band, size) * weight
    early = np.zeros(n)
    for _ in range(12):
        # each early reflection a short smear off a rough wall, not a single-sample click
        at = int(rng.uniform(0.012, 0.09) * SR)
        smear = int(rng.uniform(0.0004, 0.002) * SR) + 2
        early[at:at + smear] += rng.standard_normal(smear) * np.exp(-np.arange(smear) / (smear / 3)) * 0.5 * rng.uniform(0.4, 1)
    hall += np.fft.rfft(early, size)
    dark = 1 / (1 + (freqs / 5000) ** 2)
    tail = np.fft.irfft(np.fft.rfft(dry, size) * hall * dark, size)[:len(dry)]
    tail *= np.max(np.abs(dry)) / max(np.max(np.abs(tail)), 1e-9) * wet
    if duck > 0:
        # At most 8 dB down (duck 1): deeper, and the hall audibly pumps back up after every hit, a sucking swell
        level = follow(dry, 0.35)
        tail *= 1 - DUCK_DEPTH * duck * level / max(np.max(level), 1e-9)
    return dry + tail


def check(condition, where, message):
    if not condition:
        raise SystemExit(f"{where}: {message}")


def scaled(freq, ratio):
    return [f * ratio for f in freq] if isinstance(freq, list) else freq * ratio


def resolve(layer, voices, where):
    """
    The layers a recipe layer stands for: itself filled in from the voice it uses, or, for a voice that is a 'stack'
    (a section of instruments played as one, like the braam's trombones, tubas, horns and sub), one layer per part.
    The recipe's own keys apply to every part, except those a part pins; its freq, dur and vol are scaled per part by
    the part's 'ratio', 'share' and 'mix'. Rejects anything the renderer would ignore.
    """
    check(not set(layer) - LAYER_KEYS, where, f"unknown layer keys {sorted(set(layer) - LAYER_KEYS)}")
    if "use" not in layer:
        return [finish(layer, where)]
    check(layer["use"] in voices, where, f"unknown voice '{layer['use']}'")
    voice = voices[layer["use"]]
    own = {k: v for k, v in layer.items() if k != "use"}
    if "stack" not in voice:
        return [finish({**voice, **own}, where, own)]
    top = {k: v for k, v in voice.items() if k != "stack"}
    freq = own.get("freq", top.get("freq", 440))
    dur = own.get("dur", top.get("dur"))
    check(dur is not None, where, "a stack voice needs a 'dur'")
    parts = []
    for number, part in enumerate(voice["stack"]):
        check(not set(part) - LAYER_KEYS - STACK_KEYS, where, f"stack part {number + 1}: unknown keys")
        merged = {**top, **{k: v for k, v in part.items() if k not in STACK_KEYS}, **own}
        for key in part.get("pin", []):
            # a pinned key keeps the part's own setting, or its absence: the clean sub under a braam is never driven
            merged.pop(key, None)
            if key in part:
                merged[key] = part[key]
        merged["freq"] = scaled(freq, part.get("ratio", 1))
        merged["dur"] = dur * part.get("share", 1)
        merged["vol"] = own.get("vol", 1) * part.get("mix", 1)
        merged.setdefault("every", dur)  # every part repeats in step, however long it rings
        parts.append(finish(merged, f"{where} part {number + 1}", own))
    return parts


def finish(layer, where, own=None):
    if own is not None and "shape" in own and "release" not in own and layer.get("shape") != "hold":
        layer.pop("release", None)  # a held voice played with another shape drops the voice's release
    check(layer.get("wave") in WAVES, where, f"'wave' must be one of {sorted(WAVES)}")
    check("dur" in layer and layer["dur"] > 0, where, "'dur' is required and must be positive")
    check(layer.get("shape", "decay") in SHAPES, where, f"'shape' must be one of {sorted(SHAPES)}")
    check("fm" not in layer or layer["wave"] == "sine", where, "'fm' only applies to wave 'sine'")
    check("duty" not in layer or layer["wave"] == "square", where, "'duty' only applies to wave 'square'")
    check("release" not in layer or layer.get("shape") == "hold", where, "'release' only applies to shape 'hold'")
    check("notes" not in layer or "repeat" not in layer, where, "'notes' sets the repeat count; drop 'repeat'")
    check((layer["wave"] == "partials") == ("partials" in layer), where, "wave 'partials' needs 'partials', and only it")
    check(all(len(mode) in (3, 4) and mode[2] > 0 for mode in layer.get("partials", [])), where,
          "each partial is [ratio, amp, decay seconds] or [ratio, amp, decay seconds, bloom seconds]")
    check("ensemble" not in layer or (1 <= layer["ensemble"][0] <= 16 and len(layer["ensemble"]) in (2, 3)), where,
          "'ensemble' is [players 1 to 16, cents] or [players, cents, onset spread seconds]")
    check("effort" not in layer or (len(layer["effort"]) in (2, 3) and 20 <= layer["effort"][0] < layer["effort"][1]),
          where, "'effort' is [quiet Hz, full Hz] or [quiet Hz, full Hz, blat], quiet below full")
    check("breath" not in layer or (len(layer["breath"]) == 3 and layer["breath"][0] < 0
                                    and 20 <= layer["breath"][1] < layer["breath"][2]), where,
          "'breath' is [dB below the tone (negative), lowest Hz, highest Hz]")
    check(not isinstance(layer.get("formant"), str) or layer["formant"] in FORMANTS, where,
          f"'formant' must be one of {sorted(FORMANTS)} or a list of [Hz, width, gain]")
    return layer


def render(recipe, voices, name):
    """The finished mono samples of one sound, between -1 and 1."""
    check(not set(recipe) - SOUND_KEYS, name, f"unknown keys {sorted(set(recipe) - SOUND_KEYS)}")
    check("about" in recipe and recipe.get("layers"), name, "'about' and at least one layer are required")
    rng = np.random.default_rng(zlib.crc32(name.encode()))  # the same recipe always gives the same samples
    period = recipe.get("loop")
    if period is not None:
        # A loop is rendered as several plays one period apart. A note lasts at most three periods from its play's
        # start, and its room and echoes ring on after that; the period written ('steady') is the first one late
        # enough that a play before the first would no longer be heard in it, so it sounds exactly as a looping sound
        # does. That period, plus a seam, is what gets written.
        check(0 < period <= LONGEST_SECONDS / 2, name, f"'loop' must be above 0 and at most {LONGEST_SECONDS / 2} seconds")
        check(not {"length", "silence"} & set(recipe), name, "a looping sound takes 'loop' instead of 'length', and no 'silence'")
        ring = (recipe.get("reverb", [0])[0] + (5 * recipe["echo"][0] if "echo" in recipe else 0)
                + max([tap[0] for tap in recipe.get("reflect", [])] + [0]))
        steady = 2 + int(np.ceil(ring / period))
        plays = steady + 2
        length = plays * period
    else:
        length, plays = recipe.get("length", MAX_SECONDS), 1
        check(0 < length <= LONGEST_SECONDS, name, f"'length' must be above 0 and at most {LONGEST_SECONDS} seconds")
    mix = np.zeros(int(length * SR))
    for number, raw in enumerate(recipe["layers"]):
        parts = resolve(raw, voices, f"{name} layer {number + 1}")
        layer = parts[0]  # a stack's parts share their timing, so the first one stands for all
        notes = layer.get("notes", [0] * layer.get("repeat", 1))
        every = layer.get("every", layer["dur"])
        if period is not None:
            last = layer.get("at", 0) + (len(notes) - 1) * every
            check(last < period and max(p["dur"] for p in parts) <= 2 * period, name,
                  f"layer {number + 1}: in a loop every note must start inside the period and last at most two periods")
        for k, semitones in enumerate(notes):
            semitones += rng.uniform(-1, 1) * layer.get("scatter", 0)
            pitch = 2 ** (semitones / 12) * layer.get("step", 1) ** k
            at = layer.get("at", 0) + k * every + rng.uniform(-0.5, 0.5) * layer.get("jitter", 0) * every
            for each in parts:
                samples = note(each, pitch, rng) * each.get("vol", 1) * each.get("fade", 1) ** k
                for play in range(plays):
                    start = int((max(0.0, at) + play * (period or 0)) * SR)
                    part = samples[:max(0, len(mix) - start)]
                    mix[start:start + len(part)] += part

    if "tape" in recipe:
        # Tape saturation of the whole mix: lopsided soft clipping (tape bends its two halves differently), the DC that
        # leaves behind taken out, and the softened top end of a hot tape. The loudest moment buckles the most.
        drive = recipe["tape"]
        check(drive > 0, name, "'tape' must be positive")
        x = mix / np.max(np.abs(mix))
        mix = oversampled(x, lambda y: np.tanh(drive * (y + 0.15 * y * y)) / np.tanh(drive * 1.15))
        # Padded to twice the length, so the filter's ringing runs off the end instead of wrapping round onto the start.
        size = 1 << int(np.ceil(np.log2(2 * len(mix))))
        freqs = np.fft.rfftfreq(size, 1 / SR)
        mix = np.fft.irfft(np.fft.rfft(mix, size) * (1 - np.exp(-freqs / 25)) / (1 + (freqs / 11000) ** 2), size)[:len(mix)]
    if "clip" in recipe:
        # Hard clip at this fraction of the peak: only the transients reach it, and they tear.
        ceiling = recipe["clip"]
        check(0 < ceiling < 1, name, "'clip' must be between 0 and 1")
        peak = np.max(np.abs(mix))
        mix = oversampled(mix, lambda y: np.clip(y, -ceiling * peak, ceiling * peak))
    if "echo" in recipe:
        delay, feedback = recipe["echo"]
        dry = mix.copy()
        for tap in range(1, 6):
            shift = int(tap * delay * SR)
            if shift < len(mix):
                mix[shift:] += dry[:len(mix) - shift] * feedback ** tap
    if "reflect" in recipe:
        # Echoes off the land: each tap the sound again, later, quieter and duller, as it comes back off a hillside, a
        # treeline or a wall. Unequal delays, so they roll like thunder instead of stuttering in time. Only the mids and
        # highs come back as a separate slap: below about 150 Hz a delayed copy would pile onto the still-sounding sub
        # and body, swelling them after the hit and beating against them, so the sub stays clean.
        taps = recipe["reflect"]
        check(all(len(tap) == 3 and 0 < tap[0] <= 1 and 0 < tap[1] <= 1 and tap[2] >= 200 for tap in taps), name,
              "'reflect' is a list of [delay seconds up to 1, gain up to 1, cutoff Hz from 200]")
        size = 1 << int(np.ceil(np.log2(2 * len(mix))))
        freqs = np.fft.rfftfreq(size, 1 / SR)
        spectrum = np.fft.rfft(mix, size) * (freqs / 150) ** 2 / (1 + (freqs / 150) ** 2)
        dry = len(mix)
        for delay, gain, cutoff in taps:
            shift = int(delay * SR)
            if shift < dry:
                mix[shift:] += np.fft.irfft(spectrum / (1 + (freqs / cutoff) ** 2), size)[:dry - shift] * gain
    if "reverb" in recipe:
        seconds, wet, duck = (list(recipe["reverb"]) + [0])[:3]
        check(seconds > 0 and wet > 0 and 0 <= duck <= 1, name, "'reverb' is [seconds, wet, duck]: duck 0 to 1")
        mix = reverb(mix, seconds, wet, duck, rng)
    for start, end in recipe.get("silence", []):
        # A vacuum: everything, room included, drops out just before the hit, so the hit lands on silence.
        check(0 <= start < end <= length, name, "'silence' is a list of [from, to] seconds inside the sound")
        a, b, edge = int(start * SR), int(end * SR), int(0.004 * SR)
        gate = np.ones(len(mix))
        gate[a:b] = 0
        gate[max(0, a - edge):a] = np.linspace(1, 0, a - max(0, a - edge))
        mix = mix * gate
    if "rate" in recipe:
        hold = max(1, round(SR / recipe["rate"]))
        mix = mix[np.arange(len(mix)) // hold * hold]
    if "crush" in recipe:
        levels = 2 ** (recipe["crush"] - 1)
        mix = np.round(mix * levels) / levels

    if period is not None:
        # The steady period and a seam after it. Played again every period, each copy fades out over the seam while the
        # next fades in over the same stretch of identical sound, so the two add up to one unbroken loop.
        mix = mix[int(steady * period * SR):int(((steady + 1) * period + LOOP_SEAM) * SR)].copy()
        seam = int(LOOP_SEAM * SR)
        mix[:seam] *= np.linspace(0, 1, seam)
        mix[-seam:] *= np.linspace(1, 0, seam)
    else:
        peak = np.max(np.abs(mix))
        check(peak > 1e-9, name, "the sound is silent")
        # The tail is cut where it has fallen 60 dB below the peak: past that a reverb tail is inaudible in game and
        # only makes the file longer.
        audible = np.nonzero(np.abs(mix) > peak * TAIL_FLOOR)[0]
        mix = mix[:audible[-1] + 1]
        fade = min(len(mix), int(0.05 * SR))
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

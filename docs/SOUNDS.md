# Sounds

Every effect has its own sound: 306 of them, one per effect, listed with a description in
[SOUND_LIBRARY.md](SOUND_LIBRARY.md). A sound plays with its effect by default, but each is an ordinary Minecraft
sound event, so it also works on its own with no particles at all.

**Status: nobody has listened to these yet.** They are synthesised from written recipes, and were checked only by
measurement (see [What has been checked](#what-has-been-checked)). Expect some to need tuning by ear.

## Names

| Thing | Name | Example |
| --- | --- | --- |
| Effect | `minecraftfx:<category>/<name>` | `minecraftfx:fire/meteor_strike` |
| Its sound event | `minecraftfx:<category>.<name>` | `minecraftfx:fire.meteor_strike` |
| The audio file | `assets/minecraftfx/sounds/<category>/<name>.ogg` | `sounds/fire/meteor_strike.ogg` |
| Its subtitle key | `subtitles.minecraftfx.<category>.<name>` | shown as "Meteor Strike" |

The files are mono 44.1 kHz Ogg Vorbis. Library sounds run 1.1 to 6.8 seconds including their room tails (the
longest are the big explosions); the six `mega` sounds run 4.1 to 15 seconds. Mono matters: Minecraft only places mono sounds in the world.

Every sound is a synthesised orchestra, in one style: taikos, short braam stabs and booms for impacts; cellos,
dissonant strings and a low choir at the core; glassy and metallic chinks (anvil, cracked bell, crotales, glass); then
a cathedral tail with gongs and whispers. The mix goes through tape saturation, and the bigger hits through a hard
clip. A sound's size sets how much of that it gets: a coin pickup is a harp and a triangle in a small room, an
exploding star is the whole orchestra in a cathedral. Each category has its own colours on top, such as sopranos and
horns for holy, harp and timpani for water, sul ponticello strings for ice.

A hit lands on its own, never at the end of a charge. Where the picture builds before it (a bolt in flight, a motor
spinning up, water surging upward), the build peaks part-way and eases off into the hit instead of crescendoing into
it, and there is no moment of silence first. Nothing blooms after a hit either: a braam struck with the hit is a
short stab with no chord a semitone wide (two notes a semitone apart beat, a slow wobble), though a braam that is the
sound's voice or bed, such as `demonic_rift`'s growl, keeps its shape; no tam-tam rings on a hit (its upper modes
swell in after the strike), only a few as a quiet bed under shadow ambiences; and the room is pushed down only
lightly while the hit sounds. Only the six `mega` set pieces keep the older brief: a rising tremolo and a moment of
silence before their hit, held braams and blooming gongs, and a room pushed hard down under the dry sound.

Explosions are booms, not orchestra. Each of the 20 `explosion` sounds is one physical boom at a size (small to huge),
a distance (near, medium, far) and a loudness tier (0, -3, -7, -10 or -14 dB): a crack that only reaches you up close,
a body of four noise bands, a falling sub, sometimes a bass drum, debris, unequal echoes off the land, and a long room.
Only `implosion` has anything before its hit, a soft falling draw of air. The library's other big impacts, the 29
whose hit was a gran cassa (a meteor, a thunder clap, a ground slam), have the same boom core under their own
instruments: small, medium or large by how far the sound carries, close up, with its echoes off the land.

The players are built to sound played, not generated. Each one's pitch and loudness wander slowly and at random; a
section's players are detuned a few cents and come in a few milliseconds apart; brass plays a straight tone while
strings and choirs ease into their vibrato after the attack; an instrument gets brighter as it plays louder (`effort`);
brass, bows and voices carry a breath of noise under the tone (`breath`); string tremolo is uneven bow changes, not a
volume wave; and every edge of every envelope is rounded. The braam is a section too: trombones, tubas, horns an
octave and a twelfth up, a clean sub and an open piano's strings ringing in sympathy. These numbers come from a research
pass on cinematic brass and orchestral sound, most of them engineering judgement rather than measurement.

There is no 8-bit or chiptune sound, with one exception: eight `sci_fi` sounds keep synth tones because the synth is
the thing being heard (`laser_beam`, `photon_torpedo`, and the six computer-screen sounds `hack_progress`,
`data_stream`, `matrix_code`, `holographic_glitch`, `cyber_grid`, `scanner_sweep`). They still have strings and drums
underneath. Nothing in the library is a recording.

## Play a sound without its effect

```
/fx sound minecraftfx:fire/meteor_strike                   the effect's sound, 3 blocks in front of you
/fx sound minecraftfx:fire/meteor_strike ~ ~ ~ 1 pitch=0.7 at your feet, pitched down
/playsound minecraftfx:fire.meteor_strike player @a        vanilla command: any sound, any target, any slider
```

`/fx sound` plays whatever the effect file lists under `sounds`. `/playsound` plays one sound event by name and knows
nothing about effects. It works in command blocks, functions and datapacks too.

From Java, on the server:

```java
// The effect's own sounds, without its particles
MinecraftFx.play(level, effectId, pos, FxOptions.facing(normal).only(FxOptions.Parts.SOUND));

// One sound event directly, through vanilla; nothing from this mod is involved
level.playSound(null, pos.x, pos.y, pos.z,
        SoundEvent.createVariableRangeEvent(ResourceLocation.parse("minecraftfx:fire.meteor_strike")),
        SoundSource.PLAYERS, 1f, 1f);
```

The sound events are not in a registry, so there is nothing to look up or keep in sync: any client with the mod's
assets can play them by name.

## Play an effect without its sound

```
/fx visual minecraftfx:fire/meteor_strike ~ ~ ~4
```

```java
MinecraftFx.play(level, effectId, pos, FxOptions.facing(normal).only(FxOptions.Parts.VISUAL));
```

## Change a sound for one play

`/fx play|visual|sound <effect> [<pos> [<scale> [<options>]]]`. The options are `key=value` words in any order:

| Option | Values | Meaning |
| --- | --- | --- |
| `volume=` | 0 to 10 | Multiplies the volume. Above 1 is no louder up close; it carries further. |
| `pitch=` | 0.5 to 2 | Multiplies the pitch. 0.5 is an octave down, 2 an octave up. It also changes the length. |

`tint=`, `facing=`, `up=` and `force=` are covered in the README; `force=` changes only the particles. In Java these are `withVolume` and `withPitch` on `FxOptions`.

Scale does not change the sound. To make a bigger effect sound bigger, pass a lower pitch and a higher volume with it.

## Change which sounds an effect plays

An effect file lists its sounds. This is the whole pairing: there is no hidden rule that matches names.

```json
{
  "sounds": [ { "id": "minecraftfx:fire.meteor_strike" } ],
  "palette": { ... },
  "layers": [ ... ]
}
```

| Field | Default | Meaning |
| --- | --- | --- |
| `id` | | A sound event from any `sounds.json`: this mod's, vanilla's or a resource pack's. |
| `volume` | `1` | 0 to 10. |
| `pitch` | `[1]` | `[n]`, or `[min, max]` to pick a different pitch each play so repeats do not sound identical. |
| `delay` | `0` | Ticks to wait after the effect starts (0 to 200). |
| `category` | `"player"` | The volume slider that controls it: `master`, `music`, `record`, `weather`, `block`, `hostile`, `neutral`, `player`, `ambient` or `voice`. |

Up to 8 sounds per effect. Examples:

```json
"sounds": []
"sounds": [ { "id": "minecraft:entity.generic.explode", "volume": 2 } ]
"sounds": [ { "id": "minecraftfx:impact.hit_flash", "pitch": [0.9, 1.1] },
            { "id": "minecraftfx:pickup.coin_burst", "delay": 6, "volume": 0.5 } ]
```

The first is silent, the second uses a vanilla sound, and the third layers two with a delay. Leaving `sounds` out is
the same as an empty list. Like the rest of the file, this reloads with F3+T.

## Replace the audio itself

A resource pack can replace any file at `assets/minecraftfx/sounds/<category>/<name>.ogg`, or redefine an event in its
own `assets/minecraftfx/sounds.json` (several variants, another range, another subtitle). That is standard Minecraft
and needs nothing from this mod. Use mono files.

## Rebuild the sounds from their recipes

The audio is generated. Each sound is a short recipe in `tools/sounds/<category>.json`, and `tools/make_sounds.py`
turns the recipes into the `.ogg` files, `sounds.json`, the subtitles in `lang/en_us.json` and
[SOUND_LIBRARY.md](SOUND_LIBRARY.md).

```
python -m venv .sound-venv
.sound-venv\Scripts\pip install -r tools\requirements-sounds.txt
.sound-venv\Scripts\python tools\make_sounds.py
.sound-venv\Scripts\python tools\make_sounds.py --inspect fire.meteor_strike
```

The last line writes nothing. It prints the sound's loudness and brightness every 0.05 seconds, which is how to check
timing without playing it. A full run takes about six minutes and ends by listing the 15 pairs of sounds
that measure most alike, so a new sound that copies an old one shows up.

The same recipe always gives the same samples. The `.ogg` bytes still differ between runs, because the encoder stamps
each file with a random stream number.

### A recipe

Shortened from `fire.json`, which adds three more noise bands, crackles, a taiko, a cymbal and a drum skin to this:

```json
"meteor_strike": { "about": "A falling whistle, then a massive fiery impact", "range": 40, "tape": 2.3, "clip": 0.75,
                   "reflect": [[0.107, 0.4, 2793], [0.189, 0.26, 2406], [0.308, 0.2, 1615]],
                   "reverb": [4.13, 0.5, 0.25], "length": 6.0, "layers": [
  { "use": "violins", "freq": [1400, 180], "glide": 0.7, "dur": 0.6, "shape": "bump", "power": 1, "vol": 0.8 },
  { "use": "flame", "dur": 0.5, "shape": "bump", "power": 1, "vol": 0.6, "low": [1200, 420] },
  { "use": "crack", "at": 0.5 },
  { "wave": "hiss", "at": 0.5, "band": [77.0, 57.8], "dur": 0.9, "power": 2.2, "attack": 0.003, "vol": 0.75 },
  { "wave": "sine", "at": 0.5, "freq": [43.8, 35.0], "glide": 0.15, "dur": 1.8, "power": 1.6, "attack": 0.004, "vol": 0.9 },
  { "use": "gran_cassa", "at": 0.5, "freq": [65.6, 52.5], "dur": 1.8, "attack": 0.015, "vol": 0.45 },
  { "use": "braam", "at": 0.5, "freq": 55, "trem": [25, 0.5], "dur": 0.9, "power": 1.4, "vol": 0.7 } ] }
```

The violins slide down as the meteor falls, swelling and easing off with the flames before the hit. At 0.5 s a crack,
a band of noise, a falling sub, a bass drum a fifth above it and a short braam land together, and come back off the
land three times. Times are in seconds. An effect's timing is in ticks, so a flash at tick 10 wants its sound at 0.5.

| Sound key | Default | Meaning |
| --- | --- | --- |
| `about` | required | One line for the catalogue. |
| `layers` | required | The parts that are mixed together. |
| `range` | `24` | Blocks at which the sound has faded out. Effects are only sent to players within 96 blocks. |
| `level` | `1` | Loudness against the other sounds. Every sound is levelled to the same loudness first; quiet ambiences use 0.6 to 0.8. |
| `echo` | | `[seconds, strength]`: five fading repeats, evenly spaced. Even spacing is a rhythm, so no recipe uses it now; for the echoes of a blast or a hall use `reflect`. |
| `reflect` | | `[[delay seconds, gain, cutoff Hz], ...]`: echoes off the land, each the sound again after `delay` (up to 1 s), at `gain` and lowpassed at `cutoff` (from 200 Hz). Only the sound above about 150 Hz comes back, so the sub never beats against its own copy. Give unequal delays, between 0.08 and 0.45 s, each tap quieter and duller than the last. Applied after `echo`, before `reverb`. |
| `tape` | | Tape saturation of the whole mix (2 is warm, 3 is heavy): lopsided soft clipping, the DC it leaves removed, and the top end softened. The loudest moment bends the most. Worked at four times the sample rate, so it does not alias. |
| `clip` | | 0 to 1: hard-clips the mix at this fraction of its peak, after `tape`, so only the transients reach it and tear. Also at four times the sample rate. |
| `reverb` | | `[seconds, wet, duck]`: a cathedral tail. Lows ring for `seconds` (to -60 dB), mids for three quarters of that and highs for under half, after a 35 ms pre-delay and a dozen short early reflections; the tail starts sparse and thickens into a smooth wash over about 150 ms, as a real hall's does. `wet` sets how loud the tail peaks against the dry sound. `duck` (0 to 1, default 0) pushes the tail down while the dry sound is loud, by at most 8 dB at 1, and lets it bloom as the dry sound dies away, which is how a hit stays crisp inside a huge room. |
| `silence` | | `[[from, to], ...]` seconds cut to nothing after the reverb, with a 4 ms fade into each, for the vacuum just before a hit. Only the `mega` sounds use it. |
| `length` | `3.5` | Seconds the sound may run, up to 15. Anything past it is cut. A sound ends sooner if its tail falls 60 dB below its peak, where nobody can hear it. |
| `loop` | | Seconds, up to 7.5, instead of `length`: makes a sound to be played again every `loop` seconds with no gap and no bump. The recipe is played over and over, and the file keeps one steady period plus a 0.5 s seam that fades out while the next play fades in. Every note must start inside the period and last at most two periods; a note lasting two periods with `shape` `bump` and `power` 1 crossfades into its own next copy. No `silence`. |
| `crush` | | Bit depth, for a gritty digital sound (5 to 8). Only `sci_fi.holographic_glitch` uses it. |
| `rate` | | Sample rate in Hz to degrade to, for the same reason. |

| Layer key | Default | Meaning |
| --- | --- | --- |
| `use` | | A voice from `_voices.json` to start from. Every other key overrides the voice's. |
| `wave` | from the voice | `sine`, `triangle`, `square`, `saw`; `noise` (a new random level `freq` times a second: stepped, 8-bit noise, kept only in a few `sci_fi` sounds); `hiss` (plain white noise, shaped with the filters: every blast, crack, fire and water sound); `partials` (a struck drum, bell or plate, from `partials`). |
| `partials` | | `partials` wave only, and required by it: modes as `[ratio, amp, decay seconds]`, or `[ratio, amp, decay, bloom seconds]` for a mode that swells in after the strike, as a gong's upper modes do. |
| `ensemble` | | `[players, cents]` or `[players, cents, spread seconds]`: plays the note as a section of 1 to 16 slightly detuned players, each with its own vibrato, onset within `spread` (default 0.03), and pitch and loudness that wander slowly at random (the pitch by about a third of `cents`). How strings, choirs and brass get their width. |
| `effort` | | `[quiet Hz, full Hz]` or `[quiet Hz, full Hz, blat]`: brightness that follows loudness. The note is lowpassed at `quiet` Hz when soft and `full` Hz at its loudest, so it darkens as it dies away; `blat` (above 1) overshoots that on the attack and dies away over about 0.1 s, the rasp of a brass note hit hard. Combines with `low`: the lower cutoff wins. |
| `breath` | | `[dB, lowest Hz, highest Hz]`: noise in that band under the tone, `dB` (negative) below it, never steady, with a puff on the attack. The air in a horn, the hair of a bow, the breath of a singer. Brass and strings use -24 to -32. |
| `formant` | | Shapes the tone with resonances: `a`, `o`, `u`, `e` (sung vowels, low voices), `soprano` (an open vowel sung high), `cello`, `violin`, `brass`, or a list of `[Hz, width, gain]`. |
| `freq` | `440` | Hz, or `[from, to]` to slide over the note. |
| `glide` | `1` | Bends the slide: below 1 moves fast at first, above 1 moves late. |
| `duty` | `0.5` | `square` only: how much of each cycle is high. Small is thin and nasal. |
| `fm` | | `sine` only: `[ratio, depth]`, for bells, metal and glass. The depth fades over the note. |
| `at` | `0` | Seconds before the layer starts. |
| `dur` | required | Seconds each note lasts. |
| `vol` | `1` | Volume against the other layers. |
| `shape` | `decay` | `decay` starts full and dies away. `swell` grows and stops. `bump` rises and falls. `hold` stays full. Every edge is rounded (a raised cosine), so nothing starts or stops on a corner. |
| `power` | `2` | How sharp the shape is. |
| `attack` | `0.004` | Seconds to fade in. |
| `release` | `0.02` | `hold` only: seconds to fade out. A layer that gives a held voice another `shape` drops the voice's `release`. |
| `vib` | | `[rate, semitones]`: pitch wobble. In an `ensemble`, vibrato under half a semitone is a player's: it starts 0.15 to 0.35 s into the note and eases in, at a slightly different rate and depth for each player. Half a semitone and up is an effect and starts at once. |
| `trem` | | `[rate, depth]`: volume flutter. Fast rates buzz. In a tonal `ensemble` each player's tremolo is uneven bow changes (dips about `rate` times a second, timing loose by 15%), not a smooth wave; on `hiss` and `noise` it stays a smooth pulse. |
| `low`, `high` | | Cut everything above, or below, this Hz. May be `[from, to]`. |
| `band` | | Keep only a band around this Hz. May be `[from, to]`, which is how a whoosh sweeps. |
| `repeat`, `every` | `1`, `dur` | Play the note this many times, this many seconds apart. |
| `notes` | | Semitone steps, one note each, instead of `repeat`: `[0, 4, 7, 12]` is a rising major chord. |
| `step` | `1` | Multiplies the pitch on each repeat. |
| `fade` | `1` | Multiplies the volume on each repeat. |
| `jitter` | `0` | How unevenly repeats are spaced, 0 to 1. |
| `scatter` | `0` | Random pitch spread per repeat, in semitones. |
| `drive` | | Multiplies the note into a hard clip at full scale, at four times the sample rate. A decaying note squares off and tears at its loud start, then comes clean as it dies away: 2 to 3 thickens a sub, 5 to 7 makes a kick or crack buckle. |

`saw` and `square` are band-limited (polyBLEP), so a high note has no aliasing whine.

An unknown key, a missing `dur` or a key that does not apply to the layer's wave stops the build with the sound's name.

### Voices

`tools/sounds/_voices.json` holds 52 starting points, so the library shares one palette of timbres the way the effects
share sprite sheets. Change a voice and every sound that uses it changes.

| Kind | Voices |
| --- | --- |
| Drums | `gran_cassa`, `taiko`, `timpani`, `snare`, `skin` (a stick's slap), `whip`, `woodblock`, `cymbal` |
| Struck metal and glass | `tam_tam` (gong), `anvil`, `cracked_bell`, `tubular` (bells), `crotale`, `glock`, `ting` (triangle), `glass_chink` |
| Plucked | `pizz` (pizzicato strings), `harp` |
| Strings | `cellos`, `violins`, `tremolo`, `sul_pont` (glassy bowing at the bridge) |
| Brass | `braam`, `horns`, `trumpets` |
| Choir | `choir` (low men), `choir_high` (sopranos), `choir_whisper` |
| Blasts | `blast`, `crack`, `crunch`, `rumble` |
| Fire | `flame`, `crackle`, `sizzle`, `spark` |
| Air | `whoosh`, `slice`, `hiss`, `wind`, `rustle`, `whisper` |
| Water | `drip`, `splash`, `surf` |
| Synth, `sci_fi` only | `zap`, `blip`, `tick`, `rise`, `fall`, `wobble`, `drone` |

The blasts, fire, air and water voices are filtered noise. The synth row is there for the eight `sci_fi` sounds named
above; use the instruments for anything else.

A voice may be a `stack`: a section of instruments played as one note. `braam` is one:

```json
"braam": { "wave": "saw", "freq": 55, "dur": 1.8, "formant": "brass", "low": [4500, 700], "power": 1.4, "attack": 0.03,
           "stack": [ { "ensemble": [4, 5, 0.02], "drive": 2.2, "mix": 0.94 },
                      { "ratio": 2, "ensemble": [2, 5, 0.025], "attack": 0.08, "mix": 0.42 },
                      { "wave": "sine", "power": 0.8, "mix": 0.42, "pin": ["drive", "low", "formant"] } ] }
```

(shortened: the real one has tubas, a second horn part and the piano strings, and more keys per part). Each part is a
layer made from the voice's keys, then the part's, then the recipe layer's. The part keys are:

| Part key | Default | Meaning |
| --- | --- | --- |
| `ratio` | `1` | Multiplies the note's `freq`: 2 is an octave up, 3 an octave and a fifth. |
| `share` | `1` | Multiplies the note's `dur`, for a part that rings on after the others. |
| `mix` | `1` | Multiplies the note's `vol`. |
| `pin` | | Keys the recipe may not change for this part: it keeps its own setting, or none. The braam's clean sub pins `drive`, `trem`, `low` and the rest, so a recipe that drives or pulses the braam never drives or pulses its sub. |

Every part plays at the same `at`, `every`, `notes` and `repeat`.

### Add a sound for a new effect

1. Add a recipe under the effect's name in `tools/sounds/<category>.json`.
2. Run `tools/make_sounds.py`.
3. Add `"sounds": [ { "id": "minecraftfx:<category>.<name>" } ]` to the effect file.
4. Raise the expected counts in `EffectDefinitionTest`.

## What has been checked

- All 306 files decode with Minecraft's own Ogg reader as mono 44.1 kHz, and no two hold the same samples (unit test).
- Every effect file names the sound that matches it, and that sound exists in `sounds.json` with its file (unit test).
- The client loaded `sounds.json` with no warning about a missing or invalid file (before the orchestral library; not
  run again since).
- The 300 library sounds were rewritten from their old synth recipes, which the visuals had been timed to. Each one
  kept its old layers' timing. 190 of them have a clear hit, found from where the old audio was loudest, and in 189
  of those the new sound is within 4.4 dB of its loudest at that hit. The exception, `status.poisoned`, is nine
  randomly spaced plucks, not one hit: its loudest pluck now falls at 1.9 s instead of 1.6 s. Still true after the
  de-charge pass below.
- The de-charge pass (2026-11-11) changed 61 of the 280 library sounds outside `explosion`, counted by sound: 32
  lost their silence, in 31 the build-ups became bumps, in 16 the braam on the hit became a stab, in 13 the tam-tam
  on the hit went, 29 had their gran-cassa hit become a boom core, 7 swapped `echo` for `reflect`, and in 6 a braam
  struck with a lone taiko or crack lost its semitone or was shortened. It also capped the room's `duck` at 0.25 in
  all 280; for 219 of them that is the only change. In the 50 with a hit marked by a silence or a gran cassa, every
  hit is within 2.6 dB of the sound's loudest moment, and no build is still rising as its hit lands.
  The last 0.1 s before a hit is more than 3 dB louder than the quarter second before it in only three: where a part
  comes in just ahead of the hit (`drone_spawn`'s hovering cellos, `egg_hatch`'s peeps), and in `rift_tear`, whose rip
  starts from nothing 0.33 s before the hit and is already falling. All 61 correlate at 0.82 to 1.00 with the
  previous render in loudness over time. One build was turned down: `surge_aura`'s three surges as bumps put the
  second 4.4 dB over its whip, so they play at 0.7 of their old volume and the whip sits 2.3 dB under its loudest
  surge. The explosions and the megas decode to exactly the same samples as before the pass.
- The explosion rework (2026-11-11): every one of the 20 is loudest within 0.3 s of its hit (`implosion` at its
  0.5 s flash), and after that it only falls, apart from its echoes, thunder rolls and a reverb notch in the sub
  around 1 s, each climbing at most about 8 dB out of the trough just before it. Their files are 2.7 to 6.8 s.
- After the player and braam rework, the six `mega` sounds keep their shape: their loudness over time correlates at
  0.92 to 1.00 with the previous render, with the hits at the same moments, about 10% brighter on average.
- Likeness: the closest pair measures 0.29 apart (`ice.frozen_orb` and `sci_fi.matrix_code`: sopranos, glass and a
  crotale against synth blips, a drone and cellos, alike only in loudness and brightness over time), and 4 pairs
  measure under 0.5 (there were 10 before the de-charge pass). The typical distance to the nearest other sound is
  0.80, as before the pass (0.77 before the explosion rework); for the explosions the rework raised it from 0.83 to
  1.18.
- The looping `mega/dark_summoning_circle_loop`, laid end to end three times, has no dip or bump in level at its seams.
- `--inspect` shows each `mega` sound silent just before its hit and at full level on it.

Not checked: how any of them sounds, whether loudness is even to the ear, how they sit with vanilla sounds, and how
`/fx sound`, `volume=` and `pitch=` behave in a running game. The measurement of likeness only follows loudness and
brightness over time, so it cannot hear the difference between, say, a harp and a choir; two sounds can measure apart
and still seem alike, or measure close and sound nothing alike.

# Sounds

Every effect has its own sound: 300 of them, one per effect, listed with a description in
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

The files are mono 44.1 kHz Ogg Vorbis, 0.1 to 3.5 seconds long. Mono matters: Minecraft only places mono sounds in
the world.

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

`tint=`, `facing=` and `up=` are covered in the README. In Java these are `withVolume` and `withPitch` on `FxOptions`.

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
timing without playing it. A full run takes about 20 seconds and ends by listing the 15 pairs of sounds that measure
most alike, so a new sound that copies an old one shows up.

The same recipe always gives the same samples. The `.ogg` bytes still differ between runs, because the encoder stamps
each file with a random stream number.

### A recipe

```json
"meteor_strike": { "about": "A falling whistle, then a massive fiery impact", "range": 40, "echo": [0.12, 0.3], "layers": [
  { "use": "fall", "wave": "saw", "freq": [1400, 180], "low": 3000, "dur": 0.5, "shape": "swell" },
  { "use": "boom", "at": 0.5, "freq": [120, 28], "dur": 1.4 },
  { "use": "crack", "at": 0.5 } ] }
```

Times are in seconds. An effect's timing is in ticks, so a flash at tick 10 wants its sound at 0.5.

| Sound key | Default | Meaning |
| --- | --- | --- |
| `about` | required | One line for the catalogue. |
| `layers` | required | The parts that are mixed together. |
| `range` | `24` | Blocks at which the sound has faded out. Effects are only sent to players within 40 blocks. |
| `level` | `1` | Loudness against the other sounds. Every sound is levelled to the same loudness first; quiet ambiences use 0.6 to 0.8. |
| `echo` | | `[seconds, strength]`: five fading repeats. |
| `crush` | | Bit depth, for a gritty digital sound (5 to 8). |
| `rate` | | Sample rate in Hz to degrade to, for the same reason. |

| Layer key | Default | Meaning |
| --- | --- | --- |
| `use` | | A voice from `_voices.json` to start from. Every other key overrides the voice's. |
| `wave` | from the voice | `sine`, `triangle`, `square`, `saw`; `noise` (a new random level `freq` times a second, so low rumbles and high hisses); `hiss` (plain white noise, shaped with the filters). |
| `freq` | `440` | Hz, or `[from, to]` to slide over the note. |
| `glide` | `1` | Bends the slide: below 1 moves fast at first, above 1 moves late. |
| `duty` | `0.5` | `square` only: how much of each cycle is high. Small is thin and nasal. |
| `fm` | | `sine` only: `[ratio, depth]`, for bells, metal and glass. The depth fades over the note. |
| `at` | `0` | Seconds before the layer starts. |
| `dur` | required | Seconds each note lasts. |
| `vol` | `1` | Volume against the other layers. |
| `shape` | `decay` | `decay` starts full and dies away. `swell` grows and stops. `bump` rises and falls. `hold` stays full. |
| `power` | `2` | How sharp the shape is. |
| `attack` | `0.004` | Seconds to fade in. |
| `release` | `0.02` | `hold` only: seconds to fade out. |
| `vib` | | `[rate, semitones]`: pitch wobble. |
| `trem` | | `[rate, depth]`: volume flutter. Fast rates buzz. |
| `low`, `high` | | Cut everything above, or below, this Hz. May be `[from, to]`. |
| `band` | | Keep only a band around this Hz. May be `[from, to]`, which is how a whoosh sweeps. |
| `repeat`, `every` | `1`, `dur` | Play the note this many times, this many seconds apart. |
| `notes` | | Semitone steps, one note each, instead of `repeat`: `[0, 4, 7, 12]` is a rising major chord. |
| `step` | `1` | Multiplies the pitch on each repeat. |
| `fade` | `1` | Multiplies the volume on each repeat. |
| `jitter` | `0` | How unevenly repeats are spaced, 0 to 1. |
| `scatter` | `0` | Random pitch spread per repeat, in semitones. |

An unknown key, a missing `dur` or a key that does not apply to the layer's wave stops the build with the sound's name.

### Voices

`tools/sounds/_voices.json` holds 34 starting points, so the library shares one palette of timbres the way the effects
share sprite sheets. Change a voice and every sound that uses it changes.

| Kind | Voices |
| --- | --- |
| Hits and blasts | `boom`, `blast`, `thud`, `thump`, `crack`, `crunch` |
| Electric and digital | `zap`, `buzz`, `spark`, `blip`, `tick` |
| Fire | `flame`, `crackle`, `sizzle` |
| Tones | `chime`, `bell`, `clang`, `glass`, `pad`, `rise`, `fall`, `wobble` |
| Air | `whoosh`, `slice`, `hiss`, `wind`, `rustle`, `whisper` |
| Water | `drip`, `splash`, `surf` |
| Low and dark | `rumble`, `drone`, `growl` |

### Add a sound for a new effect

1. Add a recipe under the effect's name in `tools/sounds/<category>.json`.
2. Run `tools/make_sounds.py`.
3. Add `"sounds": [ { "id": "minecraftfx:<category>.<name>" } ]` to the effect file.
4. Raise the expected counts in `EffectDefinitionTest`.

## What has been checked

- All 300 files decode with Minecraft's own Ogg reader as mono 44.1 kHz, and no two hold the same samples (unit test).
- Every effect file names the sound that matches it, and that sound exists in `sounds.json` with its file (unit test).
- The client loaded `sounds.json` with no warning about a missing or invalid file.
- Timing was read from `--inspect` for a few sounds (for example the impact of `meteor_strike` lands at 0.5 s).
- Eight sounds that measured too close to another were rewritten.

Not checked: how any of them sounds, whether loudness is even to the ear, how they sit with vanilla sounds, and how
`/fx sound`, `volume=` and `pitch=` behave in a running game. The measurement of likeness is coarse; two sounds can
measure apart and still seem alike.

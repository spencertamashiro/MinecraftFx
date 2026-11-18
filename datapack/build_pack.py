"""Build the installable 26.3 packs from the mod catalog. Run with Python 3.10+ and Pillow."""
from collections import defaultdict
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

from particles import ASSETS, sample_layer

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "build"
FUNCTION = "data/minecraftfx/function/"
FALLBACK = {
    "arcane": "block.amethyst_block.chime", "explosion": "entity.generic.explode",
    "fire": "item.firecharge.use", "holy": "block.beacon.activate", "ice": "block.glass.break",
    "impact": "entity.player.attack.crit", "lightning": "entity.lightning_bolt.thunder",
    "melee": "entity.player.attack.sweep", "mega": "entity.warden.sonic_boom",
    "nature": "block.grass.break", "pickup": "entity.experience_orb.pickup",
    "sci_fi": "block.beacon.power_select", "shadow": "entity.enderman.teleport",
    "spawn": "entity.evoker.prepare_summon", "status": "block.enchantment_table.use",
    "water": "entity.generic.splash",
}


def add(archive, name, content):
    info = ZipInfo(name)
    info.compress_type = ZIP_STORED if name.endswith(".ogg") else ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, content)


def function(archive, path, lines):
    add(archive, FUNCTION + path + ".mcfunction", "\n".join(lines) + "\n")


def luma(rgb):
    return sum(((rgb >> shift) & 255) * weight for shift, weight in [(16, .299), (8, .587), (0, .114)])


def write_effect(archive, effect, definition):
    palette = {name: int(value[1:], 16) for name, value in definition["palette"].items()}
    colors = {name: f"c{i}" for i, name in enumerate(palette)}
    variables = {colors[name]: rgb for name, rgb in palette.items()}
    sizes, distances = {}, {}
    timeline = defaultdict(list)
    timeline[0] = []

    def coord(value):
        # Quantize to sixteenths. One macro argument per absolute distance serves all axes and signs.
        units = round(abs(value) * 16)
        if units == 0:
            return "^"
        key = f"p{units}"
        distances[key] = units / 16
        return ("^-" if value < 0 else "^") + f"$({key})"

    for index, layer in enumerate(definition["layers"]):
        for tick, pos, size, color, up in sample_layer(layer, f"{effect}/{index}"):
            size_key = f"s{max(1, round(size * 8))}"
            sizes[size_key] = int(size_key[1:]) / 8
            particle = ("minecraft:dust_color_transition{from_color:$(%s),to_color:$(%s),scale:$(%s)}"
                        % (colors[color[0]], colors[color[-1]], size_key))
            prefix = "$execute rotated $(up_yaw) $(up_pitch) run " if up else "$"
            loc = " ".join(coord(v) for v in pos)
            timeline[tick].append(f"{prefix}particle {particle} {loc} 0 0 0 0 1 $(visibility) @a[distance=..96]")

    ticks = sorted(timeline)
    for i, tick in enumerate(ticks):
        dots = list(dict.fromkeys(timeline[tick]))
        if len(dots) > 320:
            dots = [dots[j * len(dots) // 320] for j in range(320)]
        if i + 1 < len(ticks):
            nxt = ticks[i + 1]
            dots += [f"data modify entity @s data.frame set value {nxt}",
                     f"scoreboard players set @s mfx.wait {nxt - tick}"]
        else:
            dots.append("kill @s")
        function(archive, f"frames/{effect}/{tick}", dots)

    for mode in ["play", "visual", "sound"]:
        function(archive, f"{mode}/{effect}", [
            f'function minecraftfx:internal/request {{effect:"{effect}",options:{{}},mode:"{mode}"}}'])
    # All released definitions have a single unmodified, immediate cue. Reject schema changes
    # explicitly so a future new cue is never silently stripped from a release.
    [cue] = definition["sounds"]
    if set(cue) != {"id"}:
        raise ValueError(f"{effect}: add support for its extended sound cue before packaging")
    original = cue["id"]
    vanilla = "minecraft:" + FALLBACK[effect.split("/")[0]]
    function(archive, f"audio/{effect}", [
        f'$execute if data storage minecraftfx:work options{{audio:"original"}} run playsound {original} player @a[distance=..96] ~ ~ ~ $(volume) $(pitch)',
        f'$execute unless data storage minecraftfx:work options{{audio:"original"}} run playsound {vanilla} player @a[distance=..96] ~ ~ ~ $(volume) $(pitch)'])

    variables.update(distances)
    variables.update(sizes)
    function(archive, f"start/{effect}", [
        f'execute unless data storage minecraftfx:work {{mode:"visual"}} run function minecraftfx:audio/{effect} with storage minecraftfx:work options',
        'execute if data storage minecraftfx:work {mode:"sound"} run return 1',
        "data modify storage minecraftfx:work v set value " + json.dumps(variables, separators=(",", ":")),
        "function minecraftfx:internal/options",
        f"execute unless score #scale mfx.math matches 1000 run function minecraftfx:scale/{effect}",
        f"execute if data storage minecraftfx:work options.tint run function minecraftfx:tint/{effect}",
        f'data modify storage minecraftfx:work instance set value {{effect:"{effect}",frame:0}}',
        "data modify storage minecraftfx:work instance.v set from storage minecraftfx:work v",
        "execute summon minecraft:marker run function minecraftfx:internal/spawn"])

    scaling = [f"execute store result storage minecraftfx:work v.{key} double {value/1000:.9f} run scoreboard players get #scale mfx.math"
               for key, value in distances.items()]
    for key, value in sizes.items():
        # A vanilla dust size must stay in [0.01, 4].
        scaling += [f"execute store result storage minecraftfx:work v.{key} double {value/1000:.9f} run scoreboard players get #scale mfx.math",
                    f"execute if score #scale mfx.math matches {int(4000 / value) + 1}.. run data modify storage minecraftfx:work v.{key} set value 4.0d"]
    function(archive, f"scale/{effect}", scaling)
    brightest = max(map(luma, palette.values()))
    shading = []
    for key, color in palette.items():
        shade = round(1000 * luma(color) / brightest) if brightest else 0
        shading += [f"scoreboard players set #shade mfx.math {shade}", "function minecraftfx:internal/shade",
                    f"execute store result storage minecraftfx:work v.{colors[key]} int 1 run scoreboard players get #packed mfx.math"]
    function(archive, f"tint/{effect}", shading)


def build():
    OUT.mkdir(exist_ok=True)
    definitions = sorted((ASSETS / "fx").rglob("*.json"))
    events = json.loads((ASSETS / "sounds.json").read_text())
    with TemporaryDirectory(dir=OUT) as staging:
        data = Path(staging) / "MinecraftFx-26.3-datapack.zip"
        audio = Path(staging) / "MinecraftFx-26.3-sounds.zip"
        with ZipFile(data, "w") as archive:
            for path in [ROOT / "pack.mcmeta", *sorted((ROOT / "data").rglob("*"))]:
                if path.is_file():
                    add(archive, path.relative_to(ROOT).as_posix(), path.read_bytes())
            for path in definitions:
                effect = path.relative_to(ASSETS / "fx").with_suffix("").as_posix()
                definition = json.loads(path.read_text())
                for cue in definition["sounds"]:
                    if cue["id"].removeprefix("minecraftfx:") not in events:
                        raise ValueError(f"Missing event for {effect}")
                write_effect(archive, effect, definition)
            add(archive, "LICENSE", (ROOT.parent / "LICENSE").read_bytes())
        with ZipFile(audio, "w") as archive:
            add(archive, "pack.mcmeta", json.dumps({"pack": {"description": "MinecraftFx original sounds",
                "min_format": [97, 1], "max_format": [97, 1]}}))
            for path in [ASSETS / "sounds.json", ASSETS / "lang/en_us.json", *sorted((ASSETS / "sounds").rglob("*.ogg"))]:
                add(archive, "assets/minecraftfx/" + path.relative_to(ASSETS).as_posix(), path.read_bytes())
            add(archive, "LICENSE", (ROOT.parent / "LICENSE").read_bytes())
        for path in [data, audio]:
            path.replace(OUT / path.name)
    print(f"Built {len(definitions)} effects in {OUT}")


if __name__ == "__main__":
    build()

"""Sample the mod's sprite sheets and emission model into local-coordinate dust points."""
from functools import lru_cache
import json
import math
from pathlib import Path
from random import Random

from PIL import Image

ASSETS = Path(__file__).resolve().parents[1] / "src/main/resources/assets/minecraftfx"


def sample(values, rng):
    return rng.uniform(values[0], values[-1])


def curve(spec, t):
    values = [spec["from"], spec["to"]]
    if "mid" in spec:
        values.insert(1, spec["mid"])
        t *= 2
        if t >= 1:
            t -= 1
            values.pop(0)
    easing = spec.get("easing", "linear")
    if easing == "expoOut":
        t = 1 - 2 ** (-10 * t)
    elif easing == "expoIn":
        t = 2 ** (10 * (t - 1))
    elif easing == "quadIn":
        t *= t
    elif easing == "quadOut":
        t = 1 - (1 - t) ** 2
    return values[0] + (values[1] - values[0]) * t


@lru_cache(None)
def frames(sprite):
    return json.loads((ASSETS / "particles" / (sprite + ".json")).read_text())["textures"]


@lru_cache(None)
def silhouette(texture, budget):
    # Farthest-point sampling retains outlines even when a whole sprite gets just a few dots.
    with Image.open(ASSETS / "textures/particle" / (texture.split(":")[1] + ".png")) as image:
        alpha = image.convert("RGBA").getchannel("A")
        w, h = alpha.size
        points = [(2 * (x + 0.5) / w - 1, 1 - 2 * (y + 0.5) / h)
                  for y in range(h) for x in range(w) if alpha.getpixel((x, y)) >= 64]
    if not points:
        return []
    chosen = [min(points, key=lambda p: p[0] ** 2 + p[1] ** 2)]
    while len(chosen) < min(budget, len(points)):
        chosen.append(max(points, key=lambda p: min(math.dist(p, q) for q in chosen)))
    return chosen


def sample_layer(layer, seed):
    rng = Random(seed)
    sprite = frames(layer["sprite"].split(":")[1])
    count = layer.get("count", 1)
    # Keep evenly spaced spokes around the full circumference when reducing a dense burst.
    density = min(count, 32 if layer.get("pattern") == "even" else 8)
    for burst in range(layer.get("repeat", 1)):
        start = layer.get("delay", 0) + burst * layer.get("interval", 1)
        phase = math.radians(layer.get("phase", 0) + burst * layer.get("twist", 0))
        for index in range(density):
            angle = phase + math.tau * (index / density if layer.get("pattern") == "even" else rng.random())
            cos = math.cos(math.radians(layer.get("cone", 180)))
            if not layer.get("rim"):
                cos = rng.uniform(cos, 1)
            sin = math.sqrt(max(0, 1 - cos * cos))
            offset = sample(layer.get("offset", [0]), rng)
            speed = sample(layer.get("speed", [0]), rng)
            height = sample(layer.get("height", [0]), rng) + offset * cos
            lift = sample(layer.get("lift", [0]), rng) + speed * cos
            life = max(1, round(sample(layer["lifetime"], rng)))
            frame_choice = rng.randrange(len(sprite))
            for age in range(0, life, 5):
                t = age / life
                friction = layer.get("friction", 1)
                travel = age if friction == 1 else (1 - friction ** age) / (1 - friction)
                radius = (offset + speed * travel) * sin
                orbit = layer.get("orbit", 0)
                if orbit:
                    radius = max(0, radius)
                theta = angle + orbit * age
                x, y = radius * math.cos(theta), radius * math.sin(theta)
                z = height + lift * travel
                fall = 0.04 * layer.get("gravity", 0) * (
                    age * (age + 1) / 2 if friction == 1 else (age - friction * travel) / (1 - friction))
                if layer.get("axis") == "up":
                    z -= fall
                else:
                    y -= fall
                size = max(0.015, curve(layer["scale"], t))
                alpha = curve(layer.get("alpha", {"from": 1, "to": 0}), t)
                if alpha < 0.035:
                    continue
                length = curve(layer["length"], t) if "length" in layer else size
                budget = 16 if count == 1 else 4
                mode = layer.get("frames", "age")
                frame = frame_choice if mode == "random" else (0 if mode == "first" else int(t * len(sprite)))
                points = silhouette(sprite[frame], budget) if max(size, length) >= 0.32 else [(0, 0)]
                # In-plane axes by default; elongated streaks follow their velocity or orbit tangent.
                vx = speed * sin * math.cos(theta) - radius * orbit * math.sin(theta)
                vy = speed * sin * math.sin(theta) + radius * orbit * math.cos(theta)
                vz = lift
                norm = math.sqrt(vx * vx + vy * vy + vz * vz)
                rotation = layer.get("spin", 0) * age
                dot_size = max(0.125, min(4, size * (2 if len(points) > 1 else 6) * math.sqrt(alpha)))
                for px, py in points:
                    dx = size * (px * math.cos(rotation) - py * math.sin(rotation))
                    dy = length * (px * math.sin(rotation) + py * math.cos(rotation))
                    if layer.get("shape") == "streak" and norm > 0:
                        pos = (x + dx + dy * vx / norm, y + dy * vy / norm, z + dy * vz / norm)
                    else:
                        pos = (x + dx, y + dy, z)
                    yield start + age, pos, dot_size, layer["color"], layer.get("axis") == "up"

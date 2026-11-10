package dev.minecraftfx.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectDefinitionTest {
    /** Set by build.gradle. */
    private static final Path EFFECTS = Path.of(System.getProperty("minecraftfx.effects"));
    private static final Path ASSETS = EFFECTS.getParent();

    private static JsonObject read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static JsonObject criticalSpark() throws IOException {
        return read(EFFECTS.resolve("impact/critical_spark.json"));
    }

    private static Map<String, EffectDefinition> shippedEffects() throws IOException {
        Map<String, EffectDefinition> effects = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        try (Stream<Path> files = Files.walk(EFFECTS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                String id = EFFECTS.relativize(file).toString().replace('\\', '/');
                EffectDefinition.CODEC.parse(JsonOps.INSTANCE, read(file))
                        .ifSuccess(effect -> effects.put(id, effect))
                        .ifError(error -> failures.add(id + ": " + error.message()));
            }
        }
        assertEquals(List.of(), failures);
        return effects;
    }

    @Test
    void everyShippedEffectLoads() throws IOException {
        assertEquals(300, shippedEffects().size());
    }

    /** fx/fire/meteor_strike.json plays minecraftfx:fire.meteor_strike, which is sounds/fire/meteor_strike.ogg. */
    @Test
    void everyShippedEffectPlaysTheSoundNamedAfterIt() throws IOException {
        JsonObject events = read(ASSETS.resolve("sounds.json"));
        List<String> wrong = new ArrayList<>();
        shippedEffects().forEach((file, effect) -> {
            String name = file.substring(0, file.length() - ".json".length());
            String event = name.replace('/', '.');
            boolean paired = effect.sounds().size() == 1 && effect.sounds().getFirst().id().toString().equals("minecraftfx:" + event);
            if (!paired || !events.has(event) || !Files.exists(ASSETS.resolve("sounds/" + name + ".ogg"))) {
                wrong.add(file);
            }
        });
        assertEquals(List.of(), wrong);
        assertEquals(300, events.size());
    }

    /** Decoded with Minecraft's own Ogg reader, so a file that passes here is one the game can play. */
    @Test
    void everyShippedSoundDecodesAsMonoAndNoTwoAreTheSame() throws IOException {
        Map<ByteBuffer, Path> seen = new HashMap<>();
        try (Stream<Path> files = Files.walk(ASSETS.resolve("sounds"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".ogg")).toList()) {
                try (InputStream in = Files.newInputStream(file); JOrbisAudioStream sound = new JOrbisAudioStream(in)) {
                    assertEquals(1, sound.getFormat().getChannels(), file + " is not mono, so Minecraft would not place it in the world");
                    assertEquals(44100f, sound.getFormat().getSampleRate(), file.toString());
                    ByteBuffer samples = sound.readAll();
                    assertTrue(samples.remaining() >= 44100 / 20 * 2, file + " is shorter than a tick");
                    Path other = seen.putIfAbsent(samples, file);
                    assertTrue(other == null, file + " has the same samples as " + other);
                }
            }
        }
        assertEquals(300, seen.size());
    }

    @Test
    void soundCueNeedsOnlyAnId() {
        JsonObject json = JsonParser.parseString("{\"id\": \"minecraftfx:fire.meteor_strike\"}").getAsJsonObject();

        SoundCue cue = SoundCue.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

        assertEquals(new SoundCue(ResourceLocation.parse("minecraftfx:fire.meteor_strike"), SoundSource.PLAYERS, 1, new Layer.Range(1, 1), 0), cue);
    }

    @Test
    void soundCueTakesCategoryVolumePitchRangeAndDelay() {
        JsonObject json = JsonParser.parseString(
                "{\"id\": \"minecraft:entity.generic.explode\", \"category\": \"hostile\", \"volume\": 2, \"pitch\": [0.8, 1.2], \"delay\": 10}")
                .getAsJsonObject();

        SoundCue cue = SoundCue.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

        assertEquals(new SoundCue(ResourceLocation.parse("minecraft:entity.generic.explode"), SoundSource.HOSTILE, 2, new Layer.Range(0.8f, 1.2f), 10), cue);
    }

    @Test
    void unknownSoundCategoryIsRejected() {
        JsonObject json = JsonParser.parseString("{\"id\": \"minecraftfx:fire.meteor_strike\", \"category\": \"loud\"}").getAsJsonObject();

        DataResult<SoundCue> result = SoundCue.CODEC.parse(JsonOps.INSTANCE, json);

        assertTrue(result.error().orElseThrow().message().contains("'loud'"));
    }

    @Test
    void anEffectWithoutSoundsIsSilent() throws IOException {
        JsonObject json = criticalSpark();
        json.remove("sounds");

        EffectDefinition effect = EffectDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

        assertEquals(List.of(), effect.sounds());
    }

    @Test
    void noTwoShippedEffectsShareTheirLayers() throws IOException {
        Map<List<Layer>, String> seen = new HashMap<>();
        shippedEffects().forEach((id, effect) -> {
            String other = seen.putIfAbsent(effect.layers(), id);
            assertTrue(other == null, id + " has the same layers as " + other);
        });
    }

    /**
     * The generic explosion is a still flash, an expanding ring and a burst flung out in every direction. Impacts and
     * explosions may be that; anything else built from it reads as "another explosion", which is what play-testing
     * found, so each must have a shape of its own.
     */
    @Test
    void onlyImpactsAndExplosionsAreAFlashARingAndABurst() throws IOException {
        List<String> clones = new ArrayList<>();
        shippedEffects().forEach((id, effect) -> {
            if (id.startsWith("impact/") || id.startsWith("explosion/")) {
                return;
            }
            boolean flash = effect.layers().stream().anyMatch(layer -> sheet(layer).equals("flash") && !layer.motion().moves());
            boolean ring = effect.layers().stream().anyMatch(layer -> sheet(layer).equals("ring"));
            boolean burst = effect.layers().stream().anyMatch(layer -> layer.motion().speed().max() > 0
                    && layer.motion().pattern() == Layer.Pattern.RANDOM && layer.motion().orbit() == 0 && layer.motion().cone() >= 60);
            if (flash && ring && burst) {
                clones.add(id);
            }
        });
        assertEquals(List.of(), clones);
    }

    private static String sheet(Layer layer) {
        return net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getKey(layer.sprite()).getPath();
    }

    @Test
    void masterEffectUsesEveryShape() throws IOException {
        EffectDefinition effect = EffectDefinition.CODEC.parse(JsonOps.INSTANCE, criticalSpark()).getOrThrow();

        assertEquals(6, effect.layers().size());
        assertEquals(EnumSet.allOf(Layer.Shape.class),
                effect.layers().stream().map(Layer::shape).collect(Collectors.toCollection(() -> EnumSet.noneOf(Layer.Shape.class))));
    }

    @Test
    void layerNamingAColourMissingFromThePaletteIsRejected() throws IOException {
        JsonObject json = criticalSpark();
        json.getAsJsonObject("palette").remove("deep");

        DataResult<EffectDefinition> result = EffectDefinition.CODEC.parse(JsonOps.INSTANCE, json);

        assertTrue(result.error().orElseThrow().message().contains("'deep'"));
    }

    @Test
    void unknownEasingIsRejected() throws IOException {
        JsonObject json = criticalSpark();
        firstLayer(json).getAsJsonObject("scale").addProperty("easing", "wobble");

        DataResult<EffectDefinition> result = EffectDefinition.CODEC.parse(JsonOps.INSTANCE, json);

        assertTrue(result.error().orElseThrow().message().contains("wobble"));
    }

    @Test
    void streakThatDoesNotMoveIsRejected() throws IOException {
        JsonObject json = criticalSpark();
        JsonObject streak = json.getAsJsonArray("layers").get(4).getAsJsonObject();
        streak.remove("speed");
        streak.remove("gravity");

        DataResult<EffectDefinition> result = EffectDefinition.CODEC.parse(JsonOps.INSTANCE, json);

        assertTrue(result.error().orElseThrow().message().contains("does not move"));
    }

    @Test
    void tooManyParticlesInOneLayerIsRejected() throws IOException {
        JsonObject json = criticalSpark();
        firstLayer(json).addProperty("count", 100);
        firstLayer(json).addProperty("repeat", 6);

        DataResult<EffectDefinition> result = EffectDefinition.CODEC.parse(JsonOps.INSTANCE, json);

        assertTrue(result.error().orElseThrow().message().contains("600"));
    }

    private static JsonObject firstLayer(JsonObject effect) {
        return effect.getAsJsonArray("layers").get(0).getAsJsonObject();
    }

    @Test
    void coneSamplesAreUnitVectorsInsideTheCone() {
        RandomSource random = RandomSource.create(42);
        for (Vec3 axis : new Vec3[]{new Vec3(0, 1, 0), new Vec3(0, -1, 0), new Vec3(1, 2, -3).normalize()}) {
            for (int i = 0; i < 500; i++) {
                Vec3 direction = Layer.sampleCone(axis, 75, random.nextDouble() * Math.PI * 2, random);
                assertEquals(1.0, direction.length(), 1.0E-6);
                assertTrue(direction.dot(axis) >= Math.cos(Math.toRadians(75)) - 1.0E-6);
            }
        }
    }

    @Test
    void rimSamplesLieExactlyOnTheConeEdge() {
        RandomSource random = RandomSource.create(7);
        for (Vec3 axis : new Vec3[]{new Vec3(0, 1, 0), new Vec3(1, 2, -3).normalize()}) {
            for (float degrees : new float[]{0, 25, 90}) {
                for (int i = 0; i < 200; i++) {
                    Vec3 direction = Layer.sampleRim(axis, degrees, random.nextDouble() * Math.PI * 2);
                    assertEquals(1.0, direction.length(), 1.0E-6);
                    assertEquals(Math.cos(Math.toRadians(degrees)), direction.dot(axis), 1.0E-6);
                }
            }
        }
    }

    private static Layer.Motion evenMotion(float twist) {
        return evenMotion(0, twist);
    }

    private static Layer.Motion evenMotion(float phase, float twist) {
        return new Layer.Motion(Layer.Range.ZERO, 90, true, Layer.Pattern.EVEN, phase, twist, 0,
                Layer.Range.ZERO, Layer.Range.ZERO, Layer.Range.ZERO, 0, 1);
    }

    @Test
    void phaseTurnsTheWholePattern() {
        RandomSource random = RandomSource.create(1);
        Vec3 axis = new Vec3(0, 1, 0);
        Vec3 plain = Layer.sampleRim(axis, 90, evenMotion(0, 0).turn(0, 4, 0, random));
        Vec3 turned = Layer.sampleRim(axis, 90, evenMotion(45, 0).turn(0, 4, 0, random));
        assertEquals(Math.cos(Math.toRadians(45)), plain.dot(turned), 1.0E-6);
    }

    @Test
    void evenPatternOfFourMakesACross() {
        Layer.Motion motion = evenMotion(0);
        RandomSource random = RandomSource.create(1);
        Vec3 axis = new Vec3(1, 2, -3).normalize();
        Vec3[] spokes = new Vec3[4];
        for (int i = 0; i < 4; i++) {
            spokes[i] = Layer.sampleRim(axis, 90, motion.turn(i, 4, 0, random));
        }
        for (int i = 0; i < 4; i++) {
            assertEquals(0, spokes[i].dot(spokes[(i + 1) % 4]), 1.0E-6, "neighbours are square");
            assertEquals(-1, spokes[i].dot(spokes[(i + 2) % 4]), 1.0E-6, "opposites point apart");
        }
    }

    @Test
    void twistTurnsEachRepeatByThatAngle() {
        Layer.Motion motion = evenMotion(30);
        RandomSource random = RandomSource.create(1);
        Vec3 axis = new Vec3(0, 1, 0);
        Vec3 first = Layer.sampleRim(axis, 90, motion.turn(0, 6, 0, random));
        Vec3 second = Layer.sampleRim(axis, 90, motion.turn(0, 6, 1, random));
        assertEquals(Math.cos(Math.toRadians(30)), first.dot(second), 1.0E-6);
    }

    @Test
    void twistWithoutAnEvenPatternIsRejected() throws IOException {
        JsonObject json = criticalSpark();
        firstLayer(json).addProperty("twist", 15);

        DataResult<EffectDefinition> result = EffectDefinition.CODEC.parse(JsonOps.INSTANCE, json);

        assertTrue(result.error().orElseThrow().message().contains("twist"));
    }

    @Test
    void orbitWithGravityIsRejected() throws IOException {
        JsonObject json = criticalSpark();
        JsonObject streak = json.getAsJsonArray("layers").get(4).getAsJsonObject();
        streak.addProperty("orbit", 0.2);
        streak.addProperty("gravity", 0.5);

        DataResult<EffectDefinition> result = EffectDefinition.CODEC.parse(JsonOps.INSTANCE, json);

        assertTrue(result.error().orElseThrow().message().contains("orbit"));
    }

    @Test
    void orbitKeepsItsRadiusAndHeightForSixtyTicks() {
        for (Vec3 axis : new Vec3[]{new Vec3(0, 1, 0), new Vec3(1, 2, -3).normalize()}) {
            Vec3 centre = new Vec3(10, 64, -5);
            Vec3 start = centre.add(Layer.side(axis).scale(1.5)).add(axis.scale(0.5));
            Orbit orbit = new Orbit(centre, axis, start, Vec3.ZERO, 0.2, 1);
            Vec3 position = start;
            for (int tick = 0; tick < 60; tick++) {
                assertEquals(orbit.peek(), orbit.step());
                position = orbit.position();
                Vec3 relative = position.subtract(centre);
                assertEquals(0.5, relative.dot(axis), 1.0E-9, "height");
                assertEquals(1.5, relative.subtract(axis.scale(relative.dot(axis))).length(), 1.0E-9, "radius");
            }
            // 60 ticks at 0.2 rad is 12 rad: the particle has gone round almost twice and sits at 12 rad from where it began.
            Vec3 from = start.subtract(centre).subtract(axis.scale(0.5)).normalize();
            Vec3 to = position.subtract(centre).subtract(axis.scale(0.5)).normalize();
            assertEquals(Math.cos(12), from.dot(to), 1.0E-9);
        }
    }

    @Test
    void orbitKeepsLaunchSpeedOutwardAndAlongTheAxis() {
        Vec3 axis = new Vec3(0, 1, 0);
        Vec3 centre = Vec3.ZERO;
        Orbit orbit = new Orbit(centre, axis, new Vec3(1, 0, 0), new Vec3(0.1, 0.05, 0), 0.3, 1);
        for (int tick = 0; tick < 10; tick++) {
            orbit.step();
        }
        Vec3 position = orbit.position();
        assertEquals(0.5, position.y, 1.0E-9, "rose 0.05 a tick");
        assertEquals(2.0, Math.hypot(position.x, position.z), 1.0E-9, "moved out 0.1 a tick from 1");
    }

    @Test
    void inwardOrbitStopsAtTheAxis() {
        Orbit orbit = new Orbit(Vec3.ZERO, new Vec3(0, 1, 0), new Vec3(1, 0, 0), new Vec3(-0.3, 0, 0), 0.3, 1);
        for (int tick = 0; tick < 10; tick++) {
            orbit.step();
        }
        assertEquals(0, orbit.position().length(), 1.0E-9);
    }

    /** fire/fireball_burst's palette, brightest to darkest. */
    private static final Map<String, Integer> FIRE = Map.of("core", 0xFFF3B0, "hot", 0xFFB02E, "flame", 0xF2541B, "ember", 0x9E2412);
    private static final List<String> FIRE_ORDER = List.of("core", "hot", "flame", "ember");

    private static Map<String, Integer> tintFire(int tint) {
        return new EffectDefinition(List.of(), FIRE, List.of()).tinted(tint);
    }

    @Test
    void tintMakesTheBrightestColourExactlyTheTint() {
        assertEquals(0xAA0000, tintFire(0xAA0000).get("core"), "dark_red");
        assertEquals(0x55FFFF, tintFire(0x55FFFF).get("core"), "aqua");
    }

    @Test
    void tintKeepsEveryShadeDistinctAndInOrder() {
        Map<String, Integer> red = tintFire(0xAA0000);
        for (int i = 1; i < FIRE_ORDER.size(); i++) {
            int lighter = red.get(FIRE_ORDER.get(i - 1)) >> 16 & 255;
            int darker = red.get(FIRE_ORDER.get(i)) >> 16 & 255;
            assertTrue(darker < lighter, FIRE_ORDER.get(i) + " should be darker than " + FIRE_ORDER.get(i - 1) + ": " + red);
            assertEquals(0, red.get(FIRE_ORDER.get(i)) & 0x00FFFF, "pure red has no green or blue");
        }
    }

    @Test
    void blackTintMakesEverythingBlack() {
        tintFire(0x000000).forEach((name, rgb) -> assertEquals(0, rgb, name));
    }

    @Test
    void aDarkerTintGivesADarkerEffect() {
        Map<String, Integer> red = tintFire(0xFF0000);
        Map<String, Integer> darkRed = tintFire(0xAA0000);
        FIRE.keySet().forEach(name -> assertTrue((darkRed.get(name) >> 16 & 255) < (red.get(name) >> 16 & 255), name));
    }
}

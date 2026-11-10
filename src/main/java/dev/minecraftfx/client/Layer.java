package dev.minecraftfx.client;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;
import team.lodestar.lodestone.systems.easing.Easing;
import team.lodestar.lodestone.systems.particle.SimpleParticleOptions.ParticleSpritePicker;
import team.lodestar.lodestone.systems.particle.builder.WorldParticleBuilder;
import team.lodestar.lodestone.systems.particle.data.GenericParticleData;
import team.lodestar.lodestone.systems.particle.data.color.ColorParticleData;
import team.lodestar.lodestone.systems.particle.data.spin.SpinParticleData;
import team.lodestar.lodestone.systems.particle.render_types.LodestoneWorldParticleRenderType;
import team.lodestar.lodestone.systems.particle.world.behaviors.DirectionalParticleBehavior;
import team.lodestar.lodestone.systems.particle.world.behaviors.SparkParticleBehavior;
import team.lodestar.lodestone.systems.particle.world.type.LodestoneWorldParticleType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One burst of identical particles. Every field maps onto a Lodestone particle setting, so a layer costs nothing once
 * spawned: Lodestone animates, batches and draws it. The JSON is flat; {@link Burst} and {@link Motion} only group
 * fields in Java, because a Mojang record codec holds at most 16.
 *
 * @param sprite   sprite sheet
 * @param shape    how each quad is oriented in the world
 * @param additive glow blending (true) or ordinary alpha blending (false, for smoke and debris)
 * @param frames   how a particle picks frames from its sheet
 * @param axis     which direction the layer treats as "out": the effect's normal, or its up
 * @param scale    half-size in blocks; for streaks, the half-width
 * @param length   streaks only: half-length in blocks
 * @param color    one or two palette names: start colour, then end colour
 * @param spin     radians per tick, in a random direction from a random starting angle
 */
public record Layer(LodestoneWorldParticleType sprite, Shape shape, boolean additive, Frames frames, Axis axis,
                    Curve scale, Curve alpha, Optional<Curve> length, List<String> color, float spin,
                    Burst burst, Motion motion) {

    /** Most particles one layer may create, counting repeats. */
    static final int MAX_PARTICLES = 512;

    public enum Shape implements StringRepresentable {
        /** Always faces the camera. */
        BILLBOARD,
        /** Lies flat, facing along the layer's axis. */
        PLANE,
        /** Faces along its own travel direction; needs motion. */
        ORIENTED,
        /** Stretched along its own travel direction; needs motion. */
        STREAK;

        static final Codec<Shape> CODEC = StringRepresentable.fromEnum(Shape::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Frames implements StringRepresentable {
        /** Play the frames once over the particle's life. */
        AGE(ParticleSpritePicker.WITH_AGE),
        /** Keep one random frame, for sheets of variants such as leaves. */
        RANDOM(ParticleSpritePicker.RANDOM_SPRITE),
        FIRST(ParticleSpritePicker.FIRST_INDEX);

        static final Codec<Frames> CODEC = StringRepresentable.fromEnum(Frames::values);
        final ParticleSpritePicker picker;

        Frames(ParticleSpritePicker picker) {
            this.picker = picker;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Axis implements StringRepresentable {
        /** The direction the effect was played with, e.g. from the victim towards the attacker. */
        NORMAL,
        /**
         * The effect's up, for things that stand on the ground whichever way the effect faces. That is world up unless
         * the effect is played tipped over.
         */
        UP;

        static final Codec<Axis> CODEC = StringRepresentable.fromEnum(Axis::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Pattern implements StringRepresentable {
        /** Each particle picks its own direction around the axis. */
        RANDOM,
        /** The particles of one burst are spaced evenly around the axis, like spokes or the points of a star. */
        EVEN;

        static final Codec<Pattern> CODEC = StringRepresentable.fromEnum(Pattern::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A value that is picked once per particle from {@code [min, max]}. */
    public record Range(float min, float max) {
        static final Range ZERO = new Range(0, 0);
        static final Codec<Range> CODEC = Codec.FLOAT.listOf(1, 2).xmap(
                values -> new Range(values.getFirst(), values.getLast()), range -> List.of(range.min, range.max));

        float sample(RandomSource random) {
            return Mth.lerp(random.nextFloat(), min, max);
        }
    }

    /** A value animated over the particle's lifetime. */
    public record Curve(float from, Optional<Float> mid, float to, Easing easing) {
        private static final Codec<Easing> EASING = Codec.STRING.comapFlatMap(name -> {
            Easing easing = Easing.EASINGS.get(name);
            return easing != null ? DataResult.success(easing) : DataResult.error(() -> "Unknown easing '" + name + "'");
        }, easing -> easing.name);

        static final Codec<Curve> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("from").forGetter(Curve::from),
                Codec.FLOAT.optionalFieldOf("mid").forGetter(Curve::mid),
                Codec.FLOAT.fieldOf("to").forGetter(Curve::to),
                EASING.optionalFieldOf("easing", Easing.LINEAR).forGetter(Curve::easing)
        ).apply(instance, Curve::new));

        GenericParticleData data(float multiplier) {
            return mid.map(middle -> GenericParticleData.create(from * multiplier, middle * multiplier, to * multiplier))
                    .orElseGet(() -> GenericParticleData.create(from * multiplier, to * multiplier))
                    .setEasing(easing).build();
        }
    }

    /**
     * When particles appear.
     *
     * @param delay    ticks before the first burst
     * @param repeat   how many bursts
     * @param interval ticks between bursts
     * @param lifetime ticks each particle lives, {@code [n]} or {@code [min, max]}
     */
    public record Burst(int count, int delay, int repeat, int interval, Range lifetime) {
        static final MapCodec<Burst> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.intRange(1, 256).optionalFieldOf("count", 1).forGetter(Burst::count),
                Codec.intRange(0, 200).optionalFieldOf("delay", 0).forGetter(Burst::delay),
                Codec.intRange(1, 64).optionalFieldOf("repeat", 1).forGetter(Burst::repeat),
                Codec.intRange(1, 100).optionalFieldOf("interval", 1).forGetter(Burst::interval),
                Range.CODEC.fieldOf("lifetime").forGetter(Burst::lifetime)
        ).apply(instance, Burst::new));
    }

    /**
     * Where particles start and how they move. Each particle draws a direction from the cone around the axis; it starts
     * {@code offset} blocks out along that direction and {@code height} blocks along the axis, then travels along the
     * direction at {@code speed} and along the axis at {@code lift}. A negative speed travels inwards.
     *
     * @param cone     half-angle in degrees around the axis; 180 is every direction
     * @param rim      draw directions on the cone's edge only, so cone 90 with rim is a flat ring
     * @param pattern  how directions are spread around the axis
     * @param phase    pattern "even" only: degrees the whole pattern is turned, so 4 particles at phase 45 make an X
     * @param twist    pattern "even" only: degrees each repeat turns from the one before, for spirals and pinwheels
     * @param orbit   radians per tick that particles circle the axis through the effect's origin, anticlockwise seen
     *                 from the axis tip; negative turns the other way. Speed and lift still move them out and along
     * @param gravity  multiple of vanilla particle gravity (0.04 blocks per tick squared); negative rises
     * @param friction velocity kept each tick
     */
    public record Motion(Range speed, float cone, boolean rim, Pattern pattern, float phase, float twist, float orbit,
                         Range offset, Range height, Range lift, float gravity, float friction) {
        static final MapCodec<Motion> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Range.CODEC.optionalFieldOf("speed", Range.ZERO).forGetter(Motion::speed),
                Codec.floatRange(0, 180).optionalFieldOf("cone", 180f).forGetter(Motion::cone),
                Codec.BOOL.optionalFieldOf("rim", false).forGetter(Motion::rim),
                Pattern.CODEC.optionalFieldOf("pattern", Pattern.RANDOM).forGetter(Motion::pattern),
                Codec.FLOAT.optionalFieldOf("phase", 0f).forGetter(Motion::phase),
                Codec.FLOAT.optionalFieldOf("twist", 0f).forGetter(Motion::twist),
                Codec.floatRange(-1, 1).optionalFieldOf("orbit", 0f).forGetter(Motion::orbit),
                Range.CODEC.optionalFieldOf("offset", Range.ZERO).forGetter(Motion::offset),
                Range.CODEC.optionalFieldOf("height", Range.ZERO).forGetter(Motion::height),
                Range.CODEC.optionalFieldOf("lift", Range.ZERO).forGetter(Motion::lift),
                Codec.FLOAT.optionalFieldOf("gravity", 0f).forGetter(Motion::gravity),
                Codec.FLOAT.optionalFieldOf("friction", 1f).forGetter(Motion::friction)
        ).apply(instance, Motion::new));

        boolean moves() {
            return speed.min != 0 || speed.max != 0 || lift.min != 0 || lift.max != 0 || gravity != 0 || orbit != 0;
        }

        /** Radians around the axis for particle {@code index} of burst {@code round}. */
        double turn(int index, int count, int round, RandomSource random) {
            return switch (pattern) {
                case RANDOM -> random.nextDouble() * Math.PI * 2;
                case EVEN -> Math.PI * 2 * index / count + Math.toRadians(phase + twist * round);
            };
        }
    }

    private static final Curve FADE_OUT = new Curve(1, Optional.empty(), 0, Easing.LINEAR);
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private static final Codec<LodestoneWorldParticleType> SPRITE = BuiltInRegistries.PARTICLE_TYPE.byNameCodec().comapFlatMap(
            type -> type instanceof LodestoneWorldParticleType sheet ? DataResult.success(sheet)
                    : DataResult.error(() -> BuiltInRegistries.PARTICLE_TYPE.getKey(type) + " is not a Lodestone sprite sheet"),
            sheet -> sheet);

    public static final Codec<Layer> CODEC = RecordCodecBuilder.<Layer>create(instance -> instance.group(
            SPRITE.fieldOf("sprite").forGetter(Layer::sprite),
            Shape.CODEC.optionalFieldOf("shape", Shape.BILLBOARD).forGetter(Layer::shape),
            Codec.BOOL.optionalFieldOf("additive", true).forGetter(Layer::additive),
            Frames.CODEC.optionalFieldOf("frames", Frames.AGE).forGetter(Layer::frames),
            Axis.CODEC.optionalFieldOf("axis", Axis.NORMAL).forGetter(Layer::axis),
            Curve.CODEC.fieldOf("scale").forGetter(Layer::scale),
            Curve.CODEC.optionalFieldOf("alpha", FADE_OUT).forGetter(Layer::alpha),
            Curve.CODEC.optionalFieldOf("length").forGetter(Layer::length),
            Codec.STRING.listOf(1, 2).fieldOf("color").forGetter(Layer::color),
            Codec.FLOAT.optionalFieldOf("spin", 0f).forGetter(Layer::spin),
            Burst.CODEC.forGetter(Layer::burst),
            Motion.CODEC.forGetter(Layer::motion)
    ).apply(instance, Layer::new)).validate(Layer::validate);

    private DataResult<Layer> validate() {
        if (burst.count * burst.repeat > MAX_PARTICLES) {
            return DataResult.error(() -> "count x repeat is " + burst.count * burst.repeat + ", over the limit of " + MAX_PARTICLES);
        }
        if ((shape == Shape.ORIENTED || shape == Shape.STREAK) && !motion.moves()) {
            return DataResult.error(() -> "Shape '" + shape.getSerializedName() + "' follows the particle's motion, but the layer does not move");
        }
        if (length.isPresent() && shape != Shape.STREAK) {
            return DataResult.error(() -> "'length' only applies to shape 'streak'");
        }
        if ((motion.twist != 0 || motion.phase != 0) && motion.pattern != Pattern.EVEN) {
            return DataResult.error(() -> "'phase' and 'twist' turn an even pattern, but the layer's pattern is random");
        }
        if (motion.orbit != 0 && motion.gravity != 0) {
            return DataResult.error(() -> "An orbiting layer cannot use gravity: gravity pulls down while the orbit turns about the axis");
        }
        return DataResult.success(this);
    }

    /**
     * @param normal the direction the effect faces
     * @param up     the direction the effect stands along. Gravity still pulls towards the ground
     * @param size   multiplies every distance: sizes, offsets, speeds and gravity, so the effect keeps its shape and timing
     * @param force  spawn every particle even beyond 32 blocks of the camera or with the Particles setting turned down
     */
    void emit(ClientLevel level, Vec3 pos, Vec3 normal, Vec3 up, float size, Map<String, Integer> palette, boolean force) {
        RandomSource random = level.random;
        Vec3 out = axis == Axis.UP ? up : normal;
        int start = palette.get(color.getFirst());
        int end = palette.get(color.getLast());
        ColorParticleData colors = ColorParticleData.create(
                (start >> 16 & 255) / 255f, (start >> 8 & 255) / 255f, (start & 255) / 255f,
                (end >> 16 & 255) / 255f, (end >> 8 & 255) / 255f, (end & 255) / 255f).build();
        GenericParticleData scaleData = scale.data(size);
        GenericParticleData alphaData = alpha.data(1);
        Optional<GenericParticleData> lengthData = length.map(curve -> curve.data(size));

        for (int round = 0; round < burst.repeat; round++) {
            for (int i = 0; i < burst.count; i++) {
                double turn = motion.turn(i, burst.count, round, random);
                Vec3 direction = motion.rim ? sampleRim(out, motion.cone, turn) : sampleCone(out, motion.cone, turn, random);
                Vec3 start3 = pos.add(direction.scale(motion.offset.sample(random) * size))
                        .add(out.scale(motion.height.sample(random) * size));
                Vec3 velocity = direction.scale(motion.speed.sample(random) * size)
                        .add(out.scale(motion.lift.sample(random) * size));
                Orbit orbit = motion.orbit == 0 ? null : new Orbit(pos, out, start3, velocity, motion.orbit, motion.friction);
                // shortcut: no block collision. Bursts are short-lived, and skipping it keeps big fights cheap.
                WorldParticleBuilder particle = WorldParticleBuilder.create(sprite)
                        .setRenderType(additive ? LodestoneWorldParticleRenderType.ADDITIVE : LodestoneWorldParticleRenderType.TRANSPARENT)
                        .setSpritePicker(frames.picker)
                        .setColorData(colors)
                        .setScaleData(scaleData)
                        .setTransparencyData(alphaData)
                        .setLifetime(Math.round(burst.lifetime.sample(random)))
                        .setLifeDelay(burst.delay + round * burst.interval)
                        .setGravity(motion.gravity * size)
                        .enableNoClip()
                        .setForceSpawn(force);
                if (orbit == null) {
                    particle.setFriction(motion.friction).setMotion(velocity);
                } else {
                    // The orbit sets the velocity every tick, so Minecraft's own friction must leave it alone.
                    particle.setFriction(1).setMotion(orbit.peek().subtract(start3)).addTickActor(orbit);
                }
                lengthData.ifPresent(particle::setLengthData);
                if (spin != 0) {
                    particle.setSpinData(SpinParticleData.createRandomDirection(random, spin).randomSpinOffset(random).build());
                }
                switch (shape) {
                    case BILLBOARD -> { }
                    case PLANE -> particle.setBehavior(DirectionalParticleBehavior.directional(out));
                    case ORIENTED -> particle.setBehavior(DirectionalParticleBehavior.directional());
                    case STREAK -> particle.setBehavior(SparkParticleBehavior.sparkBehavior());
                }
                particle.spawn(level, start3.x, start3.y, start3.z);
            }
        }
    }

    /** A unit vector {@code turn} radians around {@code axis}, at a uniformly random angle within {@code degrees} of it. */
    static Vec3 sampleCone(Vec3 axis, float degrees, double turn, RandomSource random) {
        return around(axis, Mth.lerp(random.nextDouble(), Math.cos(Math.toRadians(degrees)), 1), turn);
    }

    /** The unit vector {@code turn} radians around {@code axis} and exactly {@code degrees} from it. */
    static Vec3 sampleRim(Vec3 axis, float degrees, double turn) {
        return around(axis, Math.cos(Math.toRadians(degrees)), turn);
    }

    private static Vec3 around(Vec3 axis, double cos, double turn) {
        double sin = Math.sqrt(Math.max(0, 1 - cos * cos));
        Vec3 side = side(axis);
        Vec3 other = axis.cross(side);
        return side.scale(Math.cos(turn) * sin).add(other.scale(Math.sin(turn) * sin)).add(axis.scale(cos));
    }

    /** A unit vector square to {@code axis}: turn 0 around it. {@code axis.cross(side)} is a quarter turn on. */
    static Vec3 side(Vec3 axis) {
        return axis.cross(Math.abs(axis.y) < 0.99 ? UP : new Vec3(1, 0, 0)).normalize();
    }
}

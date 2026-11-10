package dev.minecraftfx;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * How one effect is played: which way it points, how big, what colour, and whether its picture, its sound or both play.
 * Start from {@link #facing} and change what you need: {@code FxOptions.facing(normal).withScale(2).only(Parts.SOUND)}.
 *
 * @param normal unit vector the effect faces along, e.g. from the victim towards the attacker
 * @param up     unit vector the effect treats as up: layers with axis "up" stand along it. World up unless changed
 * @param scale  multiplies every distance in the effect (size, spread, speed); {@link #MIN_SCALE} to {@link #MAX_SCALE}
 * @param tint   0xRRGGBB: the effect's brightest colour becomes exactly this, and the rest become darker shades of it;
 *               empty keeps its own colours
 * @param parts  the picture, the sound or both
 * @param volume multiplies the volume of the effect's sounds; 0 to {@link #MAX_VOLUME}. Above 1 also carries further
 * @param pitch  multiplies the pitch of the effect's sounds; {@link #MIN_PITCH} to {@link #MAX_PITCH}
 */
public record FxOptions(Vec3 normal, Vec3 up, float scale, Optional<Integer> tint, Parts parts, float volume, float pitch) {
    public enum Parts {
        BOTH, VISUAL, SOUND;

        public boolean visual() {
            return this != SOUND;
        }

        public boolean sound() {
            return this != VISUAL;
        }
    }

    /** Limits on the size multiplier. Past these, sprites become sub-pixel or fill the screen. */
    public static final float MIN_SCALE = 0.1f, MAX_SCALE = 10f;
    public static final float MAX_VOLUME = 10f;
    /** Minecraft's sound engine clamps pitch to this range. */
    public static final float MIN_PITCH = 0.5f, MAX_PITCH = 2f;

    private static final Vec3 WORLD_UP = new Vec3(0, 1, 0);

    public FxOptions {
        requireUnit("normal", normal);
        requireUnit("up", up);
        requireRange("scale", scale, MIN_SCALE, MAX_SCALE);
        requireRange("volume", volume, 0, MAX_VOLUME);
        requireRange("pitch", pitch, MIN_PITCH, MAX_PITCH);
        if (tint.isPresent() && (tint.get() & ~0xFFFFFF) != 0) {
            throw new IllegalArgumentException("Tint " + Integer.toHexString(tint.get()) + " is not 0xRRGGBB");
        }
    }

    /** The effect as authored, facing along {@code normal}. A zero vector faces up. */
    public static FxOptions facing(Vec3 normal) {
        return new FxOptions(unit(normal), WORLD_UP, 1, Optional.empty(), Parts.BOTH, 1, 1);
    }

    public FxOptions withNormal(Vec3 normal) {
        return new FxOptions(unit(normal), up, scale, tint, parts, volume, pitch);
    }

    /** Tips the effect over: a ground circle played with up pointing north lies on a north-facing wall. */
    public FxOptions withUp(Vec3 up) {
        return new FxOptions(normal, unit(up), scale, tint, parts, volume, pitch);
    }

    public FxOptions withScale(float scale) {
        return new FxOptions(normal, up, scale, tint, parts, volume, pitch);
    }

    public FxOptions withTint(int rgb) {
        return new FxOptions(normal, up, scale, Optional.of(rgb), parts, volume, pitch);
    }

    public FxOptions only(Parts parts) {
        return new FxOptions(normal, up, scale, tint, parts, volume, pitch);
    }

    public FxOptions withVolume(float volume) {
        return new FxOptions(normal, up, scale, tint, parts, volume, pitch);
    }

    public FxOptions withPitch(float pitch) {
        return new FxOptions(normal, up, scale, tint, parts, volume, pitch);
    }

    private static Vec3 unit(Vec3 direction) {
        return direction.lengthSqr() < 1.0E-8 ? WORLD_UP : direction.normalize();
    }

    private static void requireUnit(String name, Vec3 direction) {
        if (!(Math.abs(direction.lengthSqr() - 1) < 1.0E-4)) {
            throw new IllegalArgumentException("Effect " + name + " " + direction + " is not a unit vector");
        }
    }

    private static void requireRange(String name, float value, float min, float max) {
        if (!(value >= min && value <= max)) {
            throw new IllegalArgumentException("Effect " + name + " " + value + " is outside " + min + ".." + max);
        }
    }

    static final StreamCodec<ByteBuf, Vec3> VEC3 = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, vec -> vec.x,
            ByteBufCodecs.DOUBLE, vec -> vec.y,
            ByteBufCodecs.DOUBLE, vec -> vec.z,
            Vec3::new);

    private static final StreamCodec<ByteBuf, Optional<Integer>> TINT = ByteBufCodecs.optional(ByteBufCodecs.INT);

    /** Written by hand because a composite codec holds at most six fields. */
    public static final StreamCodec<ByteBuf, FxOptions> STREAM_CODEC = StreamCodec.of(
            (wire, options) -> {
                VEC3.encode(wire, options.normal);
                VEC3.encode(wire, options.up);
                wire.writeFloat(options.scale);
                TINT.encode(wire, options.tint);
                wire.writeByte(options.parts.ordinal());
                wire.writeFloat(options.volume);
                wire.writeFloat(options.pitch);
            },
            wire -> new FxOptions(VEC3.decode(wire), VEC3.decode(wire), wire.readFloat(), TINT.decode(wire),
                    Parts.values()[wire.readByte()], wire.readFloat(), wire.readFloat()));
}

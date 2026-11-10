package dev.minecraftfx.client;

import net.minecraft.world.phys.Vec3;
import team.lodestar.lodestone.systems.particle.world.LodestoneWorldParticle;

import java.util.function.Consumer;

/**
 * Steers one particle around an axis through the effect's origin. The particle is tracked as an angle, a distance from
 * the axis and a height along it, so it keeps its radius however long it circles. Each tick this sets the particle's
 * velocity to land exactly on its next point. Setting the position instead would jump, because Minecraft saves the
 * previous position for smoothing only after Lodestone's tick actors have run.
 */
final class Orbit implements Consumer<LodestoneWorldParticle> {
    private final Vec3 centre;
    private final Vec3 axis;
    private final Vec3 side;
    private final Vec3 other;
    /** Radians per tick. */
    private final double turn;
    private final double friction;

    private double angle;
    private double radius;
    private double height;
    private double outwardSpeed;
    private double alongSpeed;

    /**
     * @param start    where the particle is spawned
     * @param velocity its launch velocity: the parts away from and along the axis carry on, slowed by friction; the
     *                 part around the axis is replaced by the orbit
     */
    Orbit(Vec3 centre, Vec3 axis, Vec3 start, Vec3 velocity, double turn, double friction) {
        this.centre = centre;
        this.axis = axis;
        this.side = Layer.side(axis);
        this.other = axis.cross(side);
        this.turn = turn;
        this.friction = friction;

        Vec3 relative = start.subtract(centre);
        height = relative.dot(axis);
        Vec3 flat = relative.subtract(axis.scale(height));
        radius = flat.length();
        // A particle starting on the axis takes its heading from its launch direction instead.
        Vec3 heading = radius > 1.0E-6 ? flat.scale(1 / radius) : flatten(velocity);
        angle = Math.atan2(heading.dot(other), heading.dot(side));
        outwardSpeed = velocity.dot(heading);
        alongSpeed = velocity.dot(axis);
    }

    private Vec3 flatten(Vec3 vector) {
        Vec3 flat = vector.subtract(axis.scale(vector.dot(axis)));
        return flat.lengthSqr() > 1.0E-12 ? flat.normalize() : side;
    }

    /** Moves the particle on by one tick and returns where it should now be. */
    Vec3 step() {
        angle += turn;
        // Inward motion stops at the axis rather than passing through it and flying out the far side.
        radius = Math.max(0, radius + outwardSpeed);
        height += alongSpeed;
        outwardSpeed *= friction;
        alongSpeed *= friction;
        return position();
    }

    /** Where the next {@link #step} will put the particle, without moving it. */
    Vec3 peek() {
        double nextRadius = Math.max(0, radius + outwardSpeed);
        return at(angle + turn, nextRadius, height + alongSpeed);
    }

    Vec3 position() {
        return at(angle, radius, height);
    }

    private Vec3 at(double angle, double radius, double height) {
        return centre.add(axis.scale(height))
                .add(side.scale(Math.cos(angle) * radius))
                .add(other.scale(Math.sin(angle) * radius));
    }

    @Override
    public void accept(LodestoneWorldParticle particle) {
        particle.setParticleSpeed(step().subtract(particle.getParticlePosition()));
    }
}

package dev.minecraftfx;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The play options, and the /fx text that sets them. */
class FxOptionsTest {
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final Vec3 NORTH = new Vec3(0, 0, -1);
    private static final Vec3 EAST = new Vec3(1, 0, 0);

    /** The caller stands east of the effect and looks north. */
    private static final FxOptions FACING_CALLER = FxOptions.facing(EAST);

    private static FxOptions parse(String text) throws CommandSyntaxException {
        return CommandOptions.parse(text, FACING_CALLER, NORTH);
    }

    @Test
    void defaultsAreTheAuthoredEffectStandingUprightWithItsSound() {
        assertEquals(new FxOptions(EAST, UP, 1, Optional.empty(), FxOptions.Parts.BOTH, 1, 1), FACING_CALLER);
    }

    @Test
    void aZeroNormalFacesUp() {
        assertEquals(UP, FxOptions.facing(Vec3.ZERO).normal());
    }

    @Test
    void directionsAreMadeUnitLength() {
        FxOptions options = FxOptions.facing(new Vec3(0, 0, -5)).withUp(new Vec3(3, 0, 0));

        assertEquals(NORTH, options.normal());
        assertEquals(EAST, options.up());
    }

    @Test
    void valuesOutsideTheirLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FACING_CALLER.withScale(11));
        assertThrows(IllegalArgumentException.class, () -> FACING_CALLER.withScale(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> FACING_CALLER.withVolume(-0.1f));
        assertThrows(IllegalArgumentException.class, () -> FACING_CALLER.withPitch(2.5f));
        assertThrows(IllegalArgumentException.class, () -> FACING_CALLER.withTint(0x1FFFFFF));
    }

    @Test
    void partsSayWhichHalvesPlay() {
        assertTrue(FxOptions.Parts.BOTH.visual() && FxOptions.Parts.BOTH.sound());
        assertTrue(FxOptions.Parts.VISUAL.visual() && !FxOptions.Parts.VISUAL.sound());
        assertFalse(FxOptions.Parts.SOUND.visual());
        assertTrue(FxOptions.Parts.SOUND.sound());
    }

    @Test
    void blankTextChangesNothing() throws CommandSyntaxException {
        assertEquals(FACING_CALLER, parse(""));
        assertEquals(FACING_CALLER, parse("  "));
    }

    @Test
    void aBareWordIsATint() throws CommandSyntaxException {
        assertEquals(Optional.of(0xFF5555), parse("red").tint());
        assertEquals(Optional.of(0x102030), parse("#102030").tint());
    }

    @Test
    void optionsApplyInAnyOrder() throws CommandSyntaxException {
        FxOptions expected = FACING_CALLER.withNormal(NORTH).withUp(EAST).withTint(0x55FFFF).withVolume(0.5f).withPitch(1.5f);

        assertEquals(expected, parse("tint=aqua facing=look up=east volume=0.5 pitch=1.5"));
        assertEquals(expected, parse("pitch=1.5  volume=0.5 up=east facing=look aqua"));
    }

    @Test
    void facingMeIsTowardsTheCallerAndLookIsWhereTheyLook() throws CommandSyntaxException {
        assertEquals(EAST, parse("facing=me").normal());
        assertEquals(NORTH, parse("facing=look").normal());
        assertEquals(EAST, parse("up=me").up());
    }

    @Test
    void compassNamesAreWorldDirections() throws CommandSyntaxException {
        assertEquals(new Vec3(0, -1, 0), parse("facing=down").normal());
        assertEquals(new Vec3(0, 0, 1), parse("facing=south").normal());
        assertEquals(new Vec3(-1, 0, 0), parse("up=west").up());
    }

    @Test
    void yawAndPitchAreTheAnglesF3Shows() throws CommandSyntaxException {
        Vec3 straightUp = parse("facing=0,-90").normal();
        Vec3 west = parse("facing=90,0").normal();

        assertEquals(1, straightUp.y, 1.0E-6);
        assertEquals(-1, west.x, 1.0E-6);
    }

    @Test
    void mistakesAreRejected() {
        assertThrows(CommandSyntaxException.class, () -> parse("colour=red"));
        assertThrows(CommandSyntaxException.class, () -> parse("tint=reddish"));
        assertThrows(CommandSyntaxException.class, () -> parse("facing=sideways"));
        assertThrows(CommandSyntaxException.class, () -> parse("facing=90"));
        assertThrows(CommandSyntaxException.class, () -> parse("volume=loud"));
        assertThrows(CommandSyntaxException.class, () -> parse("volume=11"));
        assertThrows(CommandSyntaxException.class, () -> parse("pitch=0.1"));
    }
}

package dev.minecraftfx;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayEffectPayloadTest {
    private static void assertSurvivesTheWire(PlayEffectPayload sent) {
        ByteBuf wire = Unpooled.buffer();

        PlayEffectPayload.STREAM_CODEC.encode(wire, sent);

        assertEquals(sent, PlayEffectPayload.STREAM_CODEC.decode(wire));
        assertEquals(0, wire.readableBytes());
    }

    @Test
    void survivesTheWireAsAuthored() {
        assertSurvivesTheWire(new PlayEffectPayload(ResourceLocation.parse("minecraftfx:impact/critical_spark"),
                new Vec3(29_999_000.25, -63.5, 12.125), FxOptions.facing(new Vec3(0, 0.6, -0.8))));
    }

    @Test
    void survivesTheWireWithEveryOptionChanged() {
        assertSurvivesTheWire(new PlayEffectPayload(ResourceLocation.parse("minecraftfx:ice/frost_nova"), new Vec3(-4.5, 70, 8),
                FxOptions.facing(new Vec3(1, 2, 3)).withUp(new Vec3(0, 0, -1)).withScale(2.5f).withTint(0x33AAFF)
                        .only(FxOptions.Parts.SOUND).withVolume(0.5f).withPitch(1.5f)));
    }
}

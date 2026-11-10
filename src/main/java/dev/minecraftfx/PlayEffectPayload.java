package dev.minecraftfx;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Server to client: play this effect here, this way. The whole network cost of an effect. */
public record PlayEffectPayload(ResourceLocation effect, Vec3 pos, FxOptions options) implements CustomPacketPayload {
    public static final Type<PlayEffectPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MinecraftFx.MOD_ID, "play_effect"));

    public static final StreamCodec<ByteBuf, PlayEffectPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, PlayEffectPayload::effect,
            FxOptions.VEC3, PlayEffectPayload::pos,
            FxOptions.STREAM_CODEC, PlayEffectPayload::options,
            PlayEffectPayload::new);

    @Override
    public Type<PlayEffectPayload> type() {
        return TYPE;
    }
}

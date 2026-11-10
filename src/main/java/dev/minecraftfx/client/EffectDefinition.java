package dev.minecraftfx.client;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.minecraftfx.FxOptions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One effect: a small named palette plus the layers that draw with it. Layers only ever name palette entries, so
 * recolouring an effect means changing the palette and nothing else, either in the file or at play time with a tint.
 * The sounds are the effect's default soundtrack; an effect with none is silent.
 */
public record EffectDefinition(List<SoundCue> sounds, Map<String, Integer> palette, List<Layer> layers) {
    /** "#RRGGBB" or a vanilla colour name such as "gold". */
    private static final Codec<Integer> COLOR = TextColor.CODEC.xmap(TextColor::getValue, TextColor::fromRgb);

    public static final Codec<EffectDefinition> CODEC = RecordCodecBuilder.<EffectDefinition>create(instance -> instance.group(
            SoundCue.LIST_CODEC.optionalFieldOf("sounds", List.of()).forGetter(EffectDefinition::sounds),
            Codec.unboundedMap(Codec.STRING, COLOR).fieldOf("palette").forGetter(EffectDefinition::palette),
            Layer.CODEC.listOf(1, 32).fieldOf("layers").forGetter(EffectDefinition::layers)
    ).apply(instance, EffectDefinition::new)).validate(EffectDefinition::validate);

    private DataResult<EffectDefinition> validate() {
        for (Layer layer : layers) {
            for (String name : layer.color()) {
                if (!palette.containsKey(name)) {
                    return DataResult.error(() -> "Layer uses colour '" + name + "' which is not in the palette " + palette.keySet());
                }
            }
        }
        return DataResult.success(this);
    }

    /** Plays the picture, the sounds or both, as the options ask. */
    public void play(ClientLevel level, Vec3 pos, FxOptions options) {
        if (options.parts().visual()) {
            Map<String, Integer> colors = options.tint().map(this::tinted).orElse(palette);
            for (Layer layer : layers) {
                layer.emit(level, pos, options.normal(), options.up(), options.scale(), colors, options.force());
            }
        }
        if (options.parts().sound()) {
            for (SoundCue sound : sounds) {
                sound.play(pos, options.volume(), options.pitch(), level.random);
            }
        }
    }

    Map<String, Integer> tinted(int tint) {
        float brightest = palette.values().stream().map(EffectDefinition::luma).max(Float::compare).orElseThrow();
        Map<String, Integer> colors = new HashMap<>();
        palette.forEach((name, rgb) -> colors.put(name, tint(rgb, tint, brightest)));
        return colors;
    }

    /**
     * Shades the tint by how bright {@code rgb} is next to the palette's brightest colour, so that colour becomes exactly
     * the tint and the rest become darker shades of it in the same order. A dark tint therefore darkens the whole effect,
     * and black makes it black. Brightness is luma, not HSV value, because white, yellow and orange all share HSV value 1
     * and would collapse into one shade.
     *
     * @param brightest the luma of the palette's brightest colour
     */
    static int tint(int rgb, int tint, float brightest) {
        float shade = brightest == 0 ? 0 : Math.min(1, luma(rgb) / brightest);
        int r = Math.round((tint >> 16 & 255) * shade);
        int g = Math.round((tint >> 8 & 255) * shade);
        int b = Math.round((tint & 255) * shade);
        return r << 16 | g << 8 | b;
    }

    /** Perceived brightness from 0 to 1 (ITU-R BT.601 weights). */
    static float luma(int rgb) {
        return (0.299f * (rgb >> 16 & 255) + 0.587f * (rgb >> 8 & 255) + 0.114f * (rgb & 255)) / 255f;
    }
}

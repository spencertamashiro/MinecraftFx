package dev.minecraftfx.client;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;

/**
 * One sound an effect plays alongside its picture. An effect lists any number of these under "sounds".
 *
 * @param id       a sound event from any sounds.json: one of this mod's, vanilla's or a resource pack's
 * @param category the volume slider that controls it, by its /playsound name
 * @param volume   0 to 10; above 1 is no louder up close but carries further
 * @param pitch    {@code [n]}, or {@code [min, max]} to vary each play a little; Minecraft clamps the result to 0.5..2
 * @param delay    ticks to wait after the effect starts
 */
public record SoundCue(ResourceLocation id, SoundSource category, float volume, Layer.Range pitch, int delay) {
    private static final Codec<SoundSource> CATEGORY = Codec.STRING.comapFlatMap(
            name -> Arrays.stream(SoundSource.values()).filter(source -> source.getName().equals(name)).findFirst()
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Unknown sound category '" + name + "': use one of "
                            + Arrays.stream(SoundSource.values()).map(SoundSource::getName).toList())),
            SoundSource::getName);

    private static final Layer.Range UNCHANGED = new Layer.Range(1, 1);

    static final Codec<SoundCue> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(SoundCue::id),
            CATEGORY.optionalFieldOf("category", SoundSource.PLAYERS).forGetter(SoundCue::category),
            Codec.floatRange(0, 10).optionalFieldOf("volume", 1f).forGetter(SoundCue::volume),
            Layer.Range.CODEC.optionalFieldOf("pitch", UNCHANGED).forGetter(SoundCue::pitch),
            Codec.intRange(0, 200).optionalFieldOf("delay", 0).forGetter(SoundCue::delay)
    ).apply(instance, SoundCue::new));

    static final Codec<List<SoundCue>> LIST_CODEC = CODEC.listOf(0, 8);

    /** @param volume and {@code pitch} multiply the cue's own */
    void play(Vec3 pos, float volume, float pitch, RandomSource random) {
        SoundInstance sound = new SimpleSoundInstance(id, category, this.volume * volume, this.pitch.sample(random) * pitch,
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR, pos.x, pos.y, pos.z, false);
        SoundManager manager = Minecraft.getInstance().getSoundManager();
        if (delay == 0) {
            manager.play(sound);
        } else {
            manager.playDelayed(sound, delay);
        }
    }
}

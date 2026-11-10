package dev.minecraftfx.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import dev.minecraftfx.MinecraftFx;
import dev.minecraftfx.PlayEffectPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import org.slf4j.Logger;
import team.lodestar.lodestone.systems.particle.world.type.LodestoneWorldParticleType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The effect registry. Effects are client resources at assets/NAMESPACE/fx/CATEGORY/NAME.json, so adding one needs no
 * Java, and F3+T reloads them in a running game.
 */
@Mod(value = MinecraftFx.MOD_ID, dist = Dist.CLIENT)
public final class EffectLibrary {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Map<ResourceLocation, EffectDefinition> effects = Map.of();

    public EffectLibrary(IEventBus modBus) {
        modBus.addListener(RegisterParticleProvidersEvent.class, event -> MinecraftFx.PARTICLES.getEntries().forEach(
                sheet -> event.registerSpriteSet((LodestoneWorldParticleType) sheet.get(), LodestoneWorldParticleType.Factory::new)));
        modBus.addListener(RegisterClientReloadListenersEvent.class, event -> event.registerReloadListener(
                new SimpleJsonResourceReloadListener(new Gson(), "fx") {
                    @Override
                    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
                        load(files);
                    }
                }));
    }

    private static void load(Map<ResourceLocation, JsonElement> files) {
        Map<ResourceLocation, EffectDefinition> loaded = new HashMap<>();
        files.forEach((id, json) -> EffectDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                .ifSuccess(effect -> loaded.put(id, effect))
                .ifError(error -> LOGGER.error("Skipping effect {}: {}", id, error.message())));
        effects = Map.copyOf(loaded);
        LOGGER.info("Loaded {} of {} effect files", effects.size(), files.size());
    }

    /** Every loaded effect id, for tab completion of /fx. */
    public static Set<ResourceLocation> ids() {
        return effects.keySet();
    }

    public static void play(PlayEffectPayload payload) {
        EffectDefinition effect = effects.get(payload.effect());
        ClientLevel level = Minecraft.getInstance().level;
        if (effect == null) {
            LOGGER.warn("Server asked for unknown effect {}", payload.effect());
        } else if (level != null) {
            effect.play(level, payload.pos(), payload.options());
        }
    }
}

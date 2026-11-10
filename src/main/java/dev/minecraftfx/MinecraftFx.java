package dev.minecraftfx;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.minecraftfx.client.EffectLibrary;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import team.lodestar.lodestone.systems.particle.world.type.LodestoneWorldParticleType;

import java.util.List;
import java.util.Optional;

@Mod(MinecraftFx.MOD_ID)
public final class MinecraftFx {
    public static final String MOD_ID = "minecraftfx";

    /**
     * One particle type per sprite sheet. Each name needs assets/minecraftfx/particles/NAME.json and its frames in
     * textures/particle/. Effects reference a sheet as "minecraftfx:NAME"; Lodestone's own sheets work too.
     */
    private static final List<String> SPRITE_SHEETS = List.of(
            "flash", "ring", "shard", "pixel", "smoke", "orb", "bolt", "bubble", "leaf", "heart", "rune", "slash",
            "snowflake", "coin", "plus", "drop", "feather", "skull", "glyph",
            "puff", "flame", "rock", "star", "glint", "hex", "question", "exclaim", "zzz", "eyes", "chain", "crescent",
            "reticle", "shield", "silence", "spiral", "petal", "bug", "wings", "cloud", "digit", "grid", "wave", "ghost",
            "mushroom", "gem", "flask", "crack", "anger", "box", "gear", "dagger", "crest", "coral", "xslash", "key",
            "splat", "block", "screen");

    public static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(Registries.PARTICLE_TYPE, MOD_ID);

    static {
        SPRITE_SHEETS.forEach(sheet -> PARTICLES.register(sheet, LodestoneWorldParticleType::new));
    }

    /** Vanilla drops unforced particles beyond 32 blocks of the camera; the extra 8 covers third-person cameras. */
    private static final double SEND_RADIUS = 40;

    /**
     * Suggests effect ids. Registered by name, so each client fills it from its own loaded effects: the server cannot
     * list them, because effects are client resources. Set during common setup, which always precedes command registration.
     */
    private static SuggestionProvider<CommandSourceStack> effectIds;

    public MinecraftFx(IEventBus modBus) {
        PARTICLES.register(modBus);
        modBus.addListener(MinecraftFx::registerPayloads);
        modBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(() -> effectIds = SuggestionProviders.register(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "effects"),
                // Only a client ever runs this; the dist check keeps a dedicated server from loading client classes.
                (context, builder) -> FMLEnvironment.dist.isClient()
                        ? SharedSuggestionProvider.suggestResource(EffectLibrary.ids(), builder)
                        : builder.buildFuture())));
        NeoForge.EVENT_BUS.addListener(MinecraftFx::registerCommands);
    }

    /** Plays an effect, picture and sound, at its authored size and colours. */
    public static void play(ServerLevel level, ResourceLocation effect, Vec3 pos, Vec3 normal) {
        play(level, effect, pos, FxOptions.facing(normal));
    }

    /** Plays an effect, picture and sound, at another size or colour. See {@link FxOptions} for {@code scale} and {@code tint}. */
    public static void play(ServerLevel level, ResourceLocation effect, Vec3 pos, Vec3 normal, float scale, Optional<Integer> tint) {
        FxOptions options = FxOptions.facing(normal).withScale(scale);
        play(level, effect, pos, tint.map(options::withTint).orElse(options));
    }

    /**
     * Plays an effect for every player near {@code pos}. This is the whole server cost of an effect: one small packet
     * per nearby player. Nothing is simulated or ticked server-side, and the effect is purely cosmetic.
     *
     * @param effect  id of assets/NAMESPACE/fx/PATH.json
     * @param options direction, size, colour, and whether the picture, the sound or both play
     */
    public static void play(ServerLevel level, ResourceLocation effect, Vec3 pos, FxOptions options) {
        PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, SEND_RADIUS, new PlayEffectPayload(effect, pos, options));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // A lambda rather than a method reference, so the client-only class loads only when a client receives a packet.
        event.registrar("3").playToClient(PlayEffectPayload.TYPE, PlayEffectPayload.STREAM_CODEC,
                (payload, context) -> EffectLibrary.play(payload));
    }

    /**
     * /fx play|visual|sound EFFECT [POS [SCALE [OPTIONS]]]. "play" is the picture with its sound, "visual" the picture
     * alone and "sound" the sound alone. Without a position the effect plays three blocks in front of the caller, and
     * it faces the caller unless the options say otherwise. OPTIONS are described in {@link CommandOptions}.
     */
    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fx").requires(source -> source.hasPermission(2))
                .then(mode("play", FxOptions.Parts.BOTH))
                .then(mode("visual", FxOptions.Parts.VISUAL))
                .then(mode("sound", FxOptions.Parts.SOUND)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mode(String name, FxOptions.Parts parts) {
        return Commands.literal(name).then(Commands.argument("effect", ResourceLocationArgument.id()).suggests(effectIds)
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    Vec3 ahead = eyes(source).add(Vec3.directionFromRotation(source.getRotation()).scale(3));
                    return run(context, parts, ahead, 1, "");
                })
                .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(context -> run(context, parts, Vec3Argument.getVec3(context, "pos"), 1, ""))
                        .then(Commands.argument("scale", FloatArgumentType.floatArg(FxOptions.MIN_SCALE, FxOptions.MAX_SCALE))
                                .executes(context -> run(context, parts, Vec3Argument.getVec3(context, "pos"),
                                        FloatArgumentType.getFloat(context, "scale"), ""))
                                .then(Commands.argument("options", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> CommandOptions.suggest(builder))
                                        .executes(context -> run(context, parts, Vec3Argument.getVec3(context, "pos"),
                                                FloatArgumentType.getFloat(context, "scale"),
                                                StringArgumentType.getString(context, "options")))))));
    }

    private static int run(CommandContext<CommandSourceStack> context, FxOptions.Parts parts, Vec3 pos, float scale, String optionText)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ResourceLocation effect = ResourceLocationArgument.getId(context, "effect");
        FxOptions facingCaller = FxOptions.facing(eyes(source).subtract(pos)).withScale(scale).only(parts);
        play(source.getLevel(), effect, pos,
                CommandOptions.parse(optionText, facingCaller, Vec3.directionFromRotation(source.getRotation())));
        source.sendSuccess(() -> Component.translatable("commands.minecraftfx.play", effect.toString()), false);
        return 1;
    }

    private static Vec3 eyes(CommandSourceStack source) {
        Entity entity = source.getEntity();
        return entity != null ? entity.getEyePosition() : source.getPosition();
    }
}

package dev.minecraftfx;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic3CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * The trailing options of /fx: space-separated {@code key=value} pairs in any order, e.g.
 * {@code tint=red facing=look up=north volume=0.5 pitch=1.2 force=true}. A bare word is a tint, so {@code /fx play ID ~ ~ ~ 2 red}
 * keeps working.
 */
final class CommandOptions {
    private static final List<String> KEYS = List.of("tint", "facing", "up", "volume", "pitch", "force");

    private static final Map<String, Vec3> COMPASS = Map.of(
            "up", new Vec3(0, 1, 0), "down", new Vec3(0, -1, 0),
            "north", new Vec3(0, 0, -1), "south", new Vec3(0, 0, 1),
            "east", new Vec3(1, 0, 0), "west", new Vec3(-1, 0, 0));
    /** In the order they are suggested. */
    private static final List<String> DIRECTIONS = List.of("me", "look", "up", "down", "north", "south", "east", "west");

    private static final DynamicCommandExceptionType BAD_OPTION = new DynamicCommandExceptionType(
            key -> Component.translatable("commands.minecraftfx.bad_option", key));
    private static final DynamicCommandExceptionType BAD_TINT = new DynamicCommandExceptionType(
            text -> Component.translatable("commands.minecraftfx.bad_tint", text));
    private static final DynamicCommandExceptionType BAD_DIRECTION = new DynamicCommandExceptionType(
            text -> Component.translatable("commands.minecraftfx.bad_direction", text));
    private static final DynamicCommandExceptionType BAD_SWITCH = new DynamicCommandExceptionType(
            text -> Component.translatable("commands.minecraftfx.bad_switch", text));
    private static final Dynamic3CommandExceptionType BAD_NUMBER = new Dynamic3CommandExceptionType(
            (text, min, max) -> Component.translatable("commands.minecraftfx.bad_number", text, min, max));

    private CommandOptions() {
    }

    /**
     * @param base what to play when no option says otherwise; its normal is what the direction "me" means
     * @param look the direction "look" means: the way the caller is looking
     */
    static FxOptions parse(String text, FxOptions base, Vec3 look) throws CommandSyntaxException {
        FxOptions options = base;
        for (String token : text.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue; // blank text: a trailing space after the scale
            }
            int equals = token.indexOf('=');
            String key = equals < 0 ? "tint" : token.substring(0, equals);
            String value = token.substring(equals + 1);
            options = switch (key) {
                case "tint" -> options.withTint(TextColor.parseColor(value).getOrThrow(error -> BAD_TINT.create(value)).getValue());
                case "facing" -> options.withNormal(direction(value, base.normal(), look));
                case "up" -> options.withUp(direction(value, base.normal(), look));
                case "volume" -> options.withVolume(number(value, 0, FxOptions.MAX_VOLUME));
                case "pitch" -> options.withPitch(number(value, FxOptions.MIN_PITCH, FxOptions.MAX_PITCH));
                case "force" -> options.withForce(switch (value) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> throw BAD_SWITCH.create(value);
                });
                default -> throw BAD_OPTION.create(key);
            };
        }
        return options;
    }

    /** A name from {@link #DIRECTIONS}, or "YAW,PITCH" in degrees as F3 shows them. */
    private static Vec3 direction(String text, Vec3 towardsCaller, Vec3 look) throws CommandSyntaxException {
        if (text.equals("me")) {
            return towardsCaller;
        }
        if (text.equals("look")) {
            return look;
        }
        Vec3 named = COMPASS.get(text);
        if (named != null) {
            return named;
        }
        String[] angles = text.split(",");
        if (angles.length != 2) {
            throw BAD_DIRECTION.create(text);
        }
        try {
            return Vec3.directionFromRotation(Float.parseFloat(angles[1]), Float.parseFloat(angles[0]));
        } catch (NumberFormatException notAngles) {
            throw BAD_DIRECTION.create(text);
        }
    }

    private static float number(String text, float min, float max) throws CommandSyntaxException {
        try {
            float value = Float.parseFloat(text);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException notANumber) {
            // reported below, like a number out of range
        }
        throw BAD_NUMBER.create(text, min, max);
    }

    /** Completes the word being typed: option names first, then the values of the option once its "=" is typed. */
    static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        int wordStart = typed.lastIndexOf(' ') + 1;
        String word = typed.substring(wordStart);
        int equals = word.indexOf('=');
        Stream<String> colours = Arrays.stream(ChatFormatting.values()).filter(ChatFormatting::isColor).map(ChatFormatting::getName);
        Stream<String> candidates;
        if (equals < 0) {
            candidates = Stream.concat(KEYS.stream().map(key -> key + "="), colours);
        } else {
            String key = word.substring(0, equals);
            Stream<String> values = switch (key) {
                case "tint" -> colours;
                case "facing", "up" -> DIRECTIONS.stream();
                case "force" -> Stream.of("true", "false");
                default -> Stream.empty();
            };
            candidates = values.map(value -> key + "=" + value);
        }
        return SharedSuggestionProvider.suggest(candidates, builder.createOffset(builder.getStart() + wordStart));
    }
}

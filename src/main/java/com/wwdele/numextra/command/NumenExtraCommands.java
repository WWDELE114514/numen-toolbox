package com.wwdele.numextra.command;

import com.dwinovo.numen.entity.NumenPlayer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /numen op <名字> [等级]} 与 {@code /numen deop <名字>}。
 *
 * <p>挂在 {@code /numen} 根下,和原版 {@code /op} 一样要权限等级 3 才能执行;效果也一样——把这只同伴的
 * {@code GameProfile} 加进服务器的 op 列表。于是她经 {@code numen.mc.run} 执行指令时,服务器按原版那套权限等级放行,
 * 不需要任何额外的判断。等级默认 4(服务器管理),也可以给 1~4。
 *
 * <p>名字要在线的同伴里找:op 列表按 {@code GameProfile} 存,得先拿到她的 profile。
 */
public final class NumenExtraCommands {

    /** 原版 {@code /op} 默认给的等级。 */
    private static final int DEFAULT_LEVEL = 4;

    private NumenExtraCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> op() {
        return Commands.literal("op")
                .requires(source -> source.hasPermission(3))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(NumenExtraCommands::suggestCompanions)
                        .executes(context -> setOp(context, DEFAULT_LEVEL))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 4))
                                .executes(context -> setOp(context,
                                        IntegerArgumentType.getInteger(context, "level")))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> deop() {
        return Commands.literal("deop")
                .requires(source -> source.hasPermission(3))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(NumenExtraCommands::suggestCompanions)
                        .executes(NumenExtraCommands::deop));
    }

    private static int setOp(CommandContext<CommandSourceStack> context, int level) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        NumenPlayer her = find(source.getServer(), name);
        if (her == null) {
            source.sendFailure(Component.literal("there is no companion named '" + name + "' online"));
            return 0;
        }
        source.getServer().getPlayerList().getOps()
                .add(new ServerOpListEntry(her.getGameProfile(), level, false));
        source.sendSuccess(() -> Component.literal("gave " + her.getName().getString()
                + " operator level " + level), true);
        return 1;
    }

    private static int deop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        NumenPlayer her = find(source.getServer(), name);
        if (her == null) {
            source.sendFailure(Component.literal("there is no companion named '" + name + "' online"));
            return 0;
        }
        source.getServer().getPlayerList().getOps().remove(her.getGameProfile());
        source.sendSuccess(() -> Component.literal(her.getName().getString() + " is no longer an operator"), true);
        return 1;
    }

    private static NumenPlayer find(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer her && her.getName().getString().equalsIgnoreCase(name)) {
                return her;
            }
        }
        return null;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestCompanions(
            CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer) {
                names.add(player.getName().getString());
            }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }
}

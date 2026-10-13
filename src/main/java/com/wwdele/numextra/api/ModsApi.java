package com.wwdele.numextra.api;

import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code toolbox.mods}:服务器装了哪些模组。
 *
 * <p>她"看到"一个模组世界的入口:先知道有什么模组,再去 {@code toolbox.items.search} 搜里面的东西。
 * 三个函数都在服务端当场读、当场回,不动世界。
 */
public final class ModsApi {

    /** 不写上限时最多给几条。 */
    private static final int DEFAULT_LIMIT = 100;
    /** 一次最多给几条,免得把模型的上下文塞满。 */
    private static final int MAX_LIMIT = 500;

    private ModsApi() {}

    /** 一个加载着的模组。 */
    @Doc("A mod loaded on this server.")
    public record Mod(@Doc("Its id, superbwarfare.") String id,
                      @Doc("Its display name.") String name,
                      @Doc("Its version.") String version) {}

    /** 要几条。 */
    public record ListArgs(@Doc("At most this many results (1-" + MAX_LIMIT + ").")
                           @Omitted("" + DEFAULT_LIMIT) Optional<Integer> limit) {}

    @Fn("List every mod loaded on this server, by id.")
    @Example("for _, m in ipairs(toolbox.mods.list()) do print(m.id, m.name, m.version) end")
    @Note("Instant and read-only. Use it to see what a modded world offers before searching its items.")
    @SeeAlso({"toolbox.mods.has", "toolbox.mods.search", "toolbox.items.search"})
    public static List<Mod> list(ServerCall call, ListArgs args) {
        int limit = Math.clamp((long) args.limit().orElse(DEFAULT_LIMIT), 1, MAX_LIMIT);
        List<Mod> out = new ArrayList<>();
        for (IModInfo info : ModList.get().getMods()) {
            out.add(row(info));
            if (out.size() >= limit) {
                break;
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    /** 查哪个模组。 */
    public record Has(@Doc("The mod id to look for, superbwarfare.") String mod) {}

    @Fn("Whether a mod with this id is loaded.")
    @Example("if toolbox.mods.has(\"superbwarfare\") then print(\"warfare is here\") end")
    @Note("Instant and read-only.")
    @SeeAlso("toolbox.mods.list")
    public static boolean has(ServerCall call, Has args) {
        return ModList.get().isLoaded(args.mod());
    }

    /** 搜什么。 */
    public record Search(@Doc("Keyword matched against the mod id and name, case-insensitive.") String keyword) {}

    @Fn("Find loaded mods whose id or name contains a keyword.")
    @Example("for _, m in ipairs(toolbox.mods.search(\"warfare\")) do print(m.id) end")
    @Note("Instant and read-only.")
    @SeeAlso({"toolbox.mods.list", "toolbox.mods.has"})
    public static List<Mod> search(ServerCall call, Search args) {
        String keyword = args.keyword().toLowerCase(Locale.ROOT).strip();
        List<Mod> out = new ArrayList<>();
        for (IModInfo info : ModList.get().getMods()) {
            if (info.getModId().toLowerCase(Locale.ROOT).contains(keyword)
                    || info.getDisplayName().toLowerCase(Locale.ROOT).contains(keyword)) {
                out.add(row(info));
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    private static Mod row(IModInfo info) {
        return new Mod(info.getModId(), info.getDisplayName(), info.getVersion().toString());
    }
}

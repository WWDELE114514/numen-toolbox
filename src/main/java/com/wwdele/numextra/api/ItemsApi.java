package com.wwdele.numextra.api;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code toolbox.items}:跨模组搜索物品。
 *
 * <p>她知道自己装了哪些模组({@code toolbox.mods})之后,从这里按关键词找到具体的物品 id,再把 id 交给
 * {@code numen.gear.hold} / {@code numen.use.*} 去用。三个函数都在服务端当场读、当场回,不动世界。
 */
public final class ItemsApi {

    /** 不写上限时最多给几条。 */
    private static final int DEFAULT_LIMIT = 60;
    /** 一次最多给几条,免得把模型的上下文塞满。 */
    private static final int MAX_LIMIT = 500;

    private ItemsApi() {}

    /** 服务器上存在的一种物品。 */
    @Doc("One item that exists on this server.")
    public record Entry(@Doc("Its id, superbwarfare:ak_47.") String id,
                        @Doc("Its display name.") String name,
                        @Doc("The mod it comes from.") String mod,
                        @Doc("Its max stack size.") int maxStack) {}

    /** 搜什么、在哪个模组里搜、要几条。 */
    public record Search(@Doc("Keyword matched against the id and name, case-insensitive; empty lists the mod's items.")
                         String keyword,
                         @Doc("Only this mod's items.") @Omitted("every mod")
                         Optional<String> mod,
                         @Doc("At most this many results (1-" + MAX_LIMIT + ").")
                         @Omitted("" + DEFAULT_LIMIT) Optional<Integer> limit) {}

    @Fn("Search items across every loaded mod by keyword.")
    @Example("for _, it in ipairs(toolbox.items.search(\"rifle\")) do print(it.id, it.name, it.mod) end")
    @Example("toolbox.items.search(\"ammo\", {mod = \"superbwarfare\", limit = 20})")
    @Note("Instant and read-only. Matches the id (namespace and path) and the display name, case-insensitive; an empty "
            + "keyword lists everything in the chosen mod. Each result carries the exact id to hold or use.")
    @Note("Hand the id on: numen.gear.hold(it.id) takes it in hand, numen.use.item uses it, toolbox.items.info(it.id) "
            + "explains one.")
    @SeeAlso({"toolbox.items.info", "toolbox.items.of_mod", "toolbox.mods.list"})
    public static List<Entry> search(ServerCall call, Search args) {
        String keyword = args.keyword().toLowerCase(Locale.ROOT).strip();
        String mod = args.mod().map(s -> s.toLowerCase(Locale.ROOT).strip()).orElse(null);
        int limit = Math.clamp((long) args.limit().orElse(DEFAULT_LIMIT), 1, MAX_LIMIT);
        List<Entry> out = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (mod != null && !key.getNamespace().equals(mod)) {
                continue;
            }
            if (!keyword.isEmpty()) {
                // 先看 id:命中就不必再解析组件取显示名(大注册表上这一步不便宜)
                String id = key.toString().toLowerCase(Locale.ROOT);
                if (!id.contains(keyword)) {
                    String name = new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
                    if (!name.contains(keyword)) {
                        continue;
                    }
                }
            }
            out.add(row(item, key));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** 查哪一件。 */
    public record Info(@Doc("The item id, minecraft:elytra or superbwarfare:ak_47.") String id) {}

    @Fn("One item by its id: name, mod, stack size.")
    @Example("toolbox.items.info(\"superbwarfare:ak_47\")")
    @Example("toolbox.items.info(\"minecraft:elytra\")")
    @Note("Instant and read-only. Fails with not_found when no item has that id.")
    @SeeAlso({"toolbox.items.search", "toolbox.items.of_mod"})
    public static Entry info(ServerCall call, Info args) {
        ResourceLocation key = ResourceLocation.tryParse(
                args.id().contains(":") ? args.id() : "minecraft:" + args.id());
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            throw new ApiError(ErrorKind.NOT_FOUND, "there is no item '" + args.id() + "'",
                    Call.of("toolbox.items.search", args.id()));
        }
        return row(BuiltInRegistries.ITEM.get(key), key);
    }

    /** 哪个模组的、要几条。 */
    public record OfMod(@Doc("The mod id, superbwarfare.") String mod,
                        @Doc("At most this many results (1-" + MAX_LIMIT + ").")
                        @Omitted("200") Optional<Integer> limit) {}

    @Fn("Every item a mod adds.")
    @Example("for _, it in ipairs(toolbox.items.of_mod(\"superbwarfare\")) do print(it.id) end")
    @Note("Instant and read-only. An unknown mod gives an empty table.")
    @SeeAlso({"toolbox.items.search", "toolbox.items.info"})
    public static List<Entry> ofMod(ServerCall call, OfMod args) {
        String mod = args.mod().toLowerCase(Locale.ROOT).strip();
        int limit = Math.clamp((long) args.limit().orElse(200), 1, MAX_LIMIT);
        List<Entry> out = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key.getNamespace().equals(mod)) {
                out.add(row(item, key));
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    private static Entry row(Item item, ResourceLocation key) {
        ItemStack stack = new ItemStack(item);
        return new Entry(key.toString(), stack.getHoverName().getString(), key.getNamespace(),
                stack.getMaxStackSize());
    }
}

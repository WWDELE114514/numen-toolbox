package com.wwdele.numextra.pack;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
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
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code toolbox.backpack}:精妙背包(Sophisticated Backpacks)里的东西。
 *
 * <p>精妙背包的内容和原版容器不一样:原版容器把东西写在物品自己的 {@code container} 组件里,精妙背包只把内容按
 * {@link UUID} 存进世界的一份存档数据({@code SophisticatedBackpacks:backpack_storage})里——所以
 * {@code toolbox.items.search} 能找到背包这件物品,却读不出它装了什么,原版的任何"读容器"也看不见它。
 *
 * <p>这一组就在服务端当面向背包要它的存储,把她"拿着的、穿在身上的、开着的"背包里的东西读出来、放进去、拿出来。读写都不经过
 * 界面,所以不需要按键、不需要走路,也不会因为背包不在手上而失败。
 */
public final class BackpackApi {

    private BackpackApi() {}

    /** 背包空着时也读一下:空表就是空表。 */
    private static final int MAX_LIST = 200;

    /** 一个背包里的一样东西。 */
    @Doc("One kind of item inside one backpack.")
    public record BackpackItem(@Doc("The item id, minecraft:iron_ingot.") String item,
                        @Doc("Its display name.") String name,
                        @Doc("How many of it in this backpack.") int count,
                        @Doc("The slot it first sits in.") int slot) {}

    /** 一个背包的概况。 */
    @Doc("One backpack.")
    public record BackpackInfo(@Doc("Its item id, sophisticatedbackpacks:backpack.") String id,
                       @Doc("Its display name.") String name,
                       @Doc("Where it is: held, worn or open.") String where,
                       @Doc("How many storage slots it has.") int slots,
                       @Doc("How many of them are filled.") int filled,
                       @Doc("Whether it has an upgrade slot taken.") boolean upgraded) {}

    /** 读哪一个背包:手上拿的、穿在身上的、界面上开着的;不写就是手上拿的。 */
    public record FromWhere(@Doc("held the one in your hand, worn the one on your chest, open the one whose window you "
                             + "have open.")
                        @Omitted("held") Optional<String> where) {}

    /** 装什么、装哪一件、往哪个背包、装几件。 */
    public record Stow(@Doc("The item to put in.")
                      Item item,
                      @Doc("Which backpack: held, worn or open.")
                      @Omitted("held") Optional<String> where,
                      @Doc("How many to put in.")
                      @Omitted("all of them you carry") Optional<Integer> count) {}

    /** 拿什么、从哪个背包、拿几件。 */
    public record Fetch(@Doc("The item to take out.")
                       Item item,
                       @Doc("Which backpack: held, worn or open.")
                       @Omitted("held") Optional<String> where,
                       @Doc("How many to take out.")
                       @Omitted("take all of them") Optional<Integer> count) {}

    /** 拿了几件。 */
    @Doc("How many items went in or came out.")
    public record Packed(@Doc("How many went in or came out.") int moved,
                        @Doc("How many it could not do, none left inside when taking, no room when putting.")
                        int left) {}

    /** 她此刻的三个背包各是什么:手上、身上、界面上开着的。 */
    @Fn("Which backpacks you have to hand: the one in your hand, the one you wear and the one whose window is open.")
    @Example("for _, p in ipairs(toolbox.backpack.packs()) do print(p.where, p.id, p.slots, p.filled) end")
    @Note("Instant and read-only. Empty means you have none there: no backpack in hand, none worn, no window open. "
            + "A backpack you wear also has its window open while you sort it.")
    @Note("精妙背包的存储按 UUID 存在世界里,不在物品自身,所以 numen.gui.view 与 toolbox.items 都读不到它;这一组"
            + "从精妙背包自己要出它的存储来读。")
    @SeeAlso({"toolbox.backpack.items", "toolbox.backpack.put", "toolbox.backpack.take", "toolbox.items.search"})
    public static List<BackpackInfo> packs(ServerCall call) {
        NumenPlayer her = call.her();
        List<BackpackInfo> out = new ArrayList<>();
        held(her).ifPresent(wrapper -> out.add(row(wrapper, "held")));
        worn(her).ifPresent(wrapper -> out.add(row(wrapper, "worn")));
        open(her).ifPresent(wrapper -> out.add(row(wrapper, "open")));
        return out;
    }

    /** 一个背包里有什么。 */
    @Fn("What one backpack holds, one entry per kind of item.")
    @Example("for _, e in ipairs(toolbox.backpack.items()) do print(e.item, e.count, e.slot) end")
    @Example("for _, e in ipairs(toolbox.backpack.items({where = \"worn\"})) do print(e.item, e.count) end")
    @Note("Instant and read-only. Empty means an empty backpack; pick where with held, worn or open. Slots are the "
            + "backpack's own storage slots, not your inventory's.")
    @SeeAlso({"toolbox.backpack.packs", "numen.inv.items", "toolbox.backpack.take"})
    public static List<BackpackItem> items(ServerCall call, FromWhere args) {
        InventoryHandler handler = handler(call.her(), args.where());
        List<BackpackItem> out = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots() && out.size() < MAX_LIST; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                out.add(new BackpackItem(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getHoverName().getString(), stack.getCount(), slot));
            }
        }
        return out;
    }

    @Fn("Put items from your inventory into one of your backpacks.")
    @Example("toolbox.backpack.put(\"minecraft:cobblestone\", {where = \"worn\"})")
    @Example("toolbox.backpack.put(\"minecraft:raw_iron\", {count = 16})")
    @Note("Stops when the backpack is full or you carry no more; the leftovers stay in your inventory. "
            + "Upgrades raise the limit of what a single slot takes.")
    @Note("Fails with not_found when you carry none of it, and with no_material when that backpack will not take it.")
    @SeeAlso({"toolbox.backpack.take", "toolbox.backpack.packs", "numen.inv.items"})
    public static Packed put(ServerCall call, Stow args) {
        InventoryHandler handler = handler(call.her(), args.where());
        int left = args.count().orElse(Integer.MAX_VALUE);
        int moved = 0;
        for (ItemStack carried : carriedStacks(call.her(), args.item())) {
            if (moved >= left) {
                break;
            }
            int want = left - moved;
            ItemStack piece = carried.copy();
            piece.setCount(Math.min(piece.getCount(), want));
            ItemStack rest = handler.insertItem(piece, false);
            int went = piece.getCount() - rest.getCount();
            if (went <= 0) {
                continue;
            }
            moved += went;
            carried.shrink(went);
        }
        handler.saveInventory();
        if (moved == 0) {
            boolean carries = carriedCount(call.her(), args.item()) > 0;
            throw new ApiError(carries ? ErrorKind.NO_MATERIAL : ErrorKind.NOT_FOUND,
                    carries ? "that backpack has no room for " + name(args.item())
                            : "you carry none of " + name(args.item()),
                    carries ? Call.of("toolbox.backpack.packs") : Call.of("toolbox.items.search", name(args.item())));
        }
        return new Packed(moved, Math.max(0, carriedCount(call.her(), args.item())));
    }

    @Fn("Take items out of one of your backpacks into your inventory.")
    @Example("toolbox.backpack.take(\"minecraft:torch\", {where = \"worn\", count = 32})")
    @Example("toolbox.backpack.take(\"minecraft:dried_kelp\")")
    @Note("Stops when the backpack has none left of it, or when your own inventory is full. "
            + "Taking something out of a backpack you do not own asks your owner first.")
    @Note("Fails with not_found when that backpack holds none of it.")
    @SeeAlso({"toolbox.backpack.put", "toolbox.backpack.items", "numen.inv.items"})
    public static Packed take(ServerCall call, Fetch args) {
        InventoryHandler handler = handler(call.her(), args.where());
        int want = args.count().orElse(Integer.MAX_VALUE);
        int moved = 0;
        for (int slot = 0; slot < handler.getSlots() && moved < want; slot++) {
            ItemStack there = handler.getStackInSlot(slot);
            if (there.isEmpty() || !there.is(args.item())) {
                continue;
            }
            int ask = Math.min(there.getCount(), want - moved);
            ItemStack taken = handler.extractItem(slot, ask, false);
            give(call.her(), taken);
            moved += taken.getCount();
        }
        handler.saveInventory();
        if (moved == 0) {
            throw new ApiError(ErrorKind.NOT_FOUND, "that backpack holds none of " + name(args.item()),
                    Call.of("toolbox.backpack.items"));
        }
        return new Packed(moved, heldCount(handler, args.item()));
    }

    // ---- 背包在哪 ----

    /** 手上拿的;不是背包就是空。 */
    private static Optional<IBackpackWrapper> held(NumenPlayer her) {
        return wrapper(her.getMainHandItem());
    }

    /** 穿在胸口的;不是背包就是空。 */
    private static Optional<IBackpackWrapper> worn(NumenPlayer her) {
        return wrapper(her.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
    }

    /** 界面上开着的那个;精妙背包把当前上下文留在她的 containerMenu 里。 */
    private static Optional<IBackpackWrapper> open(NumenPlayer her) {
        if (her.containerMenu instanceof net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer menu) {
            return usable(menu.getBackpackContext().getBackpackWrapper(her));
        }
        return Optional.empty();
    }

    private static Optional<IBackpackWrapper> usable(IBackpackWrapper wrapper) {
        // 拿不到真背包(没有内容的,或者压根不是背包)时精妙背包会给一个 Noop:照它内部的储物格是空的
        return wrapper == null || wrapper.getBackpack().isEmpty() ? Optional.empty() : Optional.of(wrapper);
    }

    private static Optional<IBackpackWrapper> wrapper(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return usable(BackpackWrapper.fromStack(stack));
    }

    // ---- 读与写 ----

    private static InventoryHandler handler(NumenPlayer her, Optional<String> where) {
        Optional<IBackpackWrapper> wrapper = switch (where.orElse("held").strip().toLowerCase(java.util.Locale.ROOT)) {
            case "worn" -> worn(her);
            case "open" -> open(her);
            case "held" -> held(her);
            default -> throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    "where must be held, worn or open, not '" + where.get() + "'",
                    Call.of("toolbox.backpack.packs"));
        };
        return wrapper.map(IStorageWrapper::getInventoryHandler)
                .orElseThrow(() -> new ApiError(ErrorKind.NOT_FOUND,
                        "you have no backpack " + where.orElse("held") + " — hold one, wear one, or open one",
                        Call.of("toolbox.items.search", "backpack")));
    }

    private static BackpackInfo row(IBackpackWrapper wrapper, String where) {
        InventoryHandler handler = wrapper.getInventoryHandler();
        int filled = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) {
                filled++;
            }
        }
        UpgradeHandler upgrades = wrapper.getUpgradeHandler();
        boolean upgraded = false;
        for (int slot = 0; slot < upgrades.getSlots(); slot++) {
            if (!upgrades.getStackInSlot(slot).isEmpty()) {
                upgraded = true;
                break;
            }
        }
        ItemStack stack = wrapper.getBackpack();
        return new BackpackInfo(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getHoverName().getString(),
                where, handler.getSlots(), filled, upgraded);
    }

    /** 她带着的这件东西,一格一格地取:原版 API 只给合起来的数,写入要按格。 */
    private static List<ItemStack> carriedStacks(NumenPlayer her, Item item) {
        List<ItemStack> out = new ArrayList<>();
        for (int slot = 0; slot < her.getInventory().getContainerSize(); slot++) {
            ItemStack stack = her.getInventory().getItem(slot);
            if (stack.is(item)) {
                out.add(stack);
            }
        }
        return out;
    }

    private static int carriedCount(NumenPlayer her, Item item) {
        int n = 0;
        for (int slot = 0; slot < her.getInventory().getContainerSize(); slot++) {
            ItemStack stack = her.getInventory().getItem(slot);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static int heldCount(InventoryHandler handler, Item item) {
        int n = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    /** 搬进她自己那背包;满了就掉在脚边,和玩家一样。 */
    private static void give(NumenPlayer her, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (!her.getInventory().add(stack)) {
            her.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(her.level(),
                    her.getX(), her.getEyeY(), her.getZ(), stack));
        }
    }

    private static String name(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? item.toString() : key.toString();
    }
}

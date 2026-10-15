package com.wwdele.numextra.sbw;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.ServerCall;
import com.wwdele.numextra.internal.Tickers;
import com.wwdele.numextra.internal.VehicleGunController;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code toolbox.vehicles}:她骑着的卓越前线载具——坦克、炮车、自行火炮——的火力。
 *
 * <p>和手里的枪是两套东西:炮塔不跟着她的视线走,炮口方向和炮塔方向是同一套姿态,所以她自己瞄不准、弹还会飞到旁边。
 * 这里替她把火控整套管起来:以炮口为心索敌、按弹速重力与目标速度算提前量、每刻把炮塔拧过去、够准了才开火,一发一发
 * 按这门炮的射速来。她只管骑着,或者在需要时停下等打完。
 *
 * <p>用的是卓越前线自己防御塔的那一套预判({@code RangeTool.calculateFiringSolution}),牛顿迭代解出飞行时间,
 * 打得比直瞄准得多;激光类武器没有飞行时间,直接指目标。
 */
public final class VehicleApi {

    private VehicleApi() {}

    /** 一次开火交回什么。 */
    @Doc("How a volley of vehicle fire went.")
    public record Volley(@Doc("Rounds actually fired.") int shots,
                         @Doc("The target it fired at, an entity id.") Optional<Integer> target,
                         @Doc("Why it stopped: done, no_target, no_ammo, off_vehicle or interrupted.")
                         String because) {}

    /** 打多久:按发数、按秒、还是打完这一梭。 */
    public record Burst(@Doc("How many rounds to fire.")
                        @Omitted("10") Optional<Integer> shots,
                        @Doc("Fire for this long, in seconds (up to 300); whichever comes first.")
                        @Omitted("keep firing until the target is gone") Optional<Double> seconds,
                        @Doc("A specific target to fire at, an entity from numen.scan.entities.")
                        @Omitted("find the nearest enemy yourself") Optional<EntityRef> target) {}

    @Fn("Fire the cannon of the SuperbWarfare vehicle you are riding.")
    @Example("toolbox.vehicles.fire()")
    @Example("toolbox.vehicles.fire({shots = 5})")
    @Example("toolbox.vehicles.fire({target = 184, seconds = 20})")
    @Note("It aims for you: it picks the nearest enemy in the turret's arc and range, leads the shot by the "
            + "target's speed and the shell's flight time, turns the turret to it and fires round by round at the "
            + "gun's own rate. You do not need to look at the target.")
    @Note("It never fires at you, your owner, your team, or the vehicle you ride. Firing at someone else's stuff "
            + "may ask your owner first; the call waits for the answer.")
    @Note("Returns how many rounds went out, at whom, and why it stopped. no_target means nothing was in arc and "
            + "range; no_ammo means the cannon is empty (reload with numen.gui, or leave and remount).")
    @SeeAlso({"toolbox.vehicles.status", "toolbox.sbw.info", "numen.scan.entities"})
    public static Pending<Volley> fire(ServerCall call, Burst args) {
        NumenPlayer her = call.her();
        Entity ridden = her.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle)) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "you are not riding a SuperbWarfare vehicle",
                    Call.of("toolbox.items.search", "vehicle"));
        }
        if (!vehicle.hasTurret() || vehicle.getGunData("Main") == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "this vehicle has no cannon to fire",
                    Call.of("toolbox.vehicles.status"));
        }

        int maxShots = Math.clamp((long) args.shots().orElse(10), 1, 1000);
        long maxTicks = args.seconds().map(s -> Math.clamp(Math.round(s * 20), 1, 20L * 300)).orElse(Long.MAX_VALUE);
        EntityRef namedRef = args.target().orElse(null);
        Entity named = namedRef == null ? null : call.entity(namedRef);

        Pending<Volley> pending = Pending.create();
        Runnable start = () -> Tickers.add(her.getUUID(), new VolleyController(her.getUUID(), vehicle, pending,
                maxShots, maxTicks, namedRef));
        if (named == null) {
            start.run();
            return pending;
        }
        return call.authorize(Action.attack(named)).thenWait(v -> {
            start.run();
            return pending;
        });
    }

    /** 骑着的这辆车的火力状况。 */
    public record Mount(@Doc("Its entity id.") int id,
                        @Doc("What it is, m1a2.") String kind,
                        @Doc("Whether it has a turret to turn.") boolean turret,
                        @Doc("The cannon's name, Main.") String weapon,
                        @Doc("Damage per shot.") double damage,
                        @Doc("Rounds left in the cannon.") int ammo,
                        @Doc("Muzzle velocity, in blocks per tick.") double velocity,
                        @Doc("Whether the shell arcs under gravity (a mortar does; a laser does not).") boolean ballistic,
                        @Doc("How far the cannon reaches, in blocks.") double range,
                        @Doc("Whether the turret is damaged and turns slower.") boolean turretDamaged) {}

    @Fn("The SuperbWarfare vehicle you ride and what its cannon can do.")
    @Example("local v = toolbox.vehicles.status(); print(v.kind, v.weapon, v.ammo, v.range)")
    @Note("Instant and read-only. Fails with no_material when you ride no vehicle. With no turret it still reports, "
            + "but toolbox.vehicles.fire needs one.")
    @SeeAlso({"toolbox.vehicles.fire", "toolbox.sbw.info"})
    public static Mount status(ServerCall call) {
        NumenPlayer her = call.her();
        Entity ridden = her.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle)) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "you are not riding a SuperbWarfare vehicle",
                    Call.of("toolbox.items.search", "vehicle"));
        }
        String weapon = vehicle.getGunData("Main") != null ? "Main" : vehicle.getGunName(0);
        var data = vehicle.getGunData(weapon);
        return new Mount(vehicle.getId(), vehicle.getType().getDescriptionId(),
                vehicle.hasTurret(),
                weapon == null ? "" : weapon,
                data == null ? 0 : number(data.get(com.atsuishio.superbwarfare.data.gun.GunProp.DAMAGE)),
                vehicle.getAmmoCount(weapon),
                data == null ? 0 : number(data.get(com.atsuishio.superbwarfare.data.gun.GunProp.VELOCITY)),
                data != null && data.get(com.atsuishio.superbwarfare.data.gun.GunProp.GRAVITY) != null
                        && data.get(com.atsuishio.superbwarfare.data.gun.GunProp.GRAVITY) > 0,
                data == null ? 0 : number(data.get(com.atsuishio.superbwarfare.data.gun.GunProp.RANGE)),
                vehicle.getEntityData().get(VehicleEntity.TURRET_DAMAGED));
    }

    private static double number(Double value) {
        return value == null ? 0.0 : value;
    }

    private static int number(Integer value) {
        return value == null ? 0 : value;
    }
    /** 载具弹药位上的一个格子。 */
    @Doc("One stack of ammo inside the vehicle's own storage.")
    public record Slot(@Doc("The slot it sits in.") int slot,
                       @Doc("The item id, minecraft:tnt.") String item,
                       @Doc("Its display name.") String name,
                       @Doc("How many are in that stack.") int count,
                       @Doc("Whether this cannon can use it.") boolean usable) {}

    /** 这门炮要的东西。 */
    @Doc("What the cannon wants: what it takes, and what you carry for it.")
    public record AmmoInfo(@Doc("Ammo this cannon accepts, one entry per kind it can load.")
                           List<Kind> kinds,
                           @Doc("How much ammo the cannon has right now, in its own terms.")
                           int loaded,
                           @Doc("Whether it eats from the vehicle's storage instead of a magazine.")
                           boolean backpackFed,
                           @Doc("How much of the wanted ammo you carry in your own inventory.")
                           int carried) {}

    /** 一种能用的弹药。 */
    @Doc("One kind of ammo the cannon accepts.")
    public record Kind(@Doc("The item id.") String item,
                       @Doc("Its display name.") String name,
                       @Doc("How much of it the vehicle already holds.") int aboard,
                       @Doc("Which ammo slot it is loaded into, from toolbox.vehicles.ammo().")
                       int index) {}

    /** 装填什么、装到哪、装几组。 */
    public record Stow(@Doc("Which shell to load: an index from toolbox.vehicles.ammo().")
                       @Omitted("whichever kind it can") Optional<Integer> kind,
                       @Doc("How many stacks to load from your inventory.")
                       @Omitted("as many as fit and you carry") Optional<Integer> stacks) {}

    /** 装了多少进去。 */
    @Doc("How a load went.")
    public record Stowed(@Doc("How many stacks went aboard.") int stacks,
                         @Doc("How much of the wanted shell you still carry.") int carried) {}

    @Fn("What the cannon of the vehicle you ride takes, and how much of it you carry.")
    @Example("local a = toolbox.vehicles.ammo()\nfor _, k in ipairs(a.kinds) do print(k.item, k.aboard) end")
    @Note("Instant and read-only. A cannon may take several kinds of shell — that is what switching ammo type is "
            + "for. Fails with no_material when you ride no vehicle, or none with a cannon.")
    @Note("`loaded` is what the cannon can fire right now. `backpackFed` true means it eats straight from the "
            + "vehicle's storage (no magazine), which is the usual case for a tank's main gun.")
    @Note("Find shells with toolbox.items.search, then load them with toolbox.vehicles.load.")
    @SeeAlso({"toolbox.vehicles.load", "toolbox.vehicles.fire", "toolbox.vehicles.status"})
    public static AmmoInfo ammo(ServerCall call) {
        NumenPlayer her = call.her();
        VehicleEntity vehicle = ridden(call);
        GunData data = cannon(vehicle);
        if (data == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "this vehicle has no cannon", Call.of("toolbox.vehicles.status"));
        }

        List<Kind> kinds = new ArrayList<>();
        List<AmmoConsumer> consumers = data.get(GunProp.AMMO_CONSUMER);
        for (int i = 0; i < consumers.size(); i++) {
            AmmoConsumer consumer = consumers.get(i);
            ItemStack shell = consumer.stack();
            if (shell.isEmpty()) {
                continue;
            }
            kinds.add(new Kind(BuiltInRegistries.ITEM.getKey(shell.getItem()).toString(),
                    shell.getHoverName().getString(), countAboard(vehicle, shell.getItem()), i));
        }
        int carried = 0;
        for (Kind kind : kinds) {
            Item item = itemOf(kind.item());
            if (item != null) {
                carried = Math.max(carried, countCarried(her, item));
            }
        }
        return new AmmoInfo(kinds, vehicle.getAmmoCount(weapon(vehicle)), data.useBackpackAmmo(), carried);
    }

    @Fn("Load shells from your inventory into the vehicle you ride.")
    @Example("toolbox.vehicles.load()")
    @Example("toolbox.vehicles.load({kind = 0})")
    @Example("toolbox.vehicles.load({kind = 1, stacks = 3})")
    @Note("The cannon fires out of the vehicle's own storage, not your backpack, so it must be loaded. It moves whole "
            + "stacks, as many as fit and as many as you carry.")
    @Note("kind picks which shell to load when the cannon takes more than one (the index from "
            + "toolbox.vehicles.ammo). Without it, it loads whichever kind it can.")
    @Note("Returns how much went aboard and how much is left in your hands. Loading does not by itself make the "
            + "cannon ready — a magazine-fed cannon still needs toolbox.vehicles.fire to reload it.")
    @SeeAlso({"toolbox.vehicles.ammo", "toolbox.vehicles.fire"})
    public static Stowed load(ServerCall call, Stow args) {
        NumenPlayer her = call.her();
        VehicleEntity vehicle = ridden(call);
        GunData data = cannon(vehicle);
        if (data == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "this vehicle has no cannon", Call.of("toolbox.vehicles.status"));
        }
        if (!vehicle.hasContainer()) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "this vehicle has no storage to load into",
                    Call.of("toolbox.vehicles.ammo"));
        }

        List<AmmoConsumer> consumers = data.get(GunProp.AMMO_CONSUMER);
        int want = args.kind().orElse(-1);
        if (want < -1 || want >= consumers.size()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    "kind must be an index from toolbox.vehicles.ammo, not " + args.kind().orElse(null),
                    Call.of("toolbox.vehicles.ammo"));
        }
        int limit = args.stacks().map(n -> Math.clamp((long) n, 1, 64)).orElse(Integer.MAX_VALUE);

        int stacks = 0;
        int carried = 0;
        boolean any = false;
        for (int i = 0; i < consumers.size() && stacks < limit; i++) {
            if (want >= 0 && i != want) {
                continue;
            }
            AmmoConsumer consumer = consumers.get(i);
            ItemStack shell = consumer.stack();
            if (shell.isEmpty()) {
                continue;
            }
            Item item = shell.getItem();
            any = true;
            while (stacks < limit && countCarried(her, item) > 0 && hasRoomFor(vehicle, item)) {
                if (!moveOneStack(her, vehicle, item)) {
                    break;
                }
                stacks++;
            }
            carried += countCarried(her, item);
        }
        if (!any) {
            throw new ApiError(ErrorKind.NOT_FOUND, "this cannon names no shell to load",
                    Call.of("toolbox.vehicles.ammo"));
        }
        vehicle.setChanged();
        return new Stowed(stacks, carried);
    }

    @Fn("Switch what the cannon of the vehicle you ride fires.")
    @Example("toolbox.vehicles.shift(1)")
    @Note("A cannon that takes several shells loads one at a time; this picks which. It hands the round already in "
            + "the chamber back to the vehicle's storage, so nothing is wasted.")
    @Note("Instant. Fails with no_material when you ride no vehicle or it has no cannon, and bad_argument when the "
            + "index is not one of the kinds toolbox.vehicles.ammo lists.")
    @Note("After switching, toolbox.vehicles.fire reloads the cannon with the new shell.")
    @SeeAlso({"toolbox.vehicles.ammo", "toolbox.vehicles.load", "toolbox.vehicles.fire"})
    public static boolean shift(ServerCall call, Stow args) {
        VehicleEntity vehicle = ridden(call);
        GunData data = cannon(vehicle);
        if (data == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "this vehicle has no cannon to switch",
                    Call.of("toolbox.vehicles.status"));
        }
        List<AmmoConsumer> consumers = data.get(GunProp.AMMO_CONSUMER);
        int kind = args.kind().orElse(-1);
        if (kind < 0 || kind >= consumers.size()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    "kind must be 0.." + (consumers.size() - 1) + ", the indices toolbox.vehicles.ammo lists",
                    Call.of("toolbox.vehicles.ammo"));
        }
        data.changeAmmoConsumer(kind, vehicle);
        return true;
    }

    private static int countAboard(VehicleEntity vehicle, Item item) {
        int n = 0;
        for (int slot = 0; slot < vehicle.getContainerSize(); slot++) {
            ItemStack stack = vehicle.getItem(slot);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static int countCarried(NumenPlayer her, Item item) {
        int n = 0;
        for (int slot = 0; slot < her.getInventory().getContainerSize(); slot++) {
            ItemStack stack = her.getInventory().getItem(slot);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    /** 车上还有没有能叠这一样的一格。 */
    private static boolean hasRoomFor(VehicleEntity vehicle, Item item) {
        for (int slot = 0; slot < vehicle.getContainerSize(); slot++) {
            if (vehicle.canPlaceItem(slot, new ItemStack(item))) {
                return true;
            }
        }
        return false;
    }

    /** 从她背包搬一组到车上;搬没搬成。 */
    private static boolean moveOneStack(NumenPlayer her, VehicleEntity vehicle, Item item) {
        int from = -1;
        for (int slot = 0; slot < her.getInventory().getContainerSize(); slot++) {
            if (her.getInventory().getItem(slot).is(item)) {
                from = slot;
                break;
            }
        }
        if (from < 0) {
            return false;
        }
        ItemStack piece = her.getInventory().getItem(from).copy();
        int to = -1;
        for (int slot = 0; slot < vehicle.getContainerSize(); slot++) {
            if (vehicle.canPlaceItem(slot, piece)) {
                to = slot;
                break;
            }
        }
        if (to < 0) {
            return false;
        }
        int room = vehicle.maxStackSize - vehicle.getItem(to).getCount();
        room = Math.min(room, piece.getCount());
        if (room <= 0) {
            return false;
        }
        ItemStack moved = piece.copy();
        moved.setCount(room);
        vehicle.setItem(to, moved);
        her.getInventory().getItem(from).shrink(room);
        return true;
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static Item itemOf(String id) {
        return BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.tryParse(id));
    }

    private static VehicleEntity ridden(ServerCall call) {
        if (!(call.her().getVehicle() instanceof VehicleEntity vehicle)) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "you are not riding a SuperbWarfare vehicle",
                    Call.of("toolbox.items.search", "vehicle"));
        }
        return vehicle;
    }

    private static GunData cannon(VehicleEntity vehicle) {
        return vehicle.getGunData(weapon(vehicle));
    }

    private static String weapon(VehicleEntity vehicle) {
        return vehicle.getGunData("Main") != null ? "Main" : vehicle.getGunName(0);
    }


}

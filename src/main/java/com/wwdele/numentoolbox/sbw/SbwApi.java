package com.wwdele.numentoolbox.sbw;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import com.wwdele.numentoolbox.internal.GunController;
import com.wwdele.numentoolbox.internal.ReloadController;
import com.wwdele.numentoolbox.internal.Tickers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code toolbox.sbw}:卓越前线(SuperbWarfare)的枪。
 *
 * <p>{@code weapons} / {@code info} 当场读枪的静态数据;{@code fire} / {@code reload} 是占身体的活,由每刻的
 * {@link GunController} / {@link ReloadController} 替她扣扳机、拉栓、换弹。命中目标会先过权限层。
 */
public final class SbwApi {

    private SbwApi() {}

    /** 卓越前线的一把枪。 */
    @Doc("One SuperbWarfare weapon.")
    public record Weapon(@Doc("Its item id, superbwarfare:ak_47.") String id,
                         @Doc("Its name.") String name,
                         @Doc("Damage per shot.") double damage,
                         @Doc("Rounds per magazine.") int magazine,
                         @Doc("Rounds per minute.") int rpm,
                         @Doc("Muzzle velocity.") double velocity,
                         @Doc("Effective range, in blocks.") int range,
                         @Doc("The projectile it fires.") String projectile) {}

    @Fn("List every SuperbWarfare weapon on this server.")
    @Example("for _, w in ipairs(toolbox.sbw.weapons()) do print(w.id, w.name, w.damage, w.magazine) end")
    @Note("Instant and read-only. Each result carries the exact id to hold with numen.gear.hold, then fire.")
    @SeeAlso({"toolbox.sbw.info", "toolbox.sbw.fire", "toolbox.items.of_mod"})
    public static List<Weapon> weapons(ServerCall call) {
        List<Weapon> out = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof GunItem) {
                out.add(row(item));
            }
        }
        return out;
    }

    /** 查哪把枪。 */
    public record Info(@Doc("The weapon id, superbwarfare:ak_47.") String id) {}

    @Fn("One SuperbWarfare weapon by its id.")
    @Example("toolbox.sbw.info(\"superbwarfare:ak_47\")")
    @Note("Instant and read-only. Fails with not_found when no SuperbWarfare weapon has that id.")
    @SeeAlso({"toolbox.sbw.weapons", "toolbox.sbw.fire"})
    public static Weapon info(ServerCall call, Info args) {
        ResourceLocation key = ResourceLocation.tryParse(
                args.id().contains(":") ? args.id() : "superbwarfare:" + args.id());
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)
                || !(BuiltInRegistries.ITEM.get(key) instanceof GunItem)) {
            throw new ApiError(ErrorKind.NOT_FOUND, "there is no SuperbWarfare weapon '" + args.id() + "'",
                    Call.of("toolbox.sbw.weapons"));
        }
        return row(BuiltInRegistries.ITEM.get(key));
    }

    /** 打谁、打几发。 */
    public record Fire(@Doc("Aim at this entity first.")
                       @Omitted("shoot straight ahead") Optional<EntityRef> target,
                       @Doc("How many rounds to fire.")
                       @Omitted("1") Optional<Integer> count) {}

    /** 打出去几发。 */
    public record Fired(@Doc("Rounds actually fired.") int shots) {}

    @Fn("Fire the SuperbWarfare weapon you are holding.")
    @Example("toolbox.sbw.fire()")
    @Example("toolbox.sbw.fire({target = 184, count = 5})")
    @Note("A body job: it bolts, reloads when needed and fires for you, then returns how many rounds went out.")
    @Note("Naming a target asks your owner first (attack). An empty weapon fails with no_material — call "
            + "toolbox.sbw.reload first, or toolbox.items.search for ammo.")
    @SeeAlso({"toolbox.sbw.reload", "toolbox.sbw.info", "toolbox.items.search"})
    public static Pending<Fired> fire(ServerCall call, Fire args) {
        NumenPlayer her = call.her();
        if (!(her.getMainHandItem().getItem() instanceof GunItem)) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "you are not holding a SuperbWarfare weapon",
                    Call.of("toolbox.items.of_mod", "superbwarfare"));
        }
        int count = Math.clamp((long) args.count().orElse(1), 1, 200);
        Entity target = args.target().map(call::entity).orElse(null);
        Pending<Fired> pending = Pending.create();
        Runnable start = () -> Tickers.add(her.getUUID(),
                new GunController(her.getUUID(), pending, count, target == null ? null : EntityRef.of(target)));
        if (target == null) {
            start.run();
            return pending;
        }
        return call.authorize(Action.attack(target)).then(v -> {
            start.run();
            return null;
        });
    }

    /** 装进去几发。 */
    public record Reloaded(@Doc("Rounds in the magazine after reloading.") int loaded) {}

    @Fn("Reload the SuperbWarfare weapon you are holding.")
    @Example("toolbox.sbw.reload()")
    @Note("A body job: it plays the reload and refills from your backpack ammo, then returns.")
    @Note("Fails with no_material when you hold no weapon or carry no matching ammo.")
    @SeeAlso({"toolbox.sbw.fire", "toolbox.sbw.info"})
    public static Pending<Reloaded> reload(ServerCall call) {
        NumenPlayer her = call.her();
        if (!(her.getMainHandItem().getItem() instanceof GunItem)) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "you are not holding a SuperbWarfare weapon",
                    Call.of("toolbox.items.of_mod", "superbwarfare"));
        }
        Pending<Reloaded> pending = Pending.create();
        Tickers.add(her.getUUID(), new ReloadController(her.getUUID(), pending));
        return pending;
    }

    private static Weapon row(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        GunData data = GunData.from(new ItemStack(item));
        String name = data.get(GunProp.NAME);
        return new Weapon(key.toString(),
                name != null ? name : new ItemStack(item).getHoverName().getString(),
                data.get(GunProp.DAMAGE),
                data.get(GunProp.MAGAZINE),
                data.get(GunProp.RPM),
                data.get(GunProp.VELOCITY),
                data.get(GunProp.RANGE),
                data.get(GunProp.PROJECTILE));
    }
}

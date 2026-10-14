package com.wwdele.numextra.sbw;

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
import com.dwinovo.numen.sdk.ServerCall;
import com.wwdele.numextra.internal.Tickers;
import com.wwdele.numextra.internal.VehicleGunController;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

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
                        @Omitted("find the nearest enemy yourself") Optional<Integer> target) {}

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
        Entity named = args.target().map(call::entity).orElse(null);

        Pending<Volley> pending = Pending.create();
        Runnable start = () -> Tickers.add(her.getUUID(), new VolleyController(her.getUUID(), vehicle, pending,
                maxShots, maxTicks, named == null ? null : com.dwinovo.numen.sdk.EntityRef.of(named)));
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
    @Doc("The vehicle you ride: what it can shoot, and with what.")
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
}

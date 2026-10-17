package com.wwdele.numextra.internal;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.SeekWeaponInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleWeaponUtils;
import com.atsuishio.superbwarfare.tools.RangeTool;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

import java.util.UUID;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 载具的火控:索敌、预判、转炮塔、等这门炮自己说能打了再打。
 *
 * <p>为什么不自己算节奏:每一门炮的脾气全在它自己的数据里——坦克主炮 {@code Magazine:1}、换弹 5 秒、后坐 2 秒,
 * 而 {@code GunData.canShoot(ammoSupplier)} 已经把弹药、换弹中、拉栓中、过热全算进去了。自己另算一套 RPM,
 * 只会在这门炮明明不能打的时候反复去戳它,一发都出不去。所以这里只做一件事:**问它能不能打,能打就打,不能打就等**。
 *
 * <p>换弹要我们自己推一把:自动换弹那条路是给玩家手上那把枪的,载具炮虽然每刻也被 {@code gunTick} 推进,但
 * {@code Magazine:1} 这种打一发就空的炮,得由我们看它空了就 {@code startReload()}。
 */
public final class VehicleGunController implements Tickers.Ticker {

    /** 一直瞄不上、也没目标时,最多再等多久(游戏刻)。 */
    private static final int MAX_IDLE = 20 * 20;

    /** 炮口方向和目标方向相差不到这么多度,才算瞄上了(度)。 */
    private static final double AIM_TOLERANCE = 2.5;

    /** 看不见目标这么久就换一个(刻);比这短只是被自己人的烟挡了一下。 */
    private static final int LOSE_SIGHT_TICKS = 30;

    private final UUID companion;
    private final VehicleEntity vehicle;
    private final VehicleFire fire;

    /** 目标的运行期 id;每刻重认,走开、死了、看不见了就重新索敌。 */
    private Entity target;
    private int lostSight;
    private int idle;
    /** 连着多少刻这门炮说不能打;只用来说清"为什么还没打出去"。 */
    private int waiting;

    /** 一次开火交回什么、什么时候算打完,由调用方决定。 */
    public interface VehicleFire {

        /** @return true 这一轮还接着打,false 可以收工了。 */
        boolean keepGoing();

        /** 打出去了一发。 */
        void fired(int shots, Entity target);

        /** 这门炮现在没弹药、也在等装填。 */
        void waitingForAmmo();

        /** 她不在车上了。 */
        void offVehicle();

        /** 一直没有目标,这么久就收工。 */
        void idleOut();

        /** 被打断。 */
        void stopped();
    }

    public VehicleGunController(UUID companion, VehicleEntity vehicle, VehicleFire fire) {
        this.companion = companion;
        this.vehicle = vehicle;
        this.fire = fire;
    }

    @Override
    public boolean tick(MinecraftServer server) {
        if (vehicle.isRemoved() || vehicle.isWreck()) {
            return false;
        }
        if (!fire.keepGoing()) {
            return false;
        }
        LivingEntity her = findSelf(server);
        if (her == null || !her.isAlive()) {
            return false;
        }
        if (her.getVehicle() != vehicle) {
            fire.offVehicle();
            return false;
        }
        GunData data = gunData(her);
        if (data == null) {
            fire.offVehicle();
            return false;
        }

        // 打一发就空的炮(坦克主炮 Magazine:1 就是),得我们看它空了就把换弹顶起来
        if (data.shouldStartReloading(vehicle)) {
            data.startReload();
        }

        Entity aim = resolveTarget(her, data);
        if (aim == null) {
            if (++idle > MAX_IDLE) {
                fire.idleOut();
                return false;
            }
            return true;
        }
        idle = 0;

        String weapon = weapon(her);
        Vec3 barrel = vehicle.getShootPos(weapon, 1f);
        Vec3 want = lead(barrel, aim, data, weapon);
        turnTurret(want);
        faceAlong(her, want);

        if (!onTarget(barrel, aim, tolerance(aim, data))) {
            // 炮塔还在转,等它到位
            return true;
        }

        // 这一炮能不能打,全听这门炮自己的:弹药、换弹中、拉栓中、过热都算在内
        if (!data.canShoot(vehicle)) {
            if (!data.hasEnoughAmmoToShoot(vehicle) && data.countBackupAmmo(vehicle) <= 0) {
                fire.waitingForAmmo();
                return false;
            }
            waiting++;
            return true;
        }
        waiting = 0;
        vehicle.vehicleShoot(her, weapon(her), aim.getUUID(), aim.position());
        fire.fired(1, aim);
        return true;
    }

    @Override
    public void cancel() {
        fire.stopped();
    }

    // ---- 这一座的火力 ----

    /**
     * 她这一座开着的那门炮叫什么。按座位推导:每辆车的武器名是自己起的——坦克是 {@code Cannon} 与
     * {@code MachineGun},飞机是 {@code Main},写死任何一个都会在别的车上落空,于是"明明在车上却开不了炮"。
     */
    private String weapon(LivingEntity her) {
        int seat = vehicle.getSeatIndex(her);
        String name = seat < 0 ? null : vehicle.getGunName(seat);
        if (name != null && !name.isEmpty() && vehicle.getGunData(name) != null) {
            return name;
        }
        return vehicle.getGunDataMap().keySet().stream().sorted()
                .filter(key -> vehicle.getGunData(key) != null)
                .findFirst()
                .orElse(null);
    }

    /** 这一座的火力数据;这一座没炮,或者整辆车都没炮,是 null。 */
    private GunData gunData(LivingEntity her) {
        String weapon = weapon(her);
        return weapon == null ? null : vehicle.getGunData(weapon);
    }

    // ---- 索敌与预判 ----

    /** 每刻把目标重认一遍:走远、死了、看不见了,就重新索敌。 */
    private Entity resolveTarget(LivingEntity her, GunData data) {
        double reach = range(data);
        if (target != null && target.isAlive() && target.distanceToSqr(vehicle) <= reach * reach) {
            if (sees(vehicle.getShootPos(weapon(her), 1f), target)) {
                lostSight = 0;
                return target;
            }
            if (++lostSight < LOSE_SIGHT_TICKS) {
                return target;
            }
        }
        forget();
        Entity found = acquire(her, data, reach);
        if (found != null) {
            target = found;
        }
        return found;
    }

    /** 射界与射程内最近的敌人,视线要通。 */
    private Entity acquire(LivingEntity her, GunData data, double reach) {
        Vec3 barrel = vehicle.getShootPos(weapon(her), 1f);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity candidate : vehicle.level().getEntitiesOfClass(Entity.class,
                vehicle.getBoundingBox().inflate(reach))) {
            if (candidate == vehicle || !shootable(her, candidate)) {
                continue;
            }
            double d = candidate.distanceToSqr(vehicle);
            if (d >= bestDist || d > reach * reach) {
                continue;
            }
            if (!sees(barrel, candidate)) {
                continue;
            }
            best = candidate;
            bestDist = d;
        }
        return best;
    }

    /** 能不能打:活的、不是她自己与这辆车、不是她的主人与同队。 */
    private boolean shootable(LivingEntity her, Entity candidate) {
        if (!candidate.isAlive() || candidate == vehicle || candidate == her) {
            return false;
        }
        if (her instanceof NumenPlayer numen) {
            UUID owner = numen.getOwnerUuid();
            if (owner != null && owner.equals(candidate.getUUID())) {
                return false;
            }
        }
        return candidate.getTeam() == null || her.getTeam() == null
                ? true
                : candidate.getTeam() != her.getTeam();
    }

    /** 炮口到目标之间有没有挡着的。 */
    private boolean sees(Vec3 from, Entity to) {
        return vehicle.level().clip(new ClipContext(from, to.getBoundingBox().getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle))
                .getType() != HitResult.Type.BLOCK;
    }

    /** 射程:制导武器用它自己配的锁定射程(导弹能到几百格),普通炮用它的射程,都没有才给默认。 */
    private double range(GunData data) {
        SeekWeaponInfo seek = data.get(GunProp.SEEK_WEAPON_INFO);
        if (seek != null && seek.getSeekRange() > 0) {
            return seek.getSeekRange();
        }
        Integer r = data.get(GunProp.RANGE);
        return r == null || r <= 0 ? 120.0 : r;
    }

    /**
     * 预判出该往哪儿打。用卓越前线自己防御塔那一套解算——它按弹速、重力、目标速度解出飞行时间。
     * 这一条不能省:坦克炮 {@code Gravity: 0.03}、弹速才 20,飞 100 格要 5 秒、下坠十几格,不算重力弹会全落在目标脚前。
     * 激光类没有飞行时间,直指目标。
     */
    private Vec3 lead(Vec3 barrel, Entity aim, GunData data, String weapon) {
        Vec3 aimPos = aim.getBoundingBox().getCenter();
        var projectile = data.get(GunProp.PROJECTILE);
        String kind = projectile == null ? "" : String.valueOf(projectile.getItemId())
                .trim().toLowerCase(java.util.Locale.ROOT);
        // 制导武器(导弹)与激光都没有飞行时间,直指目标:导弹发射后自己纠偏追,给它算提前量反而一上来就歪
        if (kind.equals("ray") || guided(data)) {
            return barrel.vectorTo(aimPos).normalize();
        }
        Vec3 vel = aim.getDeltaMovement();
        if (aim instanceof LivingEntity moving) {
            vel = vel.add(0.0, moving.getAttributeValue(Attributes.GRAVITY), 0.0);
        }
        // 弹速与重力都按这一座炮自己的数据问;载具的这几个 getter 会在 ADD_SHOOTER_DELTA_MOVEMENT 时把车速算进去
        return RangeTool.calculateFiringSolution(barrel, aimPos, vel,
                vehicle.getProjectileVelocity(weapon), vehicle.getProjectileGravity(weapon));
    }

    // ---- 转炮塔 ----

    /**
     * 把炮塔拧向 {@code want}。这一步直接用卓越前线自己的 {@code turretAutoAimFromVector}——它按炮塔当前真实朝向
     * ({@code getBarrelVector})算差、每刻走一小步、夹在射界里。自己重写这套只会把方向基准弄错(用
     * {@code getShootVec} 代替 {@code getBarrelVector}),于是拧过去又拧回来、永远对不上,看着就是乱转。
     */
    private void turnTurret(Vec3 want) {
        VehicleWeaponUtils.turretAutoAimFromVector(vehicle, want);
    }

    /**
     * 炮口对准目标了没有。基准和它自己的自动瞄准一致——炮塔真实朝向({@code getBarrelVector})与目标方向的夹角,
     * 免得"我以为瞄上了、其实炮塔指着别处"。制导武器放宽:导弹发射后自己会纠偏,只要大致朝向(夹角远小于
     * 它丢失目标的 120°)就能打出去追,不必等炮塔拧到分毫。
     */
    private boolean onTarget(Vec3 barrel, Entity aim, double tolerance) {
        Vec3 want = barrel.vectorTo(aim.getBoundingBox().getCenter()).normalize();
        Vec3 at = vehicle.getBarrelVector(1f);
        double deg = Math.toDegrees(Math.acos(Mth.clamp(at.normalize().dot(want), -1.0, 1.0)));
        return deg <= tolerance;
    }

    /** 发射容差(度):制导武器放宽,普通炮弹要准。 */
    private double tolerance(Entity aim, GunData data) {
        return guided(data) ? 15.0 : AIM_TOLERANCE;
    }

    /** 这门炮是不是制导武器(导弹、火箭弹):看它有没有配 {@link SeekWeaponInfo}。 */
    private boolean guided(GunData data) {
        return data.get(GunProp.SEEK_WEAPON_INFO) != null;
    }

    /**
     * 把她的视线也拧向炮口指的方向。炮打得准不准全看炮塔,这一步与命中无关;但它把人从"一直盯着主人看"里捞出来——
     * 引擎的说话姿态链每刻在实体更新之前把头拧向主人,我们在这之后(Post)再拧回来,一个 tick 之内最后一次说话权
     * 在我们这边,于是她看起来一直在看着目标,不再回头看人。
     */
    private void faceAlong(LivingEntity her, Vec3 want) {
        double yaw = Math.toDegrees(Math.atan2(want.z, want.x)) - 90.0;
        double pitch = -Math.toDegrees(Math.asin(Mth.clamp(want.y, -1.0, 1.0)));
        her.setYRot((float) yaw);
        her.setXRot((float) pitch);
        her.setYHeadRot((float) yaw);
    }

    private void forget() {
        target = null;
        lostSight = 0;
    }

    private LivingEntity findSelf(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(companion);
        return player instanceof LivingEntity living ? living : null;
    }

    /** 当前瞄着谁;调试与收尾用。 */
    public Entity currentTarget() {
        return target;
    }

    /** 这门炮连续说"不能打"的刻数;只说等得久不久,不用来判胜负。 */
    public int waitingTicks() {
        return waiting;
    }
}

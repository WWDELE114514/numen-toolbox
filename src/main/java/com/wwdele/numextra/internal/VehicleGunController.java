package com.wwdele.numextra.internal;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils;
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
 * 载具的火控:索敌、预判、转炮塔、开火。算法照卓越前线自己的防御塔那一套,只是由我们替她驱动。
 *
 * <p>为什么她自己打不好:卓越前线的炮塔在服务端只认两样东西——座位上是个 {@link Player} 就跟着她的视线走,是个
 * {@code Mob} 才按目标自动瞄。她是个 {@link ServerPlayer},于是炮塔跟着她的视线,而她不看目标;同时炮口方向和炮塔方向
 * 是同一套姿态,{@code vehicleShoot} 的弹就顺着那个没对准的方向飞出去——这就是"老打不中、还会打到旁边"的由来。
 *
 * <p>这里每一步都对着炮塔来做,不碰她的视线:
 * <ol>
 *   <li><b>索敌</b>:以炮口为心,在炮塔的偏航/俯仰射界与射程内挑最近的敌人,视线被挡住的不算,自己人(她、主人、
 *       同队、这辆车)一律跳过。</li>
 *   <li><b>预判</b>:目标在动,炮弹出膛后还要飞一阵。用卓越前线的 {@link RangeTool#calculateFiringSolution} 按弹速、
 *       重力、目标速度解出该往哪儿打——它用牛顿迭代解出飞行时间,给出提前量。激光类武器没有飞行时间,直接指目标。</li>
 *   <li><b>转炮塔</b>:把 {@code turretYRot/turretXRot} 拧向那个方向,每刻按炮塔自身的转速限幅,并夹在射界里
 *       ——和它自己转得一样快,不做瞬移。</li>
 *   <li><b>开火</b>:炮口方向和目标方向差到容差之内才 {@code vehicleShoot},按 RPM 折算成刻数,一次一发,
 *       和真人按住扳机一样,不会一瞬间把炮弹全泼出去。</li>
 * </ol>
 */
public final class VehicleGunController implements Tickers.Ticker {

    /** 打不出去、也没目标时,最多再等多久(游戏刻)。 */
    private static final int MAX_IDLE = 20 * 20;

    /** 炮口方向和目标方向相差不到这么多度,才算瞄上了(度)。 */
    private static final double AIM_TOLERANCE = 2.0;

    /** 看不见目标这么久就换一个(刻);比这短只是被自己人的烟挡了一下。 */
    private static final int LOSE_SIGHT_TICKS = 30;

    private final UUID companion;
    private final VehicleEntity vehicle;
    private final VehicleFire fire;

    /** 目标的运行期 id;每刻重认,走开、死了、看不见了就重新索敌。 */
    private Entity target;
    private int lostSight;
    private int cooldown;
    private int idle;

    /** 一次开火交回什么、什么时候算打完,由调用方决定。 */
    public interface VehicleFire {

        /** @return true 这一轮还接着打,false 可以收工了。 */
        boolean keepGoing();

        /** 打出去了一发。 */
        void fired(int shots, Entity target);

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
        GunData data = gunData();
        if (data == null) {
            fire.offVehicle();
            return false;
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

        Vec3 barrel = vehicle.getShootPos(weapon(), 1f);
        Vec3 want = lead(barrel, aim, data);
        turnTurret(want);

        if (cooldown > 0) {
            cooldown--;
            return true;
        }
        if (!onTarget(barrel, aim)) {
            // 炮塔还在转,等它到位
            return true;
        }
        vehicle.vehicleShoot(her, weapon(), aim.getUUID(), aim.position());
        fire.fired(1, aim);
        cooldown = ticksPerShot(data);
        return true;
    }

    @Override
    public void cancel() {
        fire.stopped();
    }

    // ---- 这门炮 ----

    /** 她这一座的火力:她坐在炮塔那座就认 {@code "Main"},没有就退回按座位取的那一门。 */
    private String weapon() {
        return vehicle.getGunData("Main") != null ? "Main" : weaponBySeat();
    }

    /** 按她的座位取那一门的名字;认不出就还是 {@code "Main"}。 */
    private String weaponBySeat() {
        int seat = vehicle.getSeatIndex(findSelf(null));
        return seat < 0 ? "Main" : vehicle.getGunName(seat);
    }

    private GunData gunData() {
        return vehicle.getGunData(weapon());
    }

    // ---- 索敌与预判 ----

    /** 每刻把目标重认一遍:就近的敌人更值得打时就换过去。 */
    private Entity resolveTarget(LivingEntity her, GunData data) {
        double reach = range(data);
        if (target != null && target.isAlive() && target.distanceToSqr(vehicle) <= reach * reach) {
            if (sees(vehicle.getShootPos(weapon(), 1f), target)) {
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
        Vec3 barrel = vehicle.getShootPos(weapon(), 1f);
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

    /** 能不能打:活的、不是她自己与这辆车、不是她的主人与同队、也不打已经骑在其他载具上的自己人。 */
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

    /** 射程:取这门炮配置里的;没有或离谱就给一个够用的默认。 */
    private double range(GunData data) {
        Integer r = data.get(GunProp.RANGE);
        return r == null || r <= 0 ? 120.0 : r;
    }

    /**
     * 预判出该往哪儿打。激光类没有飞行时间,直指目标;炮弹按弹速、重力、目标速度解提前量。目标速度乘一个略大于 1 的
     * 系数,和卓越前线的防御塔一样——目标在被瞄准的这会儿多半还在动,略微多算一点提前量更贴。
     */
    private Vec3 lead(Vec3 barrel, Entity aim, GunData data) {
        Vec3 aimPos = aim.getBoundingBox().getCenter();
        var projectile = data.get(GunProp.PROJECTILE);
        String kind = projectile == null ? "" : String.valueOf(projectile.getItemId())
                .trim().toLowerCase(java.util.Locale.ROOT);
        if (kind.equals("ray")) {
            return barrel.vectorTo(aimPos).normalize();
        }
        Vec3 vel = aim.getDeltaMovement().scale(1.1 + vehicle.getRandom().nextFloat() * 0.2f);
        if (aim instanceof LivingEntity moving) {
            vel = vel.add(0.0, moving.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY), 0.0);
        }
        return RangeTool.calculateFiringSolution(barrel, aimPos, vel,
                vehicle.getProjectileVelocity(weapon()), vehicle.getProjectileGravity(weapon()));
    }

    // ---- 转炮塔 ----

    /**
     * 把炮塔拧向 {@code want}:偏航与俯仰每刻各按这座炮塔自身的转速走一步,夹在它的射界里。
     * 方向和卓越前线自己那一套保持一致(见 {@code VehicleWeaponUtils.turretAutoAimFromVector}),所以转起来一样自然。
     */
    private void turnTurret(Vec3 want) {
        Vec3 barrelVec = vehicle.getShootVec(weapon(), 1f);
        float yawDiff = Mth.wrapDegrees(
                (float) (-VehicleVecUtils.getYRotFromVector(want) + VehicleVecUtils.getYRotFromVector(barrelVec)));
        float pitchDiff = Mth.wrapDegrees(
                (float) (-VehicleVecUtils.getXRotFromVector(want) + VehicleVecUtils.getXRotFromVector(barrelVec)));

        float xSpeed = vehicle.getTurretTurnXSpeed();
        float ySpeed = vehicle.getTurretTurnYSpeed();
        if (vehicle.getEntityData().get(VehicleEntity.TURRET_DAMAGED)) {
            xSpeed *= 0.2f;
            ySpeed *= 0.2f;
        }

        vehicle.setTurretXRot(Mth.clamp(vehicle.getTurretXRot() + Mth.clamp(0.75f * pitchDiff, -xSpeed, xSpeed),
                -vehicle.getTurretMaxPitch() + vehicle.getCustomTurretMaxPitch(),
                -vehicle.getTurretMinPitch() - vehicle.getCustomTurretMinPitch()));
        vehicle.setTurretYRot(Mth.clamp(vehicle.getTurretYRot() - Mth.clamp(yawDiff, -ySpeed, ySpeed),
                -vehicle.getTurretMaxYaw(), -vehicle.getTurretMinYaw()));
    }

    /** 炮口方向和目标中心差得够小,才算瞄上了。 */
    private boolean onTarget(Vec3 barrel, Entity aim) {
        Vec3 to = barrel.vectorTo(aim.getBoundingBox().getCenter()).normalize();
        Vec3 at = vehicle.getShootVec(weapon(), 1f).normalize();
        double deg = Math.toDegrees(Math.acos(Mth.clamp(at.dot(to), -1.0, 1.0)));
        return deg <= AIM_TOLERANCE;
    }

    /** 两发之间隔几刻:按这门炮的 RPM 折算,和真人扣扳机的节奏一致。 */
    private int ticksPerShot(GunData data) {
        Integer rpm = data.get(GunProp.RPM);
        return Math.max(1, (int) Math.round(1200.0 / (rpm == null || rpm <= 0 ? 300 : rpm)));
    }

    private void forget() {
        target = null;
        lostSight = 0;
    }

    private LivingEntity findSelf(MinecraftServer server) {
        if (server == null) {
            return vehicle.getFirstPassenger() instanceof LivingEntity living ? living : null;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(companion);
        return player instanceof LivingEntity living ? living : null;
    }

    /** 当前瞄着谁;调试用。 */
    public Entity currentTarget() {
        return target;
    }
}

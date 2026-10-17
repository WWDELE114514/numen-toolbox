package com.wwdele.numextra.internal;

import com.atsuishio.superbwarfare.data.gun.FireMode;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.Pending;
import com.wwdele.numextra.sbw.SbwApi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 一次开火:替她扣扳机,一直扣到打完指定发数、没弹药、或太久没进展。
 *
 * <p>节奏照卓越前线自己给 mob 用的那一套({@code GunShootGoal}),不再自己另算一套,这样才不会有顿挫:
 * <ul>
 *   <li>只有<b>真的能打</b>时才走冷却计时;一旦不能打(正在换弹、正在拉栓、过热、没弹药)计时就归零。
 *       重新能打的那一刻<b>首发立刻出去</b>,不会再等一个完整的冷却——这是"断一下"最主要的来源。</li>
 *   <li>一 tick 内该打几发就补几发,不因帧率低而少打。</li>
 *   <li>半自动与连发的最后一发额外等一手,和那把枪自己的脾气一致。</li>
 * </ul>
 *
 * <p>瞄不瞄、瞄多狠由 {@link Style} 决定——火控可以多选:
 * <ul>
 *   <li>{@link Style#BALLISTIC} 弹道预判:按子弹飞行时间把准星往前挪,打移动目标最准,和载具火控同一套算法。</li>
 *   <li>{@link Style#DIRECT} 直指:枪口直接对准目标,没提前量,适合近处或激光类。</li>
 *   <li>{@link Style#VISUAL} 盯住:只把她的视线拧过去,不接管枪口方向——有些枪的子弹从眼睛出。</li>
 * </ul>
 */
public final class GunController implements Tickers.Ticker {

    private static final Logger LOG = LoggerFactory.getLogger("numen_extra");

    /** 一直打不出去、也没弹药时,最多再等多久(游戏刻)。 */
    private static final int MAX_IDLE = 20 * 20;

    /** 弹道预判算提前量时,目标速度乘的系数:目标在被瞄的这会儿多半还在动,略微多算一点更贴。 */
    private static final double LEAD_FUDGE = 1.15;

    private final UUID companion;
    private final Pending<SbwApi.Fired> pending;
    private final int maxShots;
    private final EntityRef target;
    private final Style style;

    private int fired;
    /** 冷却还差几刻;≤0 表示随时能打。 */
    private int cooldown;
    private int idle;
    /** 看得见目标多久了:按卓越前线给 mob 的那一套,瞄够久才开第一枪。 */
    private int aimTicks;

    /** 这把武器是不是要先锁定才发得出去;换枪会变,所以每刻跟着枪重算。 */
    private boolean needsLock;
    /** 锁定进度;只有索敌武器(地对空导弹、标枪)用得上。 */
    private SeekLock lock;

    /** 火控怎么瞄。 */
    public enum Style {

        /** 弹道预判:按子弹飞行时间提前量,打移动目标最准。 */
        BALLISTIC,

        /** 直指目标中心,不算提前量。 */
        DIRECT,

        /** 只把她的视线拧过去,不动枪口。 */
        VISUAL;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Style from(String name) {
            if (name == null) {
                return BALLISTIC;
            }
            return switch (name.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "direct" -> DIRECT;
                case "visual" -> VISUAL;
                case "ballistic" -> BALLISTIC;
                default -> null;
            };
        }
    }

    public GunController(UUID companion, Pending<SbwApi.Fired> pending, int maxShots, EntityRef target, Style style) {
        this.companion = companion;
        this.pending = pending;
        this.maxShots = maxShots;
        this.target = target;
        this.style = style == null ? Style.BALLISTIC : style;
    }

    @Override
    public boolean tick(MinecraftServer server) {
        NumenPlayer her = findSelf(server);
        if (her == null || !her.isAlive()) {
            pending.fail(new ApiError(ErrorKind.INTERRUPTED, "you are no longer here to fire", null));
            return false;
        }
        ItemStack stack = her.getMainHandItem();
        if (!(stack.getItem() instanceof GunItem)) {
            pending.fail(new ApiError(ErrorKind.NO_MATERIAL, "you put the weapon away", null));
            return false;
        }
        GunData data = GunData.from(stack);
        // 推进这把枪自己的状态:冷却、拉栓、换弹、过热都从这里走
        data.tick(her, true);

        // 换枪了:锁定状态跟着换,别拿上一把的进度打这一把
        boolean seeking = SeekLock.needsLock(data);
        if (seeking != needsLock) {
            needsLock = seeking;
            lock = seeking ? new SeekLock(data) : null;
        }

        Entity aim = target == null ? null : target.in(her.serverLevel());
        if (aim != null && !aim.isAlive()) {
            aim = null;
        }
        // 索敌武器的视线在下面锁定那段里拧(对着目标当前中心);普通枪按选的火控风格瞄
        if (aim != null) {
            if (needsLock) {
                // 锁定进度由 SeekLock 自己数,不占 aimTicks 那份账
                aimTicks = AIM_READY_TICKS;
            } else {
                aim(her, aim, data);
                aimTicks = Math.min(aimTicks + 1, AIM_READY_TICKS);
            }
        } else {
            aimTicks = 0;
        }

        // 索敌武器(地对空导弹、标枪)要先开镜、把视线稳在锁定角内、锁够时间才发得出去。
        // 锁定用的视线必须对着目标的当前位置——卓越前线判锁定用的是玩家的视线与目标中心的夹角,提前量会把
        // 这个夹角撑大(标枪的锁定角只有 10 度),于是永远锁不上。导弹自己会制导,指中心正好。
        if (needsLock && aim != null) {
            lookAt(her, aim);
            lock.zoom(data);
            boolean locked = lock.advance(her, aim);
            if (idle % 20 == 0) {
                LOG.info("numen_extra: seeking {} | progress={} at {}",
                        her.getMainHandItem().getItem(), lock.progress(),
                        aim.getType().getDescriptionId());
            }
            if (!locked) {
                // 还在锁:这一 tick 只瞄,不发
                return waiting(her, data);
            }
        } else if (needsLock) {
            lock.reset();
        }

        // 索敌武器的镜从发射前到导弹落地都不能收:一收镜,已经飞出去的导弹立刻判定丢失、放弃制导
        if (needsLock) {
            lock.sustain(data);
        }

        if (data.shouldStartReloading(her)) {
            data.startReload();
        }
        if (data.shouldStartBolt()) {
            data.startBolt();
        }

        if (cooldown > 0) {
            cooldown--;
        }

        if (aim != null && aimTicks < AIM_READY_TICKS) {
            // 还没瞄稳:这一 tick 只瞄,不开火
            return waiting(her, data);
        }

        if (data.canShoot(her) && cooldown <= 0) {
            if (needsLock) {
                LOG.info("numen_extra: fire {} at {} | locked={} ammo={} mag={} reloading={} bolt={} overHeat={} speed={}",
                        her.getMainHandItem().getItem(),
                        aim == null ? "air" : aim.getType().getDescriptionId(),
                        lock != null && lock.locked(),
                        data.ammo.get(), data.get(GunProp.MAGAZINE), data.reloading(),
                        data.bolt.needed.get(), data.overHeat.get(), data.get(GunProp.VELOCITY));
            }
            fire(her, data, aim);
            return fired >= maxShots ? done() : waiting(her, data);
        }

        // 锁定完成、冷却也到了却打不出去:把挡着的那一项说清(限流,别每刻刷)
        if (needsLock && aim != null && lock != null && lock.locked() && cooldown <= 0 && idle % 20 == 0) {
            LOG.info("numen_extra: fire blocked | {} | has_ammo={} reloading={} bolt_needed={} overHeat={} heat={} mag={} backup={}",
                    her.getMainHandItem().getItem(),
                    data.hasEnoughAmmoToShoot(her), data.reloading(), data.bolt.needed.get(),
                    data.overHeat.get(), data.heat.get(), data.get(GunProp.MAGAZINE), data.countBackupAmmo(her));
        }

        return waiting(her, data);
    }

    @Override
    public void cancel() {
        pending.fail(new ApiError(ErrorKind.INTERRUPTED, "the shooting was stopped", null));
    }

    /** 打出去几发:这一 tick 里该补的都补上,帧率低也不少吃。 */
    private void fire(NumenPlayer her, GunData data, Entity aim) {
        long before = projectilesNear(her);
        int interval = ticksPerShot(data);
        do {
            data.shoot(her, spread(data), zoom(), aim == null ? null : aim.getUUID());
            fired++;
            cooldown += interval;
        } while (cooldown <= 0 && fired < maxShots && data.canShoot(her));
        long after = projectilesNear(her);
        var projectile = data.get(GunProp.PROJECTILE);
        LOG.info("numen_extra: fired {} | projectile='{}' speed={} | near before={} after={} (delta {})",
                her.getMainHandItem().getItem(),
                projectile == null ? "null" : projectile.getItemId(),
                data.get(GunProp.VELOCITY),
                before, after, after - before);
    }

    /** 她身边正飞着的弹射物有多少:不管哪一种,用来验发射到底出没出弹。 */
    private static long projectilesNear(NumenPlayer her) {
        return her.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                her.getBoundingBox().inflate(128),
                p -> !(p instanceof net.minecraft.world.entity.player.Player)).size();
    }

    /** 还没打完:看看是等得起,还是该收工了。 */
    private boolean waiting(NumenPlayer her, GunData data) {
        if (fired >= maxShots) {
            return done();
        }
        if (!data.hasEnoughAmmoToShoot(her) && data.countBackupAmmo(her) <= 0) {
            if (fired > 0) {
                return done();
            }
            pending.fail(new ApiError(ErrorKind.NO_MATERIAL, "the weapon is out of ammo",
                    Call.of("toolbox.sbw.reload")));
            return false;
        }
        if (++idle > MAX_IDLE) {
            return done();
        }
        return true;
    }

    private boolean done() {
        pending.complete(new SbwApi.Fired(fired));
        return false;
    }

    /** 两发之间隔几刻:按这把枪的 RPM 折算;半自动与连发末发额外等一手,和真人一样。 */
    private int ticksPerShot(GunData data) {
        Integer rpm = data.get(GunProp.RPM);
        double rate = rpm == null || rpm <= 0 ? 300 : rpm;
        int ticks = Math.max(1, (int) Math.round(1200.0 / rate));
        FireMode mode = data.selectedFireModeInfo().mode;
        boolean semiLike = mode == FireMode.SEMI
                || mode == FireMode.BURST && data.burstAmount.get() == 0;
        return semiLike ? ticks + 2 : ticks;
    }

    /** 这把枪自己的散布;火控只管方向,散布交给它。 */
    private double spread(GunData data) {
        Double spread = data.get(GunProp.SPREAD);
        return spread == null ? 0.0 : spread;
    }

    private boolean zoom() {
        return false;
    }

    /** 瞄多久才开第一枪:和卓越前线给 mob 的默认一样,半秒左右。 */
    private static final int AIM_READY_TICKS = 10;

    /**
     * 把枪口/视线拧向目标。三种火控:
     * <ul>
     *   <li>弹道预判:按子弹飞行时间算提前量——目标速度乘个略大的系数,和载具火控同一套。</li>
     *   <li>直指:就指目标中心。</li>
     *   <li>盯住:只拧她的视线。</li>
     * </ul>
     */
    private void aim(NumenPlayer her, Entity aim, GunData data) {
        if (style == Style.VISUAL) {
            lookAt(her, aim);
            return;
        }
        if (style == Style.DIRECT) {
            lookAt(her, aim);
            return;
        }
        double fly = flightTicks(her, aim, data);
        Vec3 where = aim.position().add(aim.getDeltaMovement().scale(fly * LEAD_FUDGE));
        lookAt(her, where.x, where.y + aim.getBbHeight() * 0.5, where.z);
    }

    /** 子弹从出膛到飞过来要几刻:距离除弹速,不够就算不出就按直指。 */
    private double flightTicks(NumenPlayer her, Entity aim, GunData data) {
        Double velocity = data.get(GunProp.VELOCITY);
        if (velocity == null || velocity <= 0) {
            return 0;
        }
        double distance = Math.sqrt(her.distanceToSqr(aim));
        return distance / velocity;
    }

    /** 每刻把她的视线拧向目标:直接设偏航/俯仰,免得依赖某个版本的 lookAt 重载。 */
    private static void lookAt(NumenPlayer her, Entity aim) {
        lookAt(her, aim.getX(), aim.getEyeY(), aim.getZ());
    }

    private static void lookAt(NumenPlayer her, double x, double y, double z) {
        double dx = x - her.getX();
        double dy = y - her.getEyeY();
        double dz = z - her.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        her.setYRot(yaw);
        her.setXRot(pitch);
        her.setYHeadRot(yaw);
    }

    private NumenPlayer findSelf(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(companion);
        return player instanceof NumenPlayer her ? her : null;
    }
}

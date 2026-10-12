package com.wwdele.numentoolbox.internal;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.Pending;
import com.wwdele.numentoolbox.sbw.SbwApi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 一次开火:替她扣扳机,一直扣到打完指定发数、没弹药、或太久没进展。
 *
 * <p>每刻按卓越前线的 {@code GunShootGoal} 那条路走:推进枪的状态、该换弹换弹、该拉栓拉栓,能开火就
 * {@code shoot}。射速按 RPM 折算成刻数,免得一发放出去就立刻再来一发。点名的目标由 {@link EntityRef} 每刻重认,
 * 走开或没了就不再瞄它。
 */
public final class GunController implements Tickers.Ticker {

    /** 一直打不出去、也没弹药时,最多再等多久(游戏刻)。 */
    private static final int MAX_IDLE = 20 * 20;

    private final UUID companion;
    private final Pending<SbwApi.Fired> pending;
    private final int maxShots;
    private final EntityRef target;

    private int fired;
    private int cooldown;
    private int idle;

    public GunController(UUID companion, Pending<SbwApi.Fired> pending, int maxShots, EntityRef target) {
        this.companion = companion;
        this.pending = pending;
        this.maxShots = maxShots;
        this.target = target;
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
        data.tick(her, true);

        Entity aim = target == null ? null : target.in(her.serverLevel());
        if (aim != null) {
            aimAt(her, aim);
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

        if (!data.reloading() && cooldown <= 0 && data.canShoot(her)) {
            int rpm = Math.max(1, data.get(GunProp.RPM));
            cooldown = Math.max(1, (int) Math.round(1200.0 / rpm));
            data.shoot(her, 0.0, false, aim == null ? null : aim.getUUID());
            fired++;
            idle = 0;
            if (fired >= maxShots) {
                pending.complete(new SbwApi.Fired(fired));
                return false;
            }
            return true;
        }

        return idleOrDone(her, data);
    }

    @Override
    public void cancel() {
        pending.fail(new ApiError(ErrorKind.INTERRUPTED, "the shooting was stopped", null));
    }

    private boolean idleOrDone(NumenPlayer her, GunData data) {
        if (!data.hasEnoughAmmoToShoot(her) && data.countBackupAmmo(her) <= 0) {
            if (fired > 0) {
                pending.complete(new SbwApi.Fired(fired));
                return false;
            }
            pending.fail(new ApiError(ErrorKind.NO_MATERIAL, "the weapon is out of ammo",
                    Call.of("toolbox.sbw.reload")));
            return false;
        }
        if (++idle > MAX_IDLE) {
            pending.complete(new SbwApi.Fired(fired));
            return false;
        }
        return true;
    }

    private NumenPlayer findSelf(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(companion);
        return player instanceof NumenPlayer her ? her : null;
    }

    /** 每刻把她的视线拧向目标:直接设偏航/俯仰,免得依赖某个版本的 lookAt 重载。 */
    private static void aimAt(NumenPlayer her, Entity aim) {
        double dx = aim.getX() - her.getX();
        double dy = aim.getEyeY() - her.getEyeY();
        double dz = aim.getZ() - her.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        her.setYRot(yaw);
        her.setXRot(pitch);
        her.setYHeadRot(yaw);
    }
}

package com.wwdele.numextra.sbw;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.Pending;
import com.wwdele.numextra.internal.Tickers;
import com.wwdele.numextra.internal.VehicleGunController;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.UUID;

/**
 * 她这一梭载具火力:限定发数或时长,让 {@link VehicleGunController} 去索敌、预判、转炮塔、开火,到点了如实收尾。
 *
 * <p>点名了目标就只打那一个;没点名就由火控自己挑最近的敌人。打光、超时、她下了车、炮弹没了、她被叫停,
 * 任何一种都让还在等的那次调用有个结论——吊着不结是最难查的一种卡。
 */
public final class VolleyController implements Tickers.Ticker {

    /** 已经交回过一次了:之后哪条路再来都不再啰嗦。 */
    private boolean settled;

    private final UUID companion;
    private final VehicleEntity vehicle;
    private final Pending<VehicleApi.Volley> pending;
    private final int maxShots;
    private final long maxTicks;
    private final EntityRef named;

    private final VehicleGunController gun;
    private int shots;
    private int ticks;
    private Entity last;

    public VolleyController(UUID companion, VehicleEntity vehicle, Pending<VehicleApi.Volley> pending,
                            int maxShots, long maxTicks, EntityRef named) {
        this.companion = companion;
        this.vehicle = vehicle;
        this.pending = pending;
        this.maxShots = maxShots;
        this.maxTicks = maxTicks;
        this.named = named;
        this.gun = new VehicleGunController(companion, vehicle, new Bridge());
    }

    @Override
    public boolean tick(net.minecraft.server.MinecraftServer server) {
        if (ticks >= maxTicks) {
            settle("time_up");
            return false;
        }
        if (shots >= maxShots) {
            settle("done");
            return false;
        }
        ticks++;
        return gun.tick(server);
    }

    @Override
    public void cancel() {
        settle("interrupted");
    }

    /** 交回这一次的结果;只交一次。 */
    private void settle(String because) {
        if (settled) {
            return;
        }
        settled = true;
        pending.complete(new VehicleApi.Volley(shots, id(last), because));
    }

    private Optional<Integer> id(Entity entity) {
        return entity == null ? Optional.empty() : Optional.of(entity.getId());
    }

    /** 火控每打一发、或它自己收工时,都回到这里来。 */
    private final class Bridge implements VehicleGunController.VehicleFire {

        @Override
        public boolean keepGoing() {
            if (settled || ticks >= maxTicks || shots >= maxShots) {
                return false;
            }
            NumenPlayer her = self();
            if (her == null || !her.isAlive() || her.getVehicle() != vehicle) {
                return false;
            }
            return named == null || stillNamed();
        }

        @Override
        public void fired(int count, Entity target) {
            shots += count;
            last = target;
        }

        @Override
        public void offVehicle() {
            settle("off_vehicle");
        }

        @Override
        public void waitingForAmmo() {
            settle("no_ammo");
        }

        @Override
        public void idleOut() {
            settle("no_target");
        }

        @Override
        public void stopped() {
            settle("interrupted");
        }
    }

    /** 点名的那一个还在不在。 */
    private boolean stillNamed() {
        NumenPlayer her = self();
        if (her == null) {
            return false;
        }
        Entity want = named.in(her.serverLevel());
        return want != null && want.isAlive();
    }

    private NumenPlayer self() {
        net.minecraft.server.MinecraftServer server = vehicle.getServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(companion) instanceof NumenPlayer her ? her : null;
    }
}

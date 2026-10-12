package com.wwdele.numentoolbox.internal;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.Pending;
import com.wwdele.numentoolbox.sbw.SbwApi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 一次换弹:让她手里的卓越前线武器拉栓、装弹,一直等到弹匣重新有弹(或超时)。
 *
 * <p>没备弹、手里不是枪、武器被收起来都如实失败。同伴离场、死亡、被打断时由 {@link Tickers} 取消。
 */
public final class ReloadController implements Tickers.Ticker {

    /** 换弹最多等多久(游戏刻)。 */
    private static final int MAX_TICKS = 20 * 30;

    private final UUID companion;
    private final Pending<SbwApi.Reloaded> pending;

    private boolean started;
    private int ticks;

    public ReloadController(UUID companion, Pending<SbwApi.Reloaded> pending) {
        this.companion = companion;
        this.pending = pending;
    }

    @Override
    public boolean tick(MinecraftServer server) {
        NumenPlayer her = findSelf(server);
        if (her == null || !her.isAlive()) {
            pending.fail(new ApiError(ErrorKind.INTERRUPTED, "you are no longer here to reload", null));
            return false;
        }
        ItemStack stack = her.getMainHandItem();
        if (!(stack.getItem() instanceof GunItem)) {
            pending.fail(new ApiError(ErrorKind.NO_MATERIAL, "you put the weapon away", null));
            return false;
        }
        GunData data = GunData.from(stack);
        data.tick(her, true);

        if (!started) {
            if (data.countBackupAmmo(her) <= 0) {
                pending.fail(new ApiError(ErrorKind.NO_MATERIAL, "you carry no ammo for this weapon",
                        Call.of("toolbox.items.search", "ammo")));
                return false;
            }
            data.startReload();
            started = true;
            return true;
        }

        if (data.shouldStartBolt()) {
            data.startBolt();
        }

        if (!data.reloading()) {
            pending.complete(new SbwApi.Reloaded(data.getAmmo().get()));
            return false;
        }
        if (++ticks > MAX_TICKS) {
            pending.complete(new SbwApi.Reloaded(data.getAmmo().get()));
            return false;
        }
        return true;
    }

    @Override
    public void cancel() {
        pending.fail(new ApiError(ErrorKind.INTERRUPTED, "the reload was stopped", null));
    }

    private NumenPlayer findSelf(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(companion);
        return player instanceof NumenPlayer her ? her : null;
    }
}

package com.wwdele.numentoolbox.internal;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Pending;
import com.wwdele.numentoolbox.api.ElytraApi;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 一次滑翔:给她穿上鞘翅、让她离地起飞,然后每刻把视线对准目标、由原版的滑翔物理带她过去。
 *
 * <p>{@code target} 为 null 是"只起飞":一旦她在滑翔就交回。有目标时一直掌舵到到达、落地或超时。
 * 占身体,所以她手上的其他活会被顶掉;同伴离场、死亡、被主人打断时由 {@link Tickers} 取消。
 */
public final class GlideController implements Tickers.Ticker {

    /** 一次滑翔最多持续多久(游戏刻)。 */
    private static final int MAX_TICKS = 20 * 120;
    /** 在地面反复起跳最多几次,再不行就认栽。 */
    private static final int MAX_TAKEOFF_TRIES = 20;

    private final UUID companion;
    private final Pending<ElytraApi.Arrived> pending;
    private final Vec3 target;
    private final double arrive;

    private int ticks;
    private int takeoffTries;

    private GlideController(UUID companion, Pending<ElytraApi.Arrived> pending, Vec3 target, double arrive) {
        this.companion = companion;
        this.pending = pending;
        this.target = target;
        this.arrive = arrive;
    }

    /** 给这只同伴安排一次滑翔,返回等它的那个值。 */
    public static Pending<ElytraApi.Arrived> start(NumenPlayer her, Vec3 target, double arrive) {
        Pending<ElytraApi.Arrived> pending = Pending.create();
        Tickers.add(her.getUUID(), new GlideController(her.getUUID(), pending, target, arrive));
        return pending;
    }

    @Override
    public boolean tick(MinecraftServer server) {
        NumenPlayer her = findSelf(server);
        if (her == null || !her.isAlive()) {
            pending.fail(new ApiError(ErrorKind.INTERRUPTED, "you are no longer here to glide", null));
            return false;
        }
        if (!ensureElytra(her)) {
            pending.fail(new ApiError(ErrorKind.NO_MATERIAL, "you carry no elytra",
                    com.dwinovo.numen.sdk.Call.of("toolbox.items.search", "elytra")));
            return false;
        }

        // 起飞:在地面就先跳起来,在空中就打开滑翔
        if (!her.isFallFlying()) {
            if (her.onGround()) {
                her.setDeltaMovement(her.getDeltaMovement().x, 0.42, her.getDeltaMovement().z);
                her.hasImpulse = true;
                if (++takeoffTries > MAX_TAKEOFF_TRIES) {
                    pending.fail(new ApiError(ErrorKind.FAILED, "could not leave the ground to glide", null));
                    return false;
                }
                return true;
            }
            her.startFallFlying();
        }

        // 只起飞:滑翔一打开就交回
        if (target == null) {
            if (her.isFallFlying()) {
                pending.complete(new ElytraApi.Arrived(her.position(), true));
                return false;
            }
            return true;
        }

        steer(her, target);
        if (arrived(her, target, arrive)) {
            pending.complete(new ElytraApi.Arrived(her.position(), true));
            return false;
        }
        if (her.onGround()) {
            pending.complete(new ElytraApi.Arrived(her.position(), false));
            return false;
        }
        if (++ticks > MAX_TICKS) {
            pending.complete(new ElytraApi.Arrived(her.position(), false));
            return false;
        }
        return true;
    }

    @Override
    public void cancel() {
        pending.fail(new ApiError(ErrorKind.INTERRUPTED, "the glide was stopped", null));
    }

    /** 背上是不是鞘翅。 */
    public static boolean wearing(NumenPlayer her) {
        return her.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    /** 把背包里的鞘翅穿到背上;没有就返回 false。换下来的胸甲回背包。 */
    public static boolean ensureElytra(NumenPlayer her) {
        if (wearing(her)) {
            return true;
        }
        Inventory inv = her.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.ELYTRA)) {
                ItemStack old = her.getItemBySlot(EquipmentSlot.CHEST).copy();
                her.setItemSlot(EquipmentSlot.CHEST, stack.copy());
                inv.setItem(i, old);
                return true;
            }
        }
        return false;
    }

    private static void steer(NumenPlayer her, Vec3 target) {
        Vec3 from = her.position();
        Vec3 delta = target.subtract(from);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.max(0.001, horizontal)));
        her.setYRot(yaw);
        her.setXRot(pitch);
        her.setYHeadRot(yaw);
        her.yRotO = yaw;
        her.xRotO = pitch;
    }

    private static boolean arrived(NumenPlayer her, Vec3 target, double arrive) {
        Vec3 delta = target.subtract(her.position());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        return horizontal <= arrive && Math.abs(delta.y) <= Math.max(arrive, 4.0);
    }

    private NumenPlayer findSelf(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(companion);
        return player instanceof NumenPlayer her ? her : null;
    }
}

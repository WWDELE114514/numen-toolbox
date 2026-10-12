package com.wwdele.numentoolbox.internal;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 每个同伴身上最多一个的"每刻控制器":开火、滑翔这类要跨很多刻、还要占用身体的事。
 *
 * <p>登记在 {@code ServerTickEvent.Post} 上,由 {@link #tick} 每刻推进。控制器返回 {@code false} 表示它做完了,
 * 这里把它摘掉;抛异常也当做完,免得一个坏控制器每刻刷屏。同伴离场、死亡、被主人打断时,{@link #cancel} 会把它摘掉
 * 并让它在等的那次调用如实失败。
 */
public final class Tickers {

    /** 每刻被推进的一件事。 */
    public interface Ticker {

        /** @return true 继续,false 结束。 */
        boolean tick(MinecraftServer server);

        /** 不跑了(同伴没了、被打断):把还在等的调用以中断收尾。 */
        default void cancel() {}
    }

    private static final Map<UUID, Ticker> ACTIVE = new HashMap<>();

    private Tickers() {}

    /** 给这只同伴装一个控制器;它身上原有的那个先被取消。 */
    public static void add(UUID companion, Ticker ticker) {
        Ticker old = ACTIVE.put(companion, ticker);
        if (old != null) {
            old.cancel();
        }
    }

    public static boolean busy(UUID companion) {
        return ACTIVE.containsKey(companion);
    }

    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Ticker>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Ticker ticker = it.next().getValue();
            boolean keep;
            try {
                keep = ticker.tick(server);
            } catch (Throwable thrown) {
                keep = false;
            }
            if (!keep) {
                it.remove();
            }
        }
    }

    public static void cancel(NumenPlayer companion) {
        if (companion != null) {
            cancelById(companion.getUUID());
        }
    }

    public static void cancelById(UUID companion) {
        Ticker ticker = ACTIVE.remove(companion);
        if (ticker != null) {
            ticker.cancel();
        }
    }
}

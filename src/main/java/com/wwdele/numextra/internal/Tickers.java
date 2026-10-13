package com.wwdele.numextra.internal;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个同伴身上最多一个的"每刻控制器":开火、滑翔这类要跨很多刻、还要占用身体的事。
 *
 * <p>登记在 {@code ServerTickEvent.Post} 上,由 {@link #tick} 每刻推进。控制器返回 {@code false} 表示它做完了,
 * 这里把它摘掉;抛异常也当做完并如实收尾,免得一个坏控制器每刻刷屏、把等它的那次调用永远吊着。同伴离场、死亡、
 * 被主人打断时,{@link #cancel} 会把它摘掉并让它在等的那次调用如实失败。
 *
 * <p>{@link #add} 可能在权限回调里被调用(未必与 {@link #tick} 同一条线程),所以表用并发实现、迭代取弱一致视图:
 * 遍历途中新装的控制器最迟下一刻生效,不会撞成 {@code ConcurrentModificationException}。
 */
public final class Tickers {

    private static final Logger LOG = LoggerFactory.getLogger("numen_extra");

    /** 每刻被推进的一件事。 */
    public interface Ticker {

        /** @return true 继续,false 结束。 */
        boolean tick(MinecraftServer server);

        /** 不跑了(同伴没了、被打断、内部出错):把还在等的调用以中断收尾。 */
        default void cancel() {}
    }

    private static final Map<UUID, Ticker> ACTIVE = new ConcurrentHashMap<>();

    private Tickers() {}

    /** 给这只同伴装一个控制器;它身上原有的那个先被取消。 */
    public static void add(UUID companion, Ticker ticker) {
        Ticker old = ACTIVE.put(companion, ticker);
        if (old != null) {
            cancelQuietly(old);
        }
    }

    public static boolean busy(UUID companion) {
        return ACTIVE.containsKey(companion);
    }

    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Ticker> entry : ACTIVE.entrySet()) {
            Ticker ticker = entry.getValue();
            boolean keep;
            try {
                keep = ticker.tick(server);
            } catch (Throwable thrown) {
                LOG.error("numen_extra: per-tick controller for {} threw; ending it", entry.getKey(), thrown);
                keep = false;
                cancelQuietly(ticker);
            }
            if (!keep) {
                // 只摘掉仍是它的那格,避免误删这一刻里刚换上的新控制器
                ACTIVE.remove(entry.getKey(), ticker);
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
            cancelQuietly(ticker);
        }
    }

    /**
     * 让一个控制器收尾。它可能那一刻刚好定局(完成或失败过),那次调用自有结论,这里再抛就不再纠缠:收尾的异常若从这里冒出去,
     * 尤其 {@link #add} 是在权限回调那条未必与 {@link #tick} 同线程的路上被调用的,没人接住会把服务器整刻带崩。
     */
    private static void cancelQuietly(Ticker ticker) {
        try {
            ticker.cancel();
        } catch (Throwable ignored) {
            // 已经定局,不纠缠
        }
    }
}

package com.wwdele.numentoolbox;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.entity.NumenCommands;
import com.wwdele.numentoolbox.api.ElytraApi;
import com.wwdele.numentoolbox.api.ItemsApi;
import com.wwdele.numentoolbox.api.ModsApi;
import com.wwdele.numentoolbox.command.ToolboxCommands;
import com.wwdele.numentoolbox.fix.UseItemFix;
import com.wwdele.numentoolbox.internal.Tickers;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 与言出法随的对接代码,单独一个类,只在言出法随在场时才被 {@link NumenToolbox} 调用。
 *
 * <p>卓越前线的对接再单独一层:{@code sbw} 那一段只有装了卓越前线才走,没装时那一段的类一次都不会被加载。
 */
public final class ToolboxPlugin {

    /** 言出法随的 use.item 返回类型补丁装上没有;它登记完函数才能补,所以挪到服务器第一次刻。 */
    private static volatile boolean useItemFixApplied;

    private ToolboxPlugin() {}

    public static void install(IEventBus gameBus) {
        // 服务端每刻推进开火/滑翔控制器,并补一次言出法随 use.item 的返回类型 bug
        gameBus.addListener((ServerTickEvent.Post event) -> {
            if (!useItemFixApplied) {
                useItemFixApplied = UseItemFix.install();
            }
            Tickers.tick(event.getServer());
        });
        // /numen op|deop:挂到言出法随自己的 /numen 根下
        gameBus.addListener(ToolboxPlugin::registerCommands);

        NumenPlugins.register(NumenToolbox.NAMESPACE, numen -> {
            numen.api("mods", "What mods this server has loaded.", ModsApi.class);
            numen.api("items", "Search items across every loaded mod.", ItemsApi.class);
            numen.api("elytra", "Wear an elytra and glide: take off, steer toward a point, land.", ElytraApi.class);

            // 卓越前线只有装了才碰它的类
            if (ModList.get().isLoaded("superbwarfare")) {
                com.wwdele.numentoolbox.sbw.SbwSupport.install(numen);
            }

            // 精妙背包的存储按 UUID 存在世界里,原版的读容器看不见它,单独给一组工具
            if (ModList.get().isLoaded("sophisticatedbackpacks")) {
                com.wwdele.numentoolbox.pack.PackSupport.install(numen);
            }

            // 随模组发布的技能
            Path skills = ModList.get().getModFileById(NumenToolbox.MOD_ID).getFile().findResource("skills");
            if (Files.isDirectory(skills)) {
                numen.bundleSkills(skills);
            }

            // 同伴离场/死亡/被打断:停掉它身上还没跑完的控制器
            numen.on(CompanionEvent.REMOVE, Tickers::cancel);
            numen.on(CompanionEvent.DEATH, Tickers::cancel);
            numen.on(CompanionEvent.ABORT, Tickers::cancelById);
        });
    }

    /**
     * 往言出法随的 {@code /numen} 根下挂 {@code op} 与 {@code deop}。用它自己的 {@code graft}:根由它建、带它的观众规则
     * (只给玩家、不给她),我们只加两格,和它内建的命令走同一条路,不必也不该自己再注册一个 {@code numen} 根。
     */
    private static void registerCommands(RegisterCommandsEvent event) {
        NumenCommands.graft(event.getDispatcher(), ToolboxCommands.op());
        NumenCommands.graft(event.getDispatcher(), ToolboxCommands.deop());
    }
}

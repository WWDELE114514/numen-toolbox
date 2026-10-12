package com.wwdele.numentoolbox;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenPlugins;
import com.wwdele.numentoolbox.api.ElytraApi;
import com.wwdele.numentoolbox.api.ItemsApi;
import com.wwdele.numentoolbox.api.ModsApi;
import com.wwdele.numentoolbox.command.ToolboxCommands;
import com.wwdele.numentoolbox.internal.Tickers;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 与言出法随的对接代码,单独一个类,只在言出法随在场时才被 {@link NumenToolbox} 调用。
 *
 * <p>卓越前线的对接再单独一层:{@code sbw} 那一段只有装了卓越前线才走,没装时那一段的类一次都不会被加载。
 */
public final class ToolboxPlugin {

    private ToolboxPlugin() {}

    public static void install(IEventBus gameBus) {
        // 服务端每刻推进开火/滑翔控制器
        gameBus.addListener((ServerTickEvent.Post event) -> Tickers.tick(event.getServer()));

        NumenPlugins.register(NumenToolbox.NAMESPACE, numen -> {
            numen.api("mods", "What mods this server has loaded.", ModsApi.class);
            numen.api("items", "Search items across every loaded mod.", ItemsApi.class);
            numen.api("elytra", "Wear an elytra and glide: take off, steer toward a point, land.", ElytraApi.class);

            // 卓越前线只有装了才碰它的类
            if (ModList.get().isLoaded("superbwarfare")) {
                com.wwdele.numentoolbox.sbw.SbwSupport.install(numen);
            }

            // /numen op <name> [level] 与 /numen deop <name>
            numen.command(ToolboxCommands.op());
            numen.command(ToolboxCommands.deop());

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
}

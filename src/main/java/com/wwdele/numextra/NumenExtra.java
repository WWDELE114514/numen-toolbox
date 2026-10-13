package com.wwdele.numextra;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Numen:Extra —— 言出法随(Numen)的附属模组。
 *
 * <p>它给智能体补上几件事:看见服务器装了哪些模组、跨模组搜索物品、存取精妙背包里的东西、使用卓越前线
 * (SuperbWarfare)的武器、穿鞘翅飞行;另加一条 {@code /numen op|deop} 命令,把智能体的权限接到原版的 op 列表上。
 *
 * <p>本类是唯一的模组入口。只有装了言出法随,才去碰 {@link ExtraPlugin} 里的 Numen 类——没装时那一段一次都不会被加载,
 * 也就不会因为找不到 Numen 的类而崩。
 */
@Mod(NumenExtra.MOD_ID)
public final class NumenExtra {

    public static final String MOD_ID = "numen_extra";

    /** 智能体在 Lua 里看到的名字空间:{@code toolbox.mods.*}、{@code toolbox.items.*}、{@code toolbox.backpack.*}。 */
    public static final String NAMESPACE = "toolbox";

    public NumenExtra(IEventBus modBus) {
        if (ModList.get().isLoaded("numen_api")) {
            ExtraPlugin.install(NeoForge.EVENT_BUS);
        }
    }
}

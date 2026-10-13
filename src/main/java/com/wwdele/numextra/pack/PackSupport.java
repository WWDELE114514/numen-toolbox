package com.wwdele.numextra.pack;

import com.dwinovo.numen.api.NumenApi;

/**
 * 精妙背包(Sophisticated Backpacks)的对接代码,单独一个类。
 *
 * <p>只在 {@link com.wwdele.numextra.ToolboxPlugin} 确认服务器装了 {@code sophisticatedbackpacks} 之后才被调用,
 * 于是没装这个模组时,这一整个包里的类一次都不会被加载,也就不会因为找不到它的类而崩。
 */
public final class PackSupport {

    private PackSupport() {}

    public static void install(NumenApi numen) {
        numen.api("backpack", "Sophisticated Backpacks: see the ones you have and move items in and out of them.",
                BackpackApi.class);
    }
}

package com.wwdele.numextra.sbw;

import com.dwinovo.numen.api.NumenApi;

/**
 * 卓越前线(SuperbWarfare)的对接代码,单独一个类。
 *
 * <p>只在 {@link com.wwdele.numextra.ExtraPlugin} 确认服务器装了 {@code superbwarfare} 之后才被调用,
 * 于是没装这个模组时,这一整个包里的类一次都不会被加载,也就不会因为找不到它的类而崩。
 */
public final class SbwSupport {

    private SbwSupport() {}

    public static void install(NumenApi numen) {
        numen.api("sbw", "SuperbWarfare weapons: list them, inspect one, fire and reload the one you hold.",
                SbwApi.class);
        numen.api("vehicles", "The SuperbWarfare vehicle you ride: what its cannon can do, and firing it with a "
                + "lead solution so the shell lands on a moving target.", VehicleApi.class);
    }
}

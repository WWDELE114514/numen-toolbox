package com.wwdele.numentoolbox.api;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import com.wwdele.numentoolbox.internal.GlideController;
import com.wwdele.numentoolbox.internal.Tickers;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * {@code toolbox.elytra}:穿鞘翅飞行——穿上、起飞、滑翔到某点、降落。
 *
 * <p>都在服务端操作她的身体。起飞与滑翔是占身体的活:一次调用等到滑翔结束(或到达)才回来,期间由每刻的
 * {@link GlideController} 替她掌舵。
 */
public final class ElytraApi {

    private ElytraApi() {}

    /** 她的鞘翅状态。 */
    @Doc("What is on your back and whether you are gliding.")
    public record Status(@Doc("Whether an elytra is on your back.") boolean wearing,
                         @Doc("Whether you are gliding now.") boolean gliding) {}

    @Fn("Whether you wear an elytra and are gliding now.")
    @Example("local s = toolbox.elytra.status()\nprint(s.wearing, s.gliding)")
    @Note("Instant and read-only.")
    @SeeAlso({"toolbox.elytra.wear", "toolbox.elytra.takeoff"})
    public static Status status(ServerCall call) {
        NumenPlayer her = call.her();
        return new Status(GlideController.wearing(her), her.isFallFlying());
    }

    /** 穿好了没有。 */
    public record Worn(@Doc("true once it is on your back.") boolean wearing) {}

    @Fn("Put an elytra from your backpack on your back.")
    @Example("toolbox.elytra.wear()")
    @Note("Instant. Fails with no_material when you carry no elytra; find one with toolbox.items.search(\"elytra\").")
    @SeeAlso({"toolbox.elytra.takeoff", "toolbox.items.search"})
    public static Worn wear(ServerCall call) {
        if (!GlideController.ensureElytra(call.her())) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "you carry no elytra",
                    Call.of("toolbox.items.search", "elytra"));
        }
        return new Worn(true);
    }

    /** 到哪儿了、到没到。 */
    @Doc("Where a glide ended.")
    public record Arrived(@Doc("Where you ended up.") Vec3 pos,
                          @Doc("true when you reached the target or took off; false when you landed or ran out of time.")
                          boolean reached) {}

    @Fn("Wear an elytra (if needed), jump and start gliding.")
    @Example("toolbox.elytra.takeoff()")
    @Note("A body job: it returns as soon as you are gliding. Start from a high place, or you will land at once.")
    @SeeAlso({"toolbox.elytra.fly_to", "toolbox.elytra.land"})
    public static Pending<Arrived> takeoff(ServerCall call) {
        return GlideController.start(call.her(), null, 0.0);
    }

    /** 滑翔到哪儿、多近算到。 */
    public record FlyTo(@Doc("The point to glide to: {x = 120, y = 80, z = -35}.") Vec3 to,
                        @Doc("How close counts as arrived, in blocks.")
                        @Omitted("3") Optional<Double> arrive) {}

    @Fn("Glide toward a point, steering all the way.")
    @Example("toolbox.elytra.fly_to({x = 120, y = 80, z = -35})")
    @Note("A body job: it steers you toward the point each tick and returns when you arrive, land, or run out of time.")
    @Note("Reaching a point higher than you is not always possible while gliding: pick a lower one, or land first.")
    @SeeAlso({"toolbox.elytra.takeoff", "toolbox.elytra.land"})
    public static Pending<Arrived> flyTo(ServerCall call, FlyTo args) {
        double arrive = Math.max(1.0, args.arrive().orElse(3.0));
        return GlideController.start(call.her(), args.to(), arrive);
    }

    /** 落了没有。 */
    public record Landed(@Doc("true once you are no longer gliding.") boolean landed) {}

    @Fn("Stop gliding and drop.")
    @Example("toolbox.elytra.land()")
    @Note("Instant: it cuts the glide, then gravity takes over.")
    @SeeAlso({"toolbox.elytra.takeoff", "toolbox.elytra.fly_to"})
    public static Landed land(ServerCall call) {
        NumenPlayer her = call.her();
        Tickers.cancelById(her.getUUID());
        her.stopFallFlying();
        return new Landed(!her.isFallFlying());
    }
}

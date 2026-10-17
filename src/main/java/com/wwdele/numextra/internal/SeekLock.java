package com.wwdele.numextra.internal;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.SeekType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 索敌武器(地对空导弹、标枪这类)的锁定。它们打得出去有前提,不像普通枪那样抬手就能打:
 *
 * <ol>
 *   <li><b>得开着镜</b>:{@code SeekType.HoldZoom} 的武器,服务端只认枪数据里的 {@code zooming};客户端是按住瞄准键
 *       攒够 0.7 秒才把这个标记发上来的。我们直接在服务端把这个标记立起来,一样的效果。</li>
 *   <li><b>得锁住目标</b>:视线与目标的夹角要一直落在 {@code SeekAngle} 里,持续 {@code SEEK_TIME} 刻以上,
 *       才算锁定(客户端还给 {@code +2} 刻的余量)。这中间不能进烟、不能被挡住。</li>
 *   <li><b>锁定完成才发得出去</b>:而且发射必须带上锁定的那个目标,导弹靠它制导。</li>
 * </ol>
 *
 * <p>所以这类武器开火的节奏不是"多久一发",而是"重新锁一次要多久"。锁定被打断(目标跑出射界、被烟挡住、视线丢了)
 * 就得从头再来——{@link #reset()} 把这笔账清掉。
 */
public final class SeekLock {

    /** 视线与目标夹角要落在这么多度内,才算正在锁(取这门武器自己的 SeekAngle)。 */
    private final double seekAngle;
    /** 锁多久才算锁住(刻):武器自己的 SeekTime,客户端另给 2 刻余量。 */
    private final int seekTime;

    /** 已经连续锁了多少刻。 */
    private int lockTicks;
    /** 是不是已经锁住了。 */
    private boolean locked;
    /** 上次锁的是谁;换了人就得重新锁。 */
    private Entity lockTarget;

    public SeekLock(GunData data) {
        Double angle = data.get(GunProp.SEEK_ANGLE);
        this.seekAngle = angle == null || angle <= 0 ? 20.0 : angle;
        Integer time = data.get(GunProp.SEEK_TIME);
        this.seekTime = time == null ? 20 : time;
    }

    /** 这把武器要不要先锁定:只有索敌类要,普通枪抬手就能打。 */
    public static boolean needsLock(GunData data) {
        SeekType type = data.get(GunProp.SEEK_TYPE);
        return type == SeekType.HOLD_ZOOM || type == SeekType.HOLD_FIRE;
    }

    /**
     * 开着镜。{@code HoldZoom} 的服务端只认枪数据里的 {@code zooming} 标记,把它立起来即可——
     * 不必真发一个客户端的瞄准键包(她也没有真的客户端)。
     */
    public void zoom(GunData data) {
        data.zooming.set(true);
    }

    /**
     * 这一刻的锁定进展:视线还在 {@link #seekAngle} 内、目标还是同一个,就往上累;跑出去了、换人了、烟挡了,
     * 都归零。累够 {@link #seekTime} 加客户端那 2 刻余量,才算锁住。
     *
     * @param her  持武器或坐在这一座里的人
     * @param aim  正在瞄的目标
     * @return 是不是已经锁住了
     */
    public boolean advance(LivingEntity her, Entity aim) {
        if (aim == null) {
            reset();
            return false;
        }
        if (lockTarget != aim) {
            reset();
            lockTarget = aim;
        }
        double toTarget = angleTo(her, aim);
        if (toTarget > seekAngle) {
            lockTicks = 0;
            return false;
        }
        lockTicks++;
        if (lockTicks >= seekTime + 2) {
            locked = true;
        }
        return locked;
    }

    /** 锁没锁上。 */
    public boolean locked() {
        return locked;
    }

    /** 锁了几分之几:给回执里说"还在锁"用。 */
    public double progress() {
        return Math.min(1.0, lockTicks / (double) Math.max(1, seekTime + 2));
    }

    /** 锁被打断:换目标、目标跑出射界、被烟挡住,都从零开始。 */
    public void reset() {
        lockTicks = 0;
        locked = false;
        lockTarget = null;
    }

    /** 她此刻的视线,与指向目标的视线,差多少度。 */
    private static double angleTo(LivingEntity her, Entity aim) {
        Vec3 want = her.getEyePosition().vectorTo(aim.getBoundingBox().getCenter()).normalize();
        Vec3 at = her.getViewVector(1f).normalize();
        return Math.toDegrees(Math.acos(Math.clamp(at.dot(want), -1.0, 1.0)));
    }
}

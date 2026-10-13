package com.wwdele.numextra.fix;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.sdk.ApiFunction;
import com.dwinovo.numen.sdk.ApiRegistry;
import com.dwinovo.numen.sdk.Codec;
import com.dwinovo.numen.sdk.LuaCodecs;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.List;

/**
 * 不改言出法随本体,补掉 {@code numen.use.item} 的返回类型 bug。
 *
 * <p>言出法随 0.1.4.1 把这个函数的返回类型声明成了 {@code Clicks.Clicked},于是它的返回编码器是一个
 * {@code Clicked} 的 record 编码器。可朝前用物品右键一旦打开界面(精妙背包、末影箱、工作台……),短活真正交回的是
 * {@code GuiOps.Window}——它属于 {@code Clicks.Pressed},不是 {@code Clicked}。派发器拿 {@code Clicked} 的编码器去编码
 * 一个 {@code Window},抛 {@code IllegalArgumentException: object is not an instance of declaring class},这异常冒泡到
 * 服务器整刻,直接把服务器带崩。
 *
 * <p>这里把这个函数的返回编码器换成一个"按运行期类型编码"的包装:{@link LuaCodecs#encode(Object)} 本来就按值的真实类型
 * 找编码器,{@code Window} 与 {@code Clicked} 都能正确交回。函数本身、参数、别的函数都不动。言出法随哪天自己修好了,
 * 这层包装照样正确(只是多绕一步),可以留着。
 */
public final class UseItemFix {

    /** 出问题的那个函数;它(错误地)声明成 Clicks.Clicked。 */
    private static final String TARGET = "numen.use.item";

    /** 返回编码器所在的私有 final 字段名。 */
    private static final String RETURNS = "returns";

    private UseItemFix() {}

    /**
     * 装上补丁。要等言出法随登记完函数之后调,所以放在服务器第一次刻里。
     *
     * @return 已经修好(或本来就正常)是 {@code true};函数还没登记、或实在写不进去是 {@code false},调用方下次再试
     */
    public static boolean install() {
        ApiFunction function = ApiRegistry.function(TARGET);
        if (function == null) {
            return false;
        }
        Codec<Object> returns = function.returns();
        if (returns instanceof RuntimeEncoded) {
            return true;
        }
        return replaceReturns(function, new RuntimeEncoded(returns));
    }

    /** 字段是 final,依次试三条路,哪条能写就用哪条。 */
    private static boolean replaceReturns(ApiFunction function, Codec<Object> codec) {
        return writeField(function, codec)
                || writeFieldWithImplLookup(function, codec)
                || writeFieldWithUnsafe(function, codec);
    }

    /** 同一个类加载器里的无名模块之间完全开放,这条路通常就成了。 */
    private static boolean writeField(ApiFunction function, Codec<Object> codec) {
        try {
            VarHandle handle = MethodHandles.privateLookupIn(ApiFunction.class, MethodHandles.lookup())
                    .findVarHandle(ApiFunction.class, RETURNS, Codec.class);
            handle.set(function, codec);
            return true;
        } catch (Throwable refused) {
            return false;
        }
    }

    /** 言出法随的启动参数给 java.base/java.lang.invoke 开了口子(Mixin 要用),拿它的可信查找写那一格。 */
    private static boolean writeFieldWithImplLookup(ApiFunction function, Codec<Object> codec) {
        try {
            Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            field.setAccessible(true);
            MethodHandles.Lookup trusted = (MethodHandles.Lookup) field.get(null);
            VarHandle handle = trusted.findVarHandle(ApiFunction.class, RETURNS, Codec.class);
            handle.set(function, codec);
            return true;
        } catch (Throwable refused) {
            return false;
        }
    }

    /** 最后一道:sun.misc.Unsafe 绕开 final。 */
    private static boolean writeFieldWithUnsafe(ApiFunction function, Codec<Object> codec) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            long offset = (Long) unsafeClass.getMethod("objectFieldOffset", Field.class)
                    .invoke(unsafe, ApiFunction.class.getDeclaredField(RETURNS));
            unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(unsafe, function, offset, codec);
            return true;
        } catch (Throwable refused) {
            return false;
        }
    }

    /** 按值的运行期类型编码;读与类型说明照旧。 */
    private static final class RuntimeEncoded implements Codec<Object> {

        private final Codec<Object> delegate;

        RuntimeEncoded(Codec<Object> delegate) {
            this.delegate = delegate;
        }

        @Override
        public ScriptType type() {
            return delegate.type();
        }

        @Override
        public Object decode(Object value) {
            return delegate.decode(value);
        }

        @Override
        public Object encode(Object value) {
            return value == null ? null : LuaCodecs.encode(value);
        }

        @Override
        public List<ScriptType.Class> classes() {
            return delegate.classes();
        }
    }
}

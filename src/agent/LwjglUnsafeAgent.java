package com.deepslate.yhcl;

import java.lang.instrument.Instrumentation;

/**
 * LWJGL Unsafe Agent：游戏进程启动最早期放开 sun.misc.Unsafe 内存访问限制。
 *
 * <p>新版 JDK（24+）对 sun.misc.Unsafe 的内存访问方法默认警告（26+ 默认禁止），
 * 旧版 LWJGL / Netty 依赖这些方法。限制档位由系统属性
 * {@code sun.misc.unsafe.memory.access}（allow/warn/debug/deny）决定。
 *
 * <p>注意：JDK 在 sun.misc.Unsafe 类初始化时通过 {@code VM.getSavedProperty}
 * 读取该属性，预置属性在启动命令行由启动器以
 * {@code -Dsun.misc.unsafe.memory.access=allow} 提供；本 Agent 的 premain
 * 再兜底设置一份实时属性，覆盖按普通属性惰性读取的实现。
 *
 * <p>必须以 Java 8 字节码编译：旧版游戏可能用 Java 8 启动，
 * 高版本类文件会让 VM 直接中止启动。
 */
public final class LwjglUnsafeAgent {

    private LwjglUnsafeAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        System.setProperty("sun.misc.unsafe.memory.access", "allow");
    }
}

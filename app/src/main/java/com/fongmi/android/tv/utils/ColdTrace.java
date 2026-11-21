package com.fongmi.android.tv.utils;

import android.os.SystemClock;
import android.util.Log;

import com.fongmi.android.tv.BuildConfig;

/**
 * 冷启动耗时埋点（仅 debug 构建输出，release 静默）。
 *
 * <p>用于定位「清除数据后首次冷启动主视觉出图慢」：每个节点打印相对进程内首次引用的
 * 累计耗时 {@code t} 与距上一节点的耗时 {@code +}，一行一个节点，便于直接对齐
 * {@code logcat -v time} 的系统时间戳。</p>
 *
 * <p>埋点全部走 {@link Log#i}：海信 VIDAA 等 ROM 会屏蔽 {@code Log.d}，统一用 i 级。</p>
 */
public final class ColdTrace {

    public static final String TAG = "ColdStart";

    /** 进程内基准：ColdTrace 首次被引用的时刻，早于 Application 构造。 */
    private static final long BASE = SystemClock.elapsedRealtime();
    private static long last = BASE;

    private ColdTrace() {
    }

    public static void mark(String node) {
        mark(node, "");
    }

    public static void mark(String node, String detail) {
        if (!BuildConfig.DEBUG) return;
        long now = SystemClock.elapsedRealtime();
        long cost = now - last;
        last = now;
        Log.i(TAG, node + " | t=" + (now - BASE) + "ms | +" + cost + "ms"
                + (detail == null || detail.isEmpty() ? "" : " | " + detail));
    }
}

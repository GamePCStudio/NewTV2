package com.fongmi.android.tv.ui.custom;

import android.app.ActivityManager;
import android.content.Context;

import com.fongmi.android.tv.App;

/**
 * 光效性能档位：低配电视自动降级，避免装饰性绘制挤占列表滚动与焦点切换的帧预算。
 *
 * <p>判定只在首次访问时做一次并缓存，依据系统低内存标记、物理内存总量与 CPU 核数，
 * 阈值取保守值，正常设备不会误判。降级内容由各光效组件自行读取本判定：减少渐变采样档数、
 * 缩短补间时长、关闭白框呼吸。不改变高配设备的任何视觉表现。</p>
 */
public final class GlowTier {

    /** 低于 1.5GB 物理内存视为低配；常见 2GB / 3GB 电视不触发。 */
    private static final long LOW_MEM_BYTES = 1536L * 1024 * 1024;
    private static volatile Boolean lowEnd;

    private GlowTier() {
    }

    public static boolean isLowEnd() {
        Boolean cached = lowEnd;
        if (cached != null) return cached;
        boolean low = Runtime.getRuntime().availableProcessors() <= 2;
        try {
            Context context = App.get();
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                if (manager.isLowRamDevice()) low = true;
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(info);
                if (info.totalMem > 0 && info.totalMem < LOW_MEM_BYTES) low = true;
            }
        } catch (Throwable ignored) {
            // 判定失败按高配处理：宁可多画几档，也不因为系统服务异常关掉整站光效。
        }
        lowEnd = low;
        return low;
    }
}

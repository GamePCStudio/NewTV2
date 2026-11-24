package com.fongmi.android.tv.utils;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页主视觉与海报卡的主色提取与缓存。
 *
 * <p>从剧照 / 海报 drawable 取一张<b>小尺寸</b>位图，用 {@code androidx.palette} 取
 * vibrant / muted swatch 作为漫反射主色。饱和度过低（灰片）不出彩色光，回透明。</p>
 *
 * <p><b>性能整改</b>：
 * <ul>
 *   <li>提色前把位图缩到最长边 96px（原来直接喂全尺寸剧照，getPixels + 量化分配巨大）；</li>
 *   <li>LRU 从 16 提到 96 条，降低快速切换时重复提色；</li>
 *   <li>同 URL 的并发请求去重：只跑一次 Palette，结果广播给所有等待者；每张卡的
 *       {@code boundPic/boundKey} 与 generation 判定仍在调用方，晚到旧结果按各自口径丢弃。</li>
 * </ul></p>
 *
 * <p>提色在固定双线程池里做（主视觉轮播 + 卡片各留一条通道），结果切回主线程。
 * 线程池与 LRU 跟随进程生命周期，是纯装饰组件的常驻成本，但规模极小。</p>
 */
public final class HeroGlowColor {

    private static final int SATURATION_FLOOR = 24;
    /** Palette 输入位图的最长边：96px 足够出 swatch，再大只是无谓的像素分配。 */
    private static final int SAMPLE_MAX_PX = 96;
    private static final LruCache<String, Integer> CACHE = new LruCache<>(96);
    /** 同 URL 进行中的提色任务：一张剧照就绪 + 聚焦回调可能同时触发，合并为一次。 */
    private static final Map<String, List<Callback>> IN_FLIGHT = new ConcurrentHashMap<>();
    // 双线程：主视觉轮播会连续提色，单线程会把卡片提色排队到好几秒之后。
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "hero-glow");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private HeroGlowColor() {
    }

    public interface Callback {
        void onColor(@ColorInt int color);
    }

    /**
     * 异步提取主色并回主线程。URL 为空 / drawable 无位图 / 灰片一律回 {@link Color#TRANSPARENT}。
     *
     * <p>同一个 URL 的并发调用会合并：先到者真正提色，后到者挂在等待列表上一起回传。
     * URL 为空（无法作为缓存 key）时不合并，各自独立处理。</p>
     */
    public static void load(@Nullable String url, @Nullable Drawable drawable, Callback callback) {
        if (callback == null) return;
        if (url != null) {
            Integer cached = CACHE.get(url);
            if (cached != null) {
                callback.onColor(cached);
                return;
            }
        }
        Bitmap bitmap = toBitmap(drawable);
        if (bitmap == null || bitmap.isRecycled()) {
            callback.onColor(Color.TRANSPARENT);
            return;
        }
        if (url == null) {
            dispatch(url, bitmap, singleton(callback));
            return;
        }
        // computeIfAbsent：同一 URL 只提交一次，其余回调排进等待列表。
        List<Callback> waiters = IN_FLIGHT.computeIfAbsent(url, key -> new ArrayList<>());
        boolean submit;
        synchronized (waiters) {
            submit = waiters.isEmpty();
            waiters.add(callback);
        }
        if (submit) dispatch(url, bitmap, waiters);
    }

    private static List<Callback> singleton(Callback callback) {
        List<Callback> list = new ArrayList<>(1);
        list.add(callback);
        return list;
    }

    /** 后台线程：缩图 → 量化 → 缓存 → 回主线程广播给所有等待者。 */
    private static void dispatch(@Nullable String url, Bitmap source, List<Callback> waiters) {
        EXECUTOR.execute(() -> {
            Bitmap sample = scaleDown(source);
            int color = extract(sample);
            if (sample != source) sample.recycle();
            if (url != null && color != Color.TRANSPARENT) CACHE.put(url, color);
            List<Callback> snapshot;
            synchronized (waiters) {
                snapshot = new ArrayList<>(waiters);
                waiters.clear();
            }
            if (url != null) IN_FLIGHT.remove(url, waiters);
            MAIN.post(() -> {
                for (Callback callback : snapshot) callback.onColor(color);
            });
        });
    }

    @Nullable
    private static Bitmap toBitmap(@Nullable Drawable drawable) {
        if (!(drawable instanceof BitmapDrawable)) return null;
        return ((BitmapDrawable) drawable).getBitmap();
    }

    /** 按比例缩到最长边不超过 96px；已经够小则原样返回（不分配新位图）。 */
    private static Bitmap scaleDown(Bitmap source) {
        int w = source.getWidth();
        int h = source.getHeight();
        int longest = Math.max(w, h);
        if (longest <= SAMPLE_MAX_PX) return source;
        float scale = SAMPLE_MAX_PX / (float) longest;
        return Bitmap.createScaledBitmap(
                source,
                Math.max(1, Math.round(w * scale)),
                Math.max(1, Math.round(h * scale)),
                true);
    }

    @ColorInt
    private static int extract(Bitmap source) {
        try {
            Palette palette = Palette.from(source).maximumColorCount(16).generate();
            Palette.Swatch swatch = palette.getVibrantSwatch();
            if (swatch == null) swatch = palette.getMutedSwatch();
            if (swatch == null) {
                // 没有候选色时取占比最大的 swatch，仍按饱和度门槛过滤。
                for (Palette.Swatch candidate : palette.getSwatches()) {
                    if (candidate == null) continue;
                    swatch = candidate;
                    break;
                }
            }
            if (swatch == null) return Color.TRANSPARENT;
            int rgb = swatch.getRgb();
            float[] hsv = new float[3];
            Color.colorToHSV(rgb, hsv);
            // 灰片或近灰片不出彩色光，与目标实现的 S<0.25 门槛同口径。
            return hsv[1] >= SATURATION_FLOOR / 255f ? rgb : Color.TRANSPARENT;
        } catch (Throwable ignored) {
            // 提色是纯装饰，任何异常都降级为不画，绝不影响主视觉本身。
            return Color.TRANSPARENT;
        }
    }
}

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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页主视觉的主色提取与缓存（方案 C）。
 *
 * <p>从剧照 drawable 取一张小尺寸位图，用 {@code androidx.palette} 取 vibrant / muted swatch
 * 作为漫反射主色。参照目标应用的实现：饱和度过低（灰片）不出彩色光，直接回透明，
 * 让 {@code HomeHeroBackdropView} 的漫反射层淡出到不画。</p>
 *
 * <p><b>缓存</b>：按"图 URL"缓存结果，同一张剧照在多轮轮播里不重复提色；缓存上限与
 * {@code TmdbStore} 的位图 LRU 同量级（16 条）。<b>线程</b>：提色在单线程池里做，
 * 结果切回主线程交给 View；调用方在派发前自己判 generation，晚到结果不会盖住新片。</p>
 */
public final class HeroGlowColor {

    private static final int SATURATION_FLOOR = 24;
    private static final LruCache<String, Integer> CACHE = new LruCache<>(16);
    // 双线程：主视觉轮播会连续提色，单线程会把卡片提色排队到好几秒之后，
    // 导致焦点落到卡片后整页背景光迟迟不亮。留一条通道给卡片提色。
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
     */
    public static void load(@Nullable String url, @Nullable Drawable drawable, Callback callback) {
        if (callback == null) return;
        Integer cached = url == null ? null : CACHE.get(url);
        if (cached != null) {
            callback.onColor(cached);
            return;
        }
        Bitmap bitmap = toBitmap(drawable);
        if (bitmap == null) {
            callback.onColor(Color.TRANSPARENT);
            return;
        }
        EXECUTOR.execute(() -> {
            int color = extract(bitmap);
            if (url != null && color != Color.TRANSPARENT) CACHE.put(url, color);
            MAIN.post(() -> callback.onColor(color));
        });
    }

    @Nullable
    private static Bitmap toBitmap(@Nullable Drawable drawable) {
        if (!(drawable instanceof BitmapDrawable)) return null;
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        return bitmap == null || bitmap.isRecycled() ? null : bitmap;
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

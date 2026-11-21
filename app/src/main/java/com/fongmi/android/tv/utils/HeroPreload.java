package com.fongmi.android.tv.utils;

import android.content.Context;
import android.text.TextUtils;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.DoubanHotItem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页主视觉资源预热：榜单数据一到就把 12 条横版剧照与片名 logo 的原图拉进 Glide 磁盘缓存。
 *
 * <p><b>为什么需要</b>：主视觉每 7 秒切一张，原实现只在 {@code show(index)} 时才按当前条目
 * 发起请求，第 12 张要等 77 秒才第一次联网。用户翻到那一张时看到的是空背景 + 文字片名，
 * 网络稍差就是明显闪烁。预热把「等切到再下载」变成「启动就下载」。</p>
 *
 * <p><b>为什么用 {@code asFile().preload()}</b>：它只把源文件写入磁盘缓存，不产生 Bitmap。
 * 后续 ImageView 请求（剧照按屏幕宽度解码、logo 按原图解码）命中同一份 {@code DataCacheKey}，
 * 省掉网络往返但不多占一份内存。用 {@code preload()} 直接解 Bitmap 会为 12 张 w1280 图
 * 额外扛几十 MB 堆内存，得不偿失。</p>
 *
 * <p><b>去重</b>：同一 URL 在一个进程内只预热一次。{@code applyHero} 可能因数据刷新、
 * 页面重建反复回调，没有这层去重会把同一批图片重复入队。清缓存后调用 {@link #reset()}
 * 重新允许预热。</p>
 */
public final class HeroPreload {

    /** 记住多少个已预热的 URL：12 条 × 2 张图 = 24，留一倍余量。 */
    private static final int MAX_REMEMBER = 64;

    private static final Map<String, Boolean> STARTED = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(MAX_REMEMBER, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_REMEMBER;
                }
            });

    private HeroPreload() {
    }

    /**
     * 预热一批主视觉的资源。可在主线程调用：{@code preload()} 只是把请求提交给 Glide 的
     * 后台线程池，本身不做 IO。
     */
    public static void start(Context context, List<DoubanHotItem> items) {
        if (context == null || items == null || items.isEmpty()) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        // 先剧照后 logo：剧照决定首帧观感，logo 体积小、晚一点无所谓。
        for (DoubanHotItem item : items) preload(app, item, true);
        for (DoubanHotItem item : items) preload(app, item, false);
    }

    private static void preload(Context context, DoubanHotItem item, boolean backdrop) {
        if (item == null) return;
        String url;
        if (backdrop) url = item.hasBackdrop() ? RankLatest.imageUrl(item.getBackdrop()) : "";
        else url = item.hasLogo() ? RankLatest.logoUrl(item.getLogo()) : "";
        if (TextUtils.isEmpty(url) || !mark(url)) return;
        try {
            Glide.with(context).asFile().load(url).preload();
        } catch (Exception ignored) {
            // 预热失败不影响正常展示：真正播放到该条时还会按原有链路重新请求并处理失败。
        }
    }

    /** 同一 URL 只提交一次；返回 true 表示本次需要发起预热。 */
    private static boolean mark(String url) {
        synchronized (STARTED) {
            if (STARTED.containsKey(url)) return false;
            STARTED.put(url, Boolean.TRUE);
            return true;
        }
    }

    /** 清除缓存后调用：磁盘文件已删，允许下一轮重新预热。 */
    public static void reset() {
        STARTED.clear();
    }
}

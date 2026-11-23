package com.fongmi.android.tv.utils;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbApi;
import com.fongmi.android.tv.api.TmdbLog;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.bean.TmdbArt;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.TmdbSetting;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 海报 / 评分 / 简介的<b>唯一</b>抓取门面：TMDB 单源抓取 + 失败分级处置。
 *
 * <pre>
 *   ① 统一入口：{@link #fetch} 处理单条（详情页、最近观看、主视觉），
 *      {@link #enqueue} 处理网格批量（首页），两者走同一套判定与落库
 *   ② 单一全局队列：所有页面共用一个按进入顺序处理的队列，不区分页面优先级
 *   ③ 匀速排队：相邻两次抓取至少间隔 {@link #INTERVAL_MS}，避免一次进页面打满接口
 *   ④ TMDB 是唯一数据源：已完整缓存直接跳过；网络故障、限流等临时问题只做冷却，
 *      不据此拉黑；TMDB 正常返回但确实无匹配才写入 {@link ArtBlacklist}
 *   ⑤ 统一出口：{@link #card(String)} 与 {@link #apply(Vod)} 走同一份索引、同一套评分格式，
 *      首页站源卡片与最近观看因此表现完全一致
 * </pre>
 */
public final class ArtSource {

    /** 不限定类目：TMDB 抓取不区分电影与剧集。 */
    public static final int CAT_AUTO = 0;
    /** 只查电影榜单。 */
    public static final int CAT_MOVIE = 2;
    /** 只查剧集榜单。 */
    public static final int CAT_TV = 3;
    /** 卡片评分备注前缀：评分写在备注字符串里（「评分：x.x」），不单独占字段。 */
    public static final String SCORE_PREFIX = "评分：";
    /**
     * 备注里任意位置的评分段（含半角冒号、省略冒号、尾部「分」字）。
     *
     * <p>不锚定行首：站源备注五花八门，评分可能出现在开头（「评分：8.7」）、
     * 结尾（「更新至12集 评分9.1」）或中间（「豆瓣评分：8.0 更新至12集」）。
     * 只剥行首会让后两种与本站评分叠成两个评分 —— 这正是「同一张卡出现 2 个评分」的成因。</p>
     */
    private static final Pattern SCORE_ANY = Pattern.compile("评分\\s*[:：]?\\s*\\d{1,2}(\\.\\d)?\\s*分?");
    /** 剥掉评分后残留在首尾的分隔符（空格 / 中点 / 竖线 / 逗号 / 顿号）。 */
    private static final Pattern EDGE_JUNK = Pattern.compile("^[\\s·•|｜,，、]+|[\\s·•|｜,，、]+$");

    /** 相邻两次抓取提交的最小间隔（毫秒）。 */
    private static final long INTERVAL_MS = 500L;
    /** 临时失败后的重试间隔：TMDB 侧已无冷静期，这里给短时冷却留足余量。 */
    private static final long RETRY_GAP_MS = 65_000L;
    /** 每个片名在整个进程内最多提交几次（含首次）。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 全局队列最多排队的片名数，防止异常数据把队列撑爆。 */
    private static final int MAX_QUEUE = 120;

    private static final Object LOCK = new Object();
    /** 全局待抓队列：归一化片名 → 原始片名，按进入顺序处理，天然去重。 */
    private static final Map<String, String> QUEUE = new LinkedHashMap<>();
    /** 失败 / 冷却后待重试：归一化片名 → 原始片名。 */
    private static final Map<String, String> RETRY = new LinkedHashMap<>();

    private static final Map<String, Integer> ATTEMPTS = new HashMap<>();
    /** 归一化片名 → 类目标记，保留给调用方做批量归类。 */
    private static final Map<String, Integer> CATEGORIES = new HashMap<>();
    private static final List<WeakReference<Listener>> LISTENERS = new ArrayList<>();
    private static boolean scheduled;
    private static boolean retryScheduled;
    private static boolean registered;

    private static final Runnable ON_FETCHED = ArtSource::broadcast;

    private ArtSource() {
    }

    public interface Listener {

        /** 有新的海报 / 评分 / 简介落库，页面应就地刷新卡片。回调已在主线程。 */
        void onChanged();
    }

    public interface Callback {

        /** 抓取完成；art 为 TMDB 写入索引后的最终记录。 */
        void onSuccess(TmdbArt art);

        /** 两个数据源都没有取到可用素材。 */
        void onFailure(String reason);
    }

    /**
     * 从旧格式备注里识别评分；返回值统一为 10 分制一位小数。
     * 评分现在直接写在备注字符串里，卡片不再解析角标，这里只留给旧缓存与数据迁移用。
     */
    public static String score(String remarks) {
        if (TextUtils.isEmpty(remarks)) return "";
        String text = remarks.trim();
        if (!text.startsWith(SCORE_PREFIX) && !text.startsWith("评分:")) return "";
        String value = text.substring(text.indexOf('：') >= 0 ? text.indexOf('：') + 1 : text.indexOf(':') + 1).trim();
        if (!value.matches("[0-9]{1,2}(\\.[0-9])?")) return "";
        return ArtRating.normalize(value);
    }

    /**
     * 把评分拼进备注字符串 —— 评分角标出现前的历史做法：评分就写在备注里，
     * 卡片只渲染这一行文本，没有任何独立角标。
     *
     * <p>热搜 / 榜单卡片的备注槽本来是空的，旧实现直接写「评分：x.x」；
     * 站源卡片自带备注（集数、更新状态），直接覆盖会丢信息，所以评分放最前面、
     * 原备注留在后面，整行仍由 TextView 按卡片宽度统一截断。</p>
     *
     * <p><b>幂等</b>：先把备注里<b>任意位置</b>已有的评分段全部剥掉（见 {@link #SCORE_ANY}），
     * 再写入本次评分。所以重复调用（缓存回填、多次 {@link #apply}）、以及站源备注里
     * 自带评分的情况，都不会出现两个评分。</p>
     */
    public static String scoreRemark(String remarks, String rating) {
        String value = ArtRating.normalize(rating);
        String text = TextUtils.isEmpty(remarks) ? "" : remarks;
        if (value.isEmpty()) return text;
        String rest = SCORE_ANY.matcher(text).replaceAll("").replaceAll("\\s{2,}", " ");
        rest = EDGE_JUNK.matcher(rest).replaceAll("").trim();
        String score = SCORE_PREFIX + value;
        return rest.isEmpty() ? score : score + " " + rest;
    }

    /** 由片名构造一张热榜卡片：素材与评分一起写入（纯片名卡片没有站源备注）。 */
    public static Vod card(String title) {
        Vod vod = new Vod();
        vod.setName(title);
        applyWithScore(vod);
        return vod;
    }

    /**
     * 只回填海报，<b>不动备注</b>。
     *
     * <p>专供站源卡片（首页「来自 · 站源名」及其「更多影片」页）：这些卡片的备注是站源原文
     * （集数、更新状态，且常自带它自己的评分，如「豆瓣8.9」——不带「评分」二字，
     * 无法被 {@link #SCORE_ANY} 识别），若再拼一条 TMDB 评分，一行就会同时出现两个评分。
     * 按「保留备注、不显示其它评分」的口径，这里只覆盖海报。</p>
     */
    public static boolean apply(Vod vod) {
        if (vod == null || TextUtils.isEmpty(vod.getName())) return false;
        TmdbArt art = TmdbStore.find(vod.getName());
        return art != null && applyArt(vod, art);
    }

    /**
     * 海报 + 评分一起写入，供纯片名卡片（热榜 / 榜单）使用。
     * 站源卡片不要用它 —— 会把评分拼到站源备注前面，见 {@link #apply(Vod)}。
     */
    public static boolean applyWithScore(Vod vod) {
        if (vod == null || TextUtils.isEmpty(vod.getName())) return false;
        TmdbArt art = TmdbStore.find(vod.getName());
        if (art == null) return false;
        boolean changed = applyArt(vod, art);
        String rating = ArtRating.normalize(art.getRating());
        if (!rating.isEmpty()) {
            String old = vod.getRemarks();
            String next = scoreRemark(old, rating);
            if (!next.equals(old)) {
                vod.setRemarks(next);
                changed = true;
            }
        }
        return changed;
    }

    /** 把索引里的海报写到卡片的展示字段，返回是否有变化。 */
    private static boolean applyArt(Vod vod, TmdbArt art) {
        File file = art.getCardFile();
        if (file == null) return false;
        String oldPic = vod.getArtPic();
        // 本地文件走内嵌服务器的 /file/ 路由（GlideUrl 只认 http(s)，不能塞绝对路径）。
        // 拼法与 SpiderTest.sourceUrl 一致：去掉开头的 '/'，避免 /file//data 双斜杠。
        // 只写独立的展示字段，站源 Vod 的原始 vodPic 保持不变，首页结果缓存才不会被本地地址污染。
        String path = file.getAbsolutePath().replaceFirst("^/", "");
        vod.setArtPic(Server.get().getAddress("/file/") + Uri.encode(path, "/"));
        return !vod.getArtPic().equals(oldPic);
    }

    /** 是否有「可用卡片图 + 有效评分」的完整缓存。 */
    private static boolean complete(TmdbArt art) {
        return art != null && art.getCardFile() != null && !ArtRating.normalize(art.getRating()).isEmpty();
    }

    /** 这个片名现在是否还值得提交一次抓取（排除黑名单 / 冷却 / 已完整 / 重试上限）。 */
    private static boolean eligible(String title) {
        if (TextUtils.isEmpty(title)) return false;
        if (ArtBlacklist.contains(title)) return false;
        if (ArtCooldown.blocked(title)) return false;
        if (complete(TmdbStore.find(title))) return false;
        String key = TmdbStore.normalize(title);
        return !key.isEmpty() && ATTEMPTS.getOrDefault(key, 0) < MAX_ATTEMPTS;
    }

    public static void register(Listener listener) {
        if (listener == null) return;
        synchronized (LISTENERS) {
            LISTENERS.removeIf(ref -> ref.get() == null || ref.get() == listener);
            LISTENERS.add(new WeakReference<>(listener));
            if (!registered) {
                TmdbApi.addOnFetched(ON_FETCHED);
                registered = true;
            }
        }
    }

    public static void unregister(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.removeIf(ref -> ref.get() == null || ref.get() == listener);
        }
    }

    // ---------------------------------------------------------------- 统一入口

    /**
     * 单条抓取：详情页、最近观看、主视觉都调用这里。
     *
     * @param keyword  主关键字
     * @param fallback 备用片名（可空）
     * @param aliases  额外别名（可空）
     * @param category {@link #CAT_AUTO} / {@link #CAT_MOVIE} / {@link #CAT_TV}
     * @param force    true 表示用户手动重试：跳过缓存与冷却，两个数据源都重新试一次
     */
    public static void fetch(String keyword, String fallback, List<String> aliases, int category, boolean force, Callback callback) {
        String title = !TextUtils.isEmpty(keyword) ? keyword.trim() : (fallback == null ? "" : fallback.trim());
        if (TextUtils.isEmpty(title)) {
            fail(callback, "没有可用的片名");
            return;
        }
        // 黑名单优先级最高：已经确认 TMDB 没有这部片，手动重试也不放行，
        // 否则用户会看到按钮毫无反应却一直在后台打接口。清除缓存可清空。
        // 这里不写日志：拉黑那一刻已经记过一条，重复进详情页会把其它现场刷掉。
        if (ArtBlacklist.contains(title)) {
            fail(callback, "该影片已在黑名单中，不再刮削");
            return;
        }
        if (!force) {
            if (ArtCooldown.blocked(title)) {
                fail(callback, "该影片 10 分钟内不再重试");
                return;
            }
            TmdbArt cached = TmdbStore.find(title);
            if (complete(cached)) {
                success(callback, cached);
                return;
            }
        }
        // TMDB 是唯一数据源：缓存命中但素材不全时直接判为临时失败，不再降级。
        if (!force && TmdbStore.cached(title)) {
            ArtCooldown.mark(title);
            fail(callback, "TMDB 素材不全，10 分钟内不再重试");
            return;
        }
        if (!force && TmdbStore.missed(title)) {
            fail(callback, "TMDB 暂无匹配，稍后再试");
            return;
        }
        // 结果驱动：TmdbApi 只在返回终态时回调，避免排队超时被误判。
        TmdbApi.FetchCallback tmdb = new TmdbApi.FetchCallback() {
            @Override
            public void onSuccess(String name, TmdbArt art) {
                TmdbArt fresh = art != null ? art : TmdbStore.find(title);
                if (complete(fresh)) {
                    ArtCooldown.clear(title);
                    broadcast();
                    success(callback, fresh);
                } else {
                    ArtCooldown.mark(title);
                    fail(callback, "TMDB 素材不全，10 分钟内不再重试");
                }
            }

            @Override
            public void onFailure(String name, String reason) {
                onFailure(name, reason, ArtFailure.TEMPORARY);
            }

            @Override
            public void onFailure(String name, String reason, int type) {
                if (type == ArtFailure.NO_MATCH) {
                    if (ArtBlacklist.add(name)) {
                        TmdbLog.add(TmdbLog.SOURCE_TMDB, TmdbLog.STAGE_BLACKLIST, name, "", "",
                                "TMDB 确认无匹配，已加入黑名单，不再刮削", false);
                        TmdbApi.notifyFetched();
                        broadcast();
                    }
                    fail(callback, "TMDB 无匹配，已加入黑名单");
                    return;
                }
                TmdbLog.add(TmdbLog.SOURCE_TMDB, TmdbLog.STAGE_FAIL, title, "", reason,
                        "TMDB 刮削失败，10 分钟内不再重试", true);
                ArtCooldown.mark(title);
                fail(callback, "TMDB 刮削失败：" + reason);
            }
        };
        if (force) TmdbApi.retry(keyword, fallback, aliases, tmdb);
        else TmdbApi.fetch(keyword, fallback, aliases, tmdb);
    }

    /** 单条抓取的简化写法。 */
    public static void fetch(String title, int category, Callback callback) {
        fetch(title, null, null, category, false, callback);
    }

    /**
     * 已有素材但缺评分 / 年份 / 题材时补一次 TMDB 元数据；不下载图片、不写 miss。
     * 统一从这里进入，避免调用方各自判断开关后再去碰 {@link TmdbApi}。
     */
    public static void ensureMeta(String title) {
        if (!TmdbSetting.isEnabled() || TextUtils.isEmpty(title)) return;
        if (ArtBlacklist.contains(title)) return;
        TmdbApi.ensureMeta(title);
    }

    /** 首页等混合内容页面：不指定类目。 */
    public static void enqueue(Collection<String> titles) {
        enqueue(titles, CAT_AUTO);
    }

    /**
     * 把一批片名放进全局队列，按进入顺序处理；同一片名归一化后只排一次。
     * 不再区分页面优先级，各页面先入队的先刮。
     */
    public static void enqueue(Collection<String> titles, int category) {
        if (titles == null || titles.isEmpty()) return;
        if (!TmdbSetting.isEnabled()) return;
        synchronized (LOCK) {
            for (String title : titles) {
                if (TextUtils.isEmpty(title)) continue;
                String key = TmdbStore.normalize(title);
                if (key.isEmpty()) continue;
                if (ArtBlacklist.contains(title)) continue;
                if (ArtCooldown.blocked(title)) continue;
                TmdbArt art = TmdbStore.find(title);
                if (art != null) {
                    if (!art.hasMeta()) ensureMeta(title);
                    if (complete(art)) continue;
                }
                if (ATTEMPTS.getOrDefault(key, 0) >= MAX_ATTEMPTS) continue;
                if (QUEUE.size() >= MAX_QUEUE) break;
                CATEGORIES.put(key, category);
                QUEUE.putIfAbsent(key, title);
            }
            schedule(INTERVAL_MS);
        }
    }

    /** 丢弃全部待抓队列（退出应用 / 清缓存时用）。 */
    public static void cancel() {
        synchronized (LOCK) {
            QUEUE.clear();
            RETRY.clear();
            scheduled = false;
            retryScheduled = false;
            App.removeCallbacks(STEP);
            App.removeCallbacks(RETRY_STEP);
        }
    }

    /** 备份恢复 / 清除缓存 / 配置变更后重置全部进程内状态。 */
    public static void reset() {
        cancel();
        synchronized (LOCK) {
            ATTEMPTS.clear();
            CATEGORIES.clear();
        }
        ArtCooldown.reset();
        // 黑名单与冷却都在 tmdb/ 目录里：备份恢复后要重读新文件，清缓存后要回到空名单。
        ArtBlacklist.reset();
    }

    // ---------------------------------------------------------------- 队列调度

    /**
     * 从全局队列头部依次取一个「现在真的能抓」的任务。
     * 永久不可抓的（黑名单 / 已完整 / 达重试上限）顺手丢掉；只在冷却期的保留，
     * 等冷却结束后还能再试 —— 它不阻塞后面的片名。
     */
    private static String take() {
        Iterator<Map.Entry<String, String>> iterator = QUEUE.entrySet().iterator();
        while (iterator.hasNext()) {
            String title = iterator.next().getValue();
            if (!eligible(title)) {
                if (ArtBlacklist.contains(title) || !ArtCooldown.blocked(title)) iterator.remove();
                continue;
            }
            iterator.remove();
            return title;
        }
        return null;
    }

    /** 是否还有现在就能抓的任务。 */
    private static boolean hasWork() {
        for (String title : QUEUE.values()) if (eligible(title)) return true;
        return false;
    }

    /** 是否还有「只是因为冷却暂时抓不了」的任务：有的话过一会儿要回来再看一眼。 */
    private static boolean hasCooling() {
        for (String title : QUEUE.values()) {
            if (!ArtBlacklist.contains(title) && ArtCooldown.blocked(title)) return true;
        }
        return false;
    }

    private static void schedule(long delay) {
        if (scheduled) return;
        if (!hasWork()) return;
        scheduled = true;
        App.post(STEP, delay);
    }

    private static final Runnable STEP = () -> {
        String title;
        synchronized (LOCK) {
            scheduled = false;
            title = take();
            if (title == null) {
                scheduleRetry();
                return;
            }
        }
        process(title);
        synchronized (LOCK) {
            if (hasWork()) schedule(INTERVAL_MS);
            else scheduleRetry();
        }
    };

    private static void process(String title) {
        if (!eligible(title)) return;
        String key = TmdbStore.normalize(title);
        int attempt;
        int category;
        synchronized (LOCK) {
            attempt = ATTEMPTS.getOrDefault(key, 0);
            if (attempt >= MAX_ATTEMPTS) return;
            ATTEMPTS.put(key, attempt + 1);
            category = CATEGORIES.getOrDefault(key, CAT_AUTO);
        }
        fetch(title, null, null, category, false, new Callback() {
            @Override
            public void onSuccess(TmdbArt result) {
            }

            @Override
            public void onFailure(String reason) {
                synchronized (LOCK) {
                    RETRY.put(key, title);
                }
            }
        });
        synchronized (LOCK) {
            // 异步结果还没回来时先登记，下一轮重试会再次确认是否已完整。
            RETRY.put(key, title);
        }
    }

    private static void success(Callback callback, TmdbArt art) {
        if (callback != null) App.post(() -> callback.onSuccess(art));
    }

    private static void fail(Callback callback, String reason) {
        if (callback != null) App.post(() -> callback.onFailure(reason));
    }

    // ---------------------------------------------------------------- 重试与广播

    /**
     * 安排下一次重试：既包括抓取失败的片名，也包括「还在冷却期」的排队项
     * —— 冷却结束后要自动回来再试一次。
     */
    private static void scheduleRetry() {
        if (retryScheduled) return;
        if (RETRY.isEmpty() && !hasCooling()) return;
        retryScheduled = true;
        App.post(RETRY_STEP, RETRY_GAP_MS);
    }

    private static final Runnable RETRY_STEP = () -> {
        synchronized (LOCK) {
            retryScheduled = false;
            for (Map.Entry<String, String> entry : RETRY.entrySet()) {
                String title = entry.getValue();
                if (!eligible(title)) continue;
                if (QUEUE.size() >= MAX_QUEUE) continue;
                QUEUE.putIfAbsent(entry.getKey(), title);
            }
            RETRY.clear();
            if (hasWork()) schedule(INTERVAL_MS);
            else scheduleRetry();
        }
    };

    /** 数据源落库后广播一次；调用方都已在主线程。 */
    private static void broadcast() {
        synchronized (LISTENERS) {
            LISTENERS.removeIf(ref -> ref.get() == null);
            for (WeakReference<Listener> ref : LISTENERS) {
                Listener listener = ref.get();
                if (listener != null) listener.onChanged();
            }
        }
    }
}

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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 海报 / 评分 / 简介的<b>唯一</b>抓取门面：TMDB 优先 → Douban Bridge兜底 → 失败分级处置。
 *
 * <pre>
 *   ① 统一入口：{@link #fetch} 处理单条（详情页、最近观看、主视觉），
 *      {@link #enqueue} 处理网格批量（首页与三个频道页），两者走同一套判定与落库
 *   ② 单一全局队列：所有页面共用一个按进入顺序处理的队列，不区分页面优先级
 *   ③ 匀速排队：相邻两次抓取至少间隔 {@link #INTERVAL_MS}，避免一次进页面打满接口
 *   ④ 优先 TMDB：已完整缓存直接跳过；只有「无匹配」「素材不全」「TMDB 未启用」才转Douban Bridge，
 *      网络故障等临时问题只做冷却、不降级，也不据此拉黑；TMDB 完整成功即排他，Douban 不再介入
 *   ⑤ Douban Bridge：按类目榜单做「片名完全匹配优先，否则模糊匹配」，海报 / 评分 / 简介写回同一索引
 *   ⑥ 失败分级：只有「两个数据源都正常返回、但确实没有这部片」才进 {@link ArtBlacklist}，
 *      网络错误 / 限流 / 素材缺失等临时问题只做 {@link ArtCooldown} 的短时冷却
 *   ⑦ 统一出口：{@link #card(String)} 与 {@link #apply(Vod)} 走同一份索引、同一套评分格式，
 *      首页站源卡片与三个频道页的热榜卡片因此表现完全一致
 * </pre>
 */
public final class ArtSource {

    /** 首页是电影 / 剧集 / 综艺混合，Douban Bridge需要同时查多类榜单。 */
    public static final int CAT_AUTO = 0;
    /** 卡片评分备注前缀：兼容旧缓存，新数据统一走 Vod.artScore。 */
    public static final String SCORE_PREFIX = "评分：";

    /** 相邻两次抓取提交的最小间隔（毫秒）。 */
    private static final long INTERVAL_MS = 500L;
    /** 临时失败后的重试间隔：TMDB 侧已无冷静期，这里给 Douban Bridge 冷却留足 10 分钟余量。 */
    private static final long RETRY_GAP_MS = 65_000L;
    /** 每个片名在整个进程内最多提交几次（含首次）。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 全局队列最多排队的片名数，防止异常数据把队列撑爆。 */
    private static final int MAX_QUEUE = 120;

    /** TMDB 未参与本次判定（二级开关关闭）：Douban Bridge的结论可以直接作为最终结论。 */
    private static final int TMDB_SKIPPED = 0;
    /** TMDB 匹配到了条目，只是素材不全（缺海报 / 缺评分）：说明这部片存在，不能拉黑。 */
    private static final int TMDB_MATCHED = 1;
    /** TMDB 明确返回「没有匹配结果」。 */
    private static final int TMDB_NO_MATCH = 2;
    /** TMDB 侧网络 / 限流 / 解析异常，结论不可信。 */
    private static final int TMDB_TEMPORARY = 3;

    private static final Object LOCK = new Object();
    /** 全局待抓队列：归一化片名 → 原始片名，按进入顺序处理，天然去重。 */
    private static final Map<String, String> QUEUE = new LinkedHashMap<>();
    /** 失败 / 冷却后待重试：归一化片名 → 原始片名。 */
    private static final Map<String, String> RETRY = new LinkedHashMap<>();

    private static final Map<String, Integer> ATTEMPTS = new HashMap<>();
    /** 归一化片名 → 热榜类目，供Douban Bridge选择对应榜单。 */
    private static final Map<String, Integer> CATEGORIES = new HashMap<>();
    /** 进程内已发起过Douban Bridge降级的片名，避免重复写触发日志。 */
    private static final Set<String> FALLBACK = new HashSet<>();
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

        /** 抓取完成；art 为索引里的最终记录，可能来自 TMDB，也可能来自Douban Bridge。 */
        void onSuccess(TmdbArt art);

        /** 两个数据源都没有取到可用素材。 */
        void onFailure(String reason);
    }

    /**
     * 从旧格式备注里识别评分；返回值统一为 10 分制一位小数。
     * 新数据不再写备注，这里只为兼容旧缓存。
     */
    public static String score(String remarks) {
        if (TextUtils.isEmpty(remarks)) return "";
        String text = remarks.trim();
        if (!text.startsWith(SCORE_PREFIX) && !text.startsWith("评分:")) return "";
        String value = text.substring(text.indexOf('：') >= 0 ? text.indexOf('：') + 1 : text.indexOf(':') + 1).trim();
        if (!value.matches("[0-9]{1,2}(\\.[0-9])?")) return "";
        return ArtRating.normalize(value);
    }

    /** 由片名构造一张热榜卡片；与首页站源卡片共用 {@link #apply(Vod)}。 */
    public static Vod card(String title) {
        Vod vod = new Vod();
        vod.setName(title);
        apply(vod);
        return vod;
    }

    /**
     * 把索引里的海报与评分就地应用到一张卡片，返回是否有变化。
     * 首页的站源 Vod 会保留片名、播放地址与站源备注，只覆盖图片并写入独立评分字段；
     * 频道页的纯片名卡片则用它拿到全部素材。两者走的是同一份索引与同一套格式。
     */
    public static boolean apply(Vod vod) {
        if (vod == null || TextUtils.isEmpty(vod.getName())) return false;
        TmdbArt art = TmdbStore.find(vod.getName());
        if (art == null) return false;
        String oldPic = vod.getArtPic();
        String oldScore = vod.getArtScore();
        File file = art.getCardFile();
        // 本地文件走内嵌服务器的 /file/ 路由（GlideUrl 只认 http(s)，不能塞绝对路径）。
        // 拼法与 SpiderTest.sourceUrl 一致：去掉开头的 '/'，避免 /file//data 双斜杠。
        // 只写独立的展示字段，站源 Vod 的原始 vodPic 保持不变，首页结果缓存才不会被本地地址污染。
        if (file != null) {
            String path = file.getAbsolutePath().replaceFirst("^/", "");
            vod.setArtPic(Server.get().getAddress("/file/") + Uri.encode(path, "/"));
        }
        String rating = ArtRating.normalize(art.getRating());
        if (!rating.isEmpty()) vod.setArtScore(rating);
        return !vod.getArtPic().equals(oldPic) || !vod.getArtScore().equals(oldScore);
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
     * @param category {@link #CAT_AUTO} 或 {@link HotRank#MOVIE} / {@link HotRank#TV} / {@link HotRank#VARIETY}
     * @param force    true 表示用户手动重试：跳过缓存与冷却，两个数据源都重新试一次
     */
    public static void fetch(String keyword, String fallback, List<String> aliases, int category, boolean force, Callback callback) {
        String title = !TextUtils.isEmpty(keyword) ? keyword.trim() : (fallback == null ? "" : fallback.trim());
        if (TextUtils.isEmpty(title)) {
            fail(callback, "没有可用的片名");
            return;
        }
        if (!TmdbSetting.isMasterEnabled()) {
            fail(callback, "刮削总开关已关闭");
            return;
        }
        // 黑名单优先级最高：已经确认「两个数据源都没有这部片」，手动重试也不放行，
        // 否则用户会看到按钮毫无反应却一直在后台打接口。移除只能走「设置 → 清除缓存」。
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
        // TMDB 二级开关关闭：跳过主数据源，直接交给Douban Bridge；此时Douban Bridge的结论就是最终结论。
        if (!TmdbSetting.isTmdbScrapeEnabled()) {
            bridge(title, category, force, TMDB_SKIPPED, callback);
            return;
        }
        // 已有卡片图（海报或剧照）但缺评分 / miss 冷却中：TmdbApi 会静默早退，直接交给Douban Bridge补齐。
        // 已有卡片图说明这部片 TMDB 认识（素材不全）；miss 冷却则来源不明，按临时状态处理。
        if (!force) {
            boolean cached = TmdbStore.cached(title);
            if (cached || TmdbStore.missed(title)) {
                bridge(title, category, false, cached ? TMDB_MATCHED : TMDB_TEMPORARY, callback);
                return;
            }
        }
        // 结果驱动：不再挂固定定时器。TmdbApi 的任务即使在多站点队列里排队，也只在真正
        // 返回终态时才回调，杜绝「排队超时被误判为不可用、Douban 抢跑」的竞态。
        TmdbApi.FetchCallback tmdb = new TmdbApi.FetchCallback() {
            @Override
            public void onSuccess(String name, TmdbArt art) {
                TmdbArt fresh = art != null ? art : TmdbStore.find(title);
                if (complete(fresh)) {
                    // TMDB 已完成全部刮削：直接收尾，Douban Bridge 不启动（互斥）。
                    ArtCooldown.clear(title);
                    broadcast();
                    success(callback, fresh);
                } else {
                    // 请求成功但没拿到完整素材（无海报或无评分）：片子是存在的，只是素材不全。
                    bridge(title, category, force, TMDB_MATCHED, callback);
                }
            }

            @Override
            public void onFailure(String name, String reason) {
                onFailure(name, reason, ArtFailure.TEMPORARY);
            }

            @Override
            public void onFailure(String name, String reason, int type) {
                if (type == ArtFailure.NO_MATCH) {
                    // TMDB 正常返回但确实没有匹配：这是「结果为空」，降级 Douban Bridge。
                    bridge(title, category, force, TMDB_NO_MATCH, callback);
                } else if (type == ArtFailure.INCOMPLETE) {
                    // 条目存在但关键素材缺失：片子是有的，降级补素材，但不参与拉黑判定。
                    bridge(title, category, force, TMDB_MATCHED, callback);
                } else {
                    // 网络故障 / 限流 / 解析异常：不降级，只做短时冷却，避免用不可信结论拉黑。
                    TmdbLog.add(TmdbLog.SOURCE_TMDB, TmdbLog.STAGE_FAIL, title, "", reason,
                            "TMDB 网络或临时故障，不降级 Douban Bridge，10 分钟内不再重试", true);
                    ArtCooldown.mark(title);
                    fail(callback, "TMDB 网络或临时故障：" + reason);
                }
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

    /** 首页等混合内容页面：不指定类目，Douban Bridge同时查电影、剧集与综艺榜。 */
    public static void enqueue(Collection<String> titles) {
        enqueue(titles, CAT_AUTO);
    }

    /**
     * 把一批片名放进全局队列，按进入顺序处理；同一片名归一化后只排一次。
     * 不再区分页面优先级，各页面先入队的先刮。
     */
    public static void enqueue(Collection<String> titles, int category) {
        if (titles == null || titles.isEmpty()) return;
        if (!TmdbSetting.isMasterEnabled()) return;
        if (!TmdbSetting.isTmdbScrapeEnabled() && !TmdbSetting.isBridgeEnabled()) return;
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
        DoubanBridge.cancel();
    }

    /** 备份恢复 / 清除缓存 / 更换Douban Bridge配置后重置全部进程内状态。 */
    public static void reset() {
        cancel();
        synchronized (LOCK) {
            ATTEMPTS.clear();
            CATEGORIES.clear();
            FALLBACK.clear();
        }
        DoubanBridge.reset();
        ArtCooldown.reset();
        // 黑名单与冷却都在 tmdb/ 目录里：备份恢复后要重读新文件，清缓存后要回到空名单。
        ArtBlacklist.reset();
    }

    /**
     * 黑名单页单条移除后调用：清掉该片在进程内的所有拦截状态（尝试次数、冷却、
     * 队列、待回调），丢掉索引里的旧记录，并重新排队抓一次，避免「移出黑名单却永远不会再刮」。
     */
    public static void unban(String title) {
        if (TextUtils.isEmpty(title)) return;
        String key = TmdbStore.normalize(title);
        synchronized (LOCK) {
            ATTEMPTS.remove(key);
            CATEGORIES.remove(key);
            FALLBACK.remove(key);
            RETRY.remove(key);
            QUEUE.remove(key);
        }
        TmdbStore.forget(title);
        ArtCooldown.clear(title);
        TmdbApi.notifyFetched();
        broadcast();
        enqueue(Collections.singletonList(title), CAT_AUTO);
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

    // ---------------------------------------------------------------- Douban Bridge降级

    /**
     * TMDB 不可用 → Douban Bridge。
     *
     * @param tmdb TMDB 在本次判定中的状态，决定Douban Bridge说「无匹配」时能不能直接拉黑
     */
    private static void bridge(String title, int category, boolean force, int tmdb, Callback callback) {
        // Douban Bridge二级开关关闭（或未配置）：不算一次失败，也不写冷却，等用户重新开启。
        if (!TmdbSetting.isBridgeEnabled()) {
            String reason = TmdbSetting.isBridgeConfigured()
                    ? "Douban Bridge刮削已关闭，本次不降级"
                    : "Douban Bridge未配置密钥，本次不降级";
            TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_SKIP, title, "", "", reason, false);
            fail(callback, reason);
            return;
        }
        String key = TmdbStore.normalize(title);
        if (!force && ArtCooldown.blocked(title)) {
            fail(callback, "该影片 10 分钟内不再重试");
            return;
        }
        boolean first;
        synchronized (LOCK) {
            first = FALLBACK.add(key);
        }
        if (first) {
            String reason = !TmdbSetting.isTmdbScrapeEnabled()
                    ? "TMDB 刮削已关闭，直接走Douban Bridge"
                    : "TMDB 不可用或数据不全，转Douban Bridge";
            TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_BRIDGE_TRIGGER, title, "", "", reason, false);
        }
        DoubanBridge.enqueue(title, category, (name, result) -> {
            synchronized (LOCK) {
                FALLBACK.remove(key);
            }
            finish(name, tmdb, result, callback);
        });
    }

    /**
     * Douban Bridge的最终结论。
     *
     * <p>成功即结束；<b>只有在「Douban Bridge确认无匹配」且「TMDB 也是无匹配或根本没参与」时</b>
     * 才写入黑名单；其余（网络错误、限流、素材缺失、桥被关闭）一律只做短时冷却，
     * 避免把一时抓不到的片子永久误杀。</p>
     */
    private static void finish(String name, int tmdb, int result, Callback callback) {
        if (result == ArtFailure.NONE) {
            TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_DONE, name, "", "",
                    "Douban Bridge降级成功", false);
            ArtCooldown.clear(name);
            // 让监听 TMDB 抓取完成的页面（详情页、主视觉）也能收到这次更新。
            TmdbApi.notifyFetched();
            broadcast();
            success(callback, TmdbStore.find(name));
            return;
        }
        if (result == ArtFailure.NO_MATCH && (tmdb == TMDB_NO_MATCH || tmdb == TMDB_SKIPPED)) {
            // 同一片名可能被多路回调同时命中，只有真正新增时才记日志与广播。
            if (ArtBlacklist.add(name)) {
                TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_BLACKLIST, name, "", "",
                        (tmdb == TMDB_NO_MATCH ? "TMDB 与Douban Bridge均无匹配" : "Douban Bridge确认无匹配")
                                + "，已加入黑名单，不再刮削", false);
                // 广播一次，页面据此把这张卡从主视觉与海报网格里移除。
                TmdbApi.notifyFetched();
                broadcast();
            }
            fail(callback, "TMDB 与Douban Bridge均无匹配，已加入黑名单");
            return;
        }
        if (result == ArtFailure.UNAVAILABLE) {
            TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_SKIP, name, "", "",
                    "Douban Bridge不可用，本次不降级", false);
            fail(callback, "Douban Bridge不可用，本次不降级");
            return;
        }
        TmdbLog.add(TmdbLog.SOURCE_BRIDGE, TmdbLog.STAGE_FAIL, name, "", "",
                "Douban Bridge降级失败（临时性问题），10 分钟内不再重试", true);
        ArtCooldown.mark(name);
        fail(callback, "TMDB 与Douban Bridge均未取到素材，10 分钟内不再重试");
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

package com.fongmi.android.tv.utils;

import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbApi;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.setting.TmdbSite;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 榜单数据：{@code https://rank-json.tmdbapi.cc.cd/rank/latest}。
 *
 * <p>一份响应里同时带 {@code categories.series} 与 {@code categories.movie} 两段，
 * 每段 40 条，字段已由服务端通过 TMDB 预置富化：片名、原名、简介、上映年份、
 * 横版剧照相对路径、评分、类型、题材、票数等。搜索页的「热播剧集 / 热播电影」
 * 与首页主视觉共用这一份数据，不再走四平台热榜，也不再为了补图去请求 TMDB 接口。</p>
 *
 * <p><b>图片地址</b>：服务端只给 {@code backdropPath} / {@code logoPath}，
 * 展示时拼成 {@code {base}/t/p/{size}/{path}}。{@code base} 由全局 TMDB 连接方式决定：
 * 默认反代与自定义反代理用 API 地址，官方模式固定用 {@code https://image.tmdb.org}；
 * 地址不支持图片时按地址持久化降级标记并回落官方图片域名。整条链路<b>不需要 API 密钥</b>。</p>
 *
 * <p><b>缓存</b>：内存 + {@code files/rank_latest.json} 双层，TTL 12 小时。命中直接回调、
 * 不联网；过期时旧数据<b>立即回调先展示</b>，后台再刷新并回调一次新数据，因此重启后不必等
 * 完整 HTTP 往返才出主视觉。刷新失败继续用旧数据，栏目不会因一次网络抖动而消失。
 * 磁盘文件的时间戳就是写入时间，不额外包一层缓存对象。</p>
 *
 * <p><b>解析</b>：逐字段手工读取，不用 {@code gson.fromJson(json, Data.class)}。
 * R8 在 release 构建会剥离泛型 Signature，{@code List<RankItem>} 会退化成
 * {@code List<LinkedTreeMap>}，绑定层强转即崩；手工读 {@code JsonObject} 与泛型无关。</p>
 */
public final class RankLatest {

    /** 榜单数据源：一份响应含剧集与电影两段。 */
    public static final String URL = "https://rank-json.tmdbapi.cc.cd/rank/latest";
    /** 未配置刮削站点时的官方图片地址。 */
    public static final String DEFAULT_IMAGE_BASE = "https://image.tmdb.org";
    /** 首页主视觉条数：2 剧集 + 2 电影 交替，共 12 条。 */
    public static final int HERO_LIMIT = 12;

    private static final long TTL = 12L * 60 * 60 * 1000;
    private static final String CACHE_NAME = "rank_latest.json";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "RankLatest");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Data memory;
    private static volatile long memoryTime;
    /** 配置站点不提供 /t/p/ 图片时置位：后续剧照与 logo 都直接走官方，避免重复 404。 */
    private static volatile boolean baseImageBlocked;
    /** 图片站点不可用按地址持久化，避免每次冷启动都对同一个站点白发一次 404。 */
    private static final String KEY_IMAGE_BLOCKED = "rank_image_blocked_";
    /** 设置里改过 TMDB 连接方式 / 站点后置位，首页回到前台时重建已挂主视觉。 */
    private static volatile boolean configChanged;
    /** 正在等待同一次刷新的调用者；并发 load 共用一次 HTTP，避免重复下载整份榜单。 */
    private static final List<Callback> PENDING = new ArrayList<>();
    private static boolean fetching;

    private RankLatest() {
    }

    public interface Callback {

        /** 结果一定切回主线程；旧缓存可能先回传一次，后台刷新成功后再回传一次新数据。 */
        void onResult(@NonNull Data data);
    }

    /* ------------------------------------------------------------- 对外入口 */

    /** 统一清除缓存时调用：删掉磁盘榜单并丢弃内存副本。 */
    public static void clearCache() {
        memory = null;
        memoryTime = 0;
        cacheFile().delete();
        Prefers.remove(KEY_IMAGE_BLOCKED + configuredImageBase());
        baseImageBlocked = false;
    }

    /** 备份恢复后调用：磁盘文件已被整体替换，只丢内存副本，不删文件。 */
    public static void reset() {
        memory = null;
        memoryTime = 0;
    }

    /** 读取榜单：缓存新鲜则立即回调，否则后台刷新；回调只会触发一次。 */
    public static void load(Callback callback) {
        ColdTrace.mark("rank.load.start", "mem=" + (memory != null) + ", fetching=" + fetching);
        Data cache = memory;
        // 「新鲜」还要「带 logo」：结构升级前写下的缓存没有 logoPath，
        // 若直接命中，主视觉在 TTL 内会一直没有片名 logo。宁可多拉一次。
        if (cache != null && !cache.isEmpty() && cache.hasAnyLogo() && System.currentTimeMillis() - memoryTime < TTL) {
            ColdTrace.mark("rank.hit.memory", "age=" + (System.currentTimeMillis() - memoryTime) + "ms");
            App.post(() -> callback.onResult(cache));
            return;
        }
        Disk disk = read();
        if (disk != null && !disk.data.isEmpty() && disk.data.hasAnyLogo() && System.currentTimeMillis() - disk.time < TTL) {
            memory = disk.data;
            memoryTime = disk.time;
            ColdTrace.mark("rank.hit.disk", "age=" + (System.currentTimeMillis() - disk.time) + "ms");
            App.post(() -> callback.onResult(disk.data));
            return;
        }
        // 旧缓存先交给展示层，主视觉/热词不必等后台 HTTP；刷新成功后再回调一次新数据。
        if (disk != null && !disk.data.isEmpty()) {
            memory = disk.data;
            memoryTime = disk.time;
            ColdTrace.mark("rank.stale.disk", "age=" + (System.currentTimeMillis() - disk.time) + "ms");
            final Data staleData = disk.data;
            App.post(() -> callback.onResult(staleData));
        }
        ColdTrace.mark("rank.fetch.schedule", "noUsableCache=" + (disk == null || disk.data.isEmpty()));
        Data stale = disk != null && !disk.data.isEmpty() ? disk.data : cache;
        synchronized (RankLatest.class) {
            PENDING.add(callback);
            if (!fetching) {
                fetching = true;
                EXECUTOR.execute(() -> fetchAndDispatch(stale));
            }
        }
    }

    /**
     * 一次刷新服务所有等待者。
     *
     * <p>首页可能在短时间内多次请求榜单（初始化、点「首页」刷新），搜索页也可能同时进入；
     * 若各自提交一个任务，单线程 executor 会串行发出完全相同的 HTTP。这里把调用者挂到
     * 同一批里，只发一次请求、一次写盘，再把结果分发给全部等待者。</p>
     */
    private static void fetchAndDispatch(Data stale) {
        long started = SystemClock.elapsedRealtime();
        Data result = stale == null ? Data.empty() : stale;
        try {
            Fetched fetched = fetch();
            if (fetched != null && !fetched.data.isEmpty()) {
                write(fetched.json);
                memory = fetched.data;
                memoryTime = System.currentTimeMillis();
                result = fetched.data;
            }
        } catch (Throwable ignored) {
            // 兜底沿用旧数据；finally 里必须把等待者全部释放，否则后续请求会一直挂住。
        } finally {
            List<Callback> callbacks;
            synchronized (RankLatest.class) {
                callbacks = new ArrayList<>(PENDING);
                PENDING.clear();
                fetching = false;
            }
            final Data dispatch = result;
            ColdTrace.mark("rank.dispatch", "callbacks=" + callbacks.size() + ", empty=" + dispatch.isEmpty()
                    + ", fetchTotal=" + (SystemClock.elapsedRealtime() - started) + "ms");
            App.post(() -> {
                for (Callback callback : callbacks) callback.onResult(dispatch);
            });
        }
    }

    /* ------------------------------------------------------------- 取数 / 解析 */

    private static Fetched fetch() {
        try {
            ColdTrace.mark("rank.http.start", URL);
            long t0 = SystemClock.elapsedRealtime();
            okhttp3.Response response = OkHttp.newCall(URL).execute();
            long t1 = SystemClock.elapsedRealtime();
            if (response.body() == null) return null;
            String json = response.body().string();
            long t2 = SystemClock.elapsedRealtime();
            if (TextUtils.isEmpty(json)) return null;
            ColdTrace.mark("rank.http.end", "code=" + response.code() + ", chars=" + json.length()
                    + ", connect=" + (t1 - t0) + "ms, body=" + (t2 - t1) + "ms");
            Data data = parse(json);
            ColdTrace.mark("rank.parse.end", "items=" + (data.getSeries().size() + data.getMovie().size())
                    + ", cost=" + (SystemClock.elapsedRealtime() - t2) + "ms");
            return data.isEmpty() ? null : new Fetched(json, data);
        } catch (Exception e) {
            ColdTrace.mark("rank.http.fail", String.valueOf(e));
            return null;
        }
    }

    private static Data parse(String json) {
        try {
            JsonElement rootElement = JsonParser.parseString(json);
            if (rootElement == null || !rootElement.isJsonObject()) return Data.empty();
            JsonObject root = rootElement.getAsJsonObject();
            JsonObject categories = root.has("categories") && root.get("categories").isJsonObject()
                    ? root.getAsJsonObject("categories") : null;
            if (categories == null) {
                // 兼容单类目响应（/rank/latest/movie 或 /rank/latest/series）。
                List<RankItem> items = readItems(root);
                return "series".equals(optString(root, "category"))
                        ? new Data(items, Collections.emptyList())
                        : new Data(Collections.emptyList(), items);
            }
            return new Data(readItems(categories.getAsJsonObject("series")),
                    readItems(categories.getAsJsonObject("movie")));
        } catch (Exception e) {
            return Data.empty();
        }
    }

    private static List<RankItem> readItems(JsonObject category) {
        if (category == null || !category.has("items") || !category.get("items").isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray array = category.getAsJsonArray("items");
        List<RankItem> out = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) continue;
            RankItem item = readItem(element.getAsJsonObject());
            if (!TextUtils.isEmpty(item.getTitle())) out.add(item);
        }
        return out;
    }

    private static RankItem readItem(JsonObject json) {
        RankItem item = new RankItem();
        item.title = optString(json, "title").trim();
        item.originalTitle = optString(json, "originalTitle");
        item.description = optString(json, "description");
        item.releaseDate = optString(json, "releaseDate");
        item.logoPath = optString(json, "logoPath");
        item.backdropPath = optString(json, "backdropPath");
        item.posterPath = optString(json, "posterPath");
        item.mediaType = optString(json, "mediaType");
        item.genreTitle = optString(json, "genreTitle");
        if (TextUtils.isEmpty(item.genreTitle)) item.genreTitle = firstGenre(json);
        // 主视觉元信息行的导演 / 主演：服务端已带数组，这里只取姓名；导演 1 名、主演最多 4 名。
        item.directors = personNames(json, "directors", 1);
        item.actors = personNames(json, "actors", 4);
        item.voteCount = optInt(json, "voteCount");
        item.rank = optInt(json, "rank");
        item.rating = optDouble(json, "rating");
        return item;
    }

    /** {@code genreItems} 第一个标题：老数据没有 {@code genreTitle} 时的兜底。 */
    private static String firstGenre(JsonObject json) {
        if (json == null || !json.has("genreItems") || !json.get("genreItems").isJsonArray()) return "";
        for (JsonElement element : json.getAsJsonArray("genreItems")) {
            if (element == null || !element.isJsonObject()) continue;
            String title = optString(element.getAsJsonObject(), "title");
            if (!TextUtils.isEmpty(title)) return title;
        }
        return "";
    }

    /**
     * 从 {@code directors} / {@code actors} 数组里取姓名拼成一串。
     *
     * <p>主演最多取 4 名、导演只取 1 名（需求上限），防止异常数据撑爆元信息行；
     * 按姓名去重、空姓名跳过。老榜单没有该字段时返回空串，展示层据此隐藏对应片段。</p>
     */
    private static String personNames(JsonObject json, String key, int limit) {
        if (json == null || !json.has(key) || !json.get(key).isJsonArray() || limit <= 0) return "";
        List<String> names = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray(key)) {
            if (element == null || !element.isJsonObject()) continue;
            String name = optString(element.getAsJsonObject(), "name").trim();
            if (TextUtils.isEmpty(name) || names.contains(name)) continue;
            names.add(name);
            if (names.size() >= limit) break;
        }
        return TextUtils.join("、", names);
    }

    private static String optString(JsonObject json, String key) {
        if (json == null || !json.has(key)) return "";
        try {
            JsonElement element = json.get(key);
            return element == null || element.isJsonNull() ? "" : element.getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int optInt(JsonObject json, String key) {
        if (json == null || !json.has(key)) return 0;
        try {
            JsonElement element = json.get(key);
            return element == null || element.isJsonNull() ? 0 : element.getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private static double optDouble(JsonObject json, String key) {
        if (json == null || !json.has(key)) return 0d;
        try {
            JsonElement element = json.get(key);
            return element == null || element.isJsonNull() ? 0d : element.getAsDouble();
        } catch (Exception e) {
            return 0d;
        }
    }

    /* ------------------------------------------------------------- 图片地址 */

    /** 拼完整剧照地址：配置了刮削站点就用它，否则用官方地址。 */
    public static String imageUrl(String path) {
        return imageUrl(imageBase(), TmdbApi.BACKDROP_SIZE, path);
    }

    /** 官方地址版本：配置的站点不支持图片时由展示层回落重试。 */
    public static String officialImageUrl(String path) {
        return imageUrl(DEFAULT_IMAGE_BASE, TmdbApi.BACKDROP_SIZE, path);
    }

    /**
     * 片名 logo 地址：走 {@link TmdbApi#LOGO_SIZE}（原图）。
     *
     * <p>logo 是透明 PNG，尺寸不大；w500 在 1080p 电视上按 200dp 高度渲染会发虚，
     * 因此与刮削链路保持一致取原图。</p>
     */
    public static String logoUrl(String path) {
        return imageUrl(imageBase(), TmdbApi.LOGO_SIZE, path);
    }

    /** logo 的官方地址版本：配置站点不支持图片路径时由展示层回落。 */
    public static String officialLogoUrl(String path) {
        return imageUrl(DEFAULT_IMAGE_BASE, TmdbApi.LOGO_SIZE, path);
    }

    /** 设置页改过全局连接方式或站点：首页回前台时据此重建已挂主视觉。 */
    public static void markConfigChanged() {
        // 用户刚确认过配置：清掉当前地址的不可用判定，让新配置真正生效。
        Prefers.remove(KEY_IMAGE_BLOCKED + configuredImageBase());
        baseImageBlocked = false;
        configChanged = true;
    }

    public static boolean consumeConfigChanged() {
        boolean changed = configChanged;
        configChanged = false;
        return changed;
    }

    /**
     * 标记配置站点不提供图片：首屏任一资源在配置地址上失败时调用。
     * 判定按地址持久化，冷启动后也不会对同一站点反复白发 404。
     */
    public static void markBaseImageUnavailable() {
        baseImageBlocked = true;
        Prefers.put(KEY_IMAGE_BLOCKED + configuredImageBase(), true);
    }

    /** 进入页面时按当前地址重载判定：用户换站点后给新地址一次机会。 */
    public static void resetBaseImage() {
        baseImageBlocked = Prefers.getBoolean(KEY_IMAGE_BLOCKED + configuredImageBase(), false);
    }

    /**
     * 原始图片基地址（不含失败降级）：官方 API 模式用独立的图片域名，
     * 其余模式沿用全局 API 地址；不可用时由 {@link #imageBase()} 回落到官方图片域名。
     */
    private static String configuredImageBase() {
        // api.themoviedb.org 只服务 /3/ 接口，图片必须走 image.tmdb.org。
        if (TmdbSetting.getMode() == TmdbSetting.MODE_OFFICIAL) return DEFAULT_IMAGE_BASE;
        for (TmdbSite site : TmdbSetting.getSites()) {
            if (site != null && site.isValid()) return site.base();
        }
        return DEFAULT_IMAGE_BASE;
    }

    /** 图片基地址：当前连接方式的图片地址，已判定不可用时回落官方默认地址。 */
    public static String imageBase() {
        String base = configuredImageBase();
        return baseImageBlocked && !TextUtils.equals(base, DEFAULT_IMAGE_BASE) ? DEFAULT_IMAGE_BASE : base;
    }

    private static String imageUrl(String base, String size, String path) {
        if (TextUtils.isEmpty(path)) return "";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        String file = path.startsWith("/") ? path : "/" + path;
        if (file.startsWith("/t/p/")) return base + file;
        return base + "/t/p/" + size + file;
    }

    /* ------------------------------------------------------------- 磁盘缓存 */

    private static final class Fetched {

        private final String json;
        private final Data data;

        private Fetched(String json, Data data) {
            this.json = json;
            this.data = data;
        }
    }

    private static final class Disk {

        private final long time;
        private final Data data;

        private Disk(long time, Data data) {
            this.time = time;
            this.data = data;
        }
    }

    private static File cacheFile() {
        return new File(Path.files(), CACHE_NAME);
    }

    private static Disk read() {
        File file = cacheFile();
        if (!file.exists() || file.length() == 0) {
            ColdTrace.mark("rank.disk.miss", "no=" + file.getAbsolutePath());
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            long t0 = SystemClock.elapsedRealtime();
            Data data = parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
            ColdTrace.mark("rank.disk.read", "bytes=" + file.length() + ", parse=" + (SystemClock.elapsedRealtime() - t0) + "ms");
            return data.isEmpty() ? null : new Disk(file.lastModified(), data);
        } catch (Exception e) {
            ColdTrace.mark("rank.disk.fail", String.valueOf(e));
            return null;
        }
    }

    private static void write(String json) {
        try {
            long t0 = SystemClock.elapsedRealtime();
            File target = cacheFile();
            File tmp = new File(target.getParentFile(), CACHE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (target.exists() && !target.delete()) return;
            tmp.renameTo(target);
            ColdTrace.mark("rank.disk.write", "bytes=" + json.length() + ", cost=" + (SystemClock.elapsedRealtime() - t0) + "ms");
        } catch (Exception ignored) {
            // 写盘失败不影响本次展示，内存缓存仍然有效。
        }
    }

    /* ------------------------------------------------------------- 数据模型 */

    /** 一份榜单响应：剧集段 + 电影段。 */
    public static final class Data {

        private final List<RankItem> series;
        private final List<RankItem> movie;

        private Data(List<RankItem> series, List<RankItem> movie) {
            this.series = series == null ? Collections.emptyList() : series;
            this.movie = movie == null ? Collections.emptyList() : movie;
        }

        public static Data empty() {
            return new Data(Collections.emptyList(), Collections.emptyList());
        }

        public List<RankItem> getSeries() {
            return series;
        }

        public List<RankItem> getMovie() {
            return movie;
        }

        public boolean isEmpty() {
            return series.isEmpty() && movie.isEmpty();
        }

        /**
         * 是否至少有一条 logo 数据。
         *
         * <p>用来识别「结构升级前写下的旧磁盘缓存」：旧缓存没有 {@code logoPath} 字段，
         * 解析后全为空；此时即使还在 TTL 内也触发一次后台刷新，避免主视觉长期没有 logo。
         * 新数据结构里 logo 覆盖率很高，不会导致缓存永远命中不了。</p>
         */
        public boolean hasAnyLogo() {
            for (RankItem item : series) if (item != null && item.hasLogo()) return true;
            for (RankItem item : movie) if (item != null && item.hasLogo()) return true;
            return false;
        }

        /**
         * 主视觉顺序：2 剧集 → 2 电影 交替，循环到 {@code limit} 条。
         *
         * <p>某一段条数不足时由另一段继续补位；两段都取完就提前结束。
         * 按归一化片名去重，避免同一部剧在电影榜里重复出现。</p>
         */
        public List<RankItem> hero(int limit) {
            if (limit <= 0) return Collections.emptyList();
            List<RankItem> out = new ArrayList<>(limit);
            Set<String> seen = new HashSet<>();
            Cursor s = new Cursor(series, seen, out);
            Cursor m = new Cursor(movie, seen, out);
            while (out.size() < limit) {
                int before = out.size();
                s.take(2);
                m.take(2);
                if (out.size() == before) break;
            }
            return out;
        }
    }

    private static final class Cursor {

        private final List<RankItem> source;
        private final Set<String> seen;
        private final List<RankItem> out;
        private int index;

        private Cursor(List<RankItem> source, Set<String> seen, List<RankItem> out) {
            this.source = source;
            this.seen = seen;
            this.out = out;
        }

        private void take(int count) {
            int added = 0;
            while (added < count && index < source.size()) {
                RankItem item = source.get(index++);
                if (item == null) continue;
                String key = TmdbStore.normalize(item.getTitle());
                if (TextUtils.isEmpty(key) || !seen.add(key)) continue;
                out.add(item);
                added++;
            }
        }
    }

    /** 榜单里的一条影片。字段名与服务端 JSON 一致，全部由服务端预置，不再二次刮削。 */
    public static final class RankItem {

        private String title = "";
        private String originalTitle = "";
        private String description = "";
        private String releaseDate = "";
        /** 片名 logo 相对路径（透明 PNG）；服务端富化缺失时为空串。 */
        private String logoPath = "";
        private String backdropPath = "";
        private String posterPath = "";
        private String mediaType = "";
        private String genreTitle = "";
        /** 导演姓名串，多个用「、」连接；老榜单没有该字段时为空串。 */
        private String directors = "";
        /** 主演姓名串，最多 3 名，多个用「、」连接。 */
        private String actors = "";
        private int voteCount;
        private int rank;
        private double rating;

        public String getTitle() {
            return title == null ? "" : title;
        }

        public String getOriginalTitle() {
            return originalTitle == null ? "" : originalTitle;
        }

        public String getDescription() {
            return description == null ? "" : description;
        }

        /** 年份：{@code releaseDate} 可能是 "2026" 或 "2026-05-01"，统一取前 4 位。 */
        public String getYear() {
            String value = releaseDate == null ? "" : releaseDate.trim();
            if (value.length() >= 4 && Character.isDigit(value.charAt(0))) return value.substring(0, 4);
            return "";
        }

        /** 片名 logo 相对路径；为空表示该条目没有 logo，展示层应回落本地缓存或文字片名。 */
        public String getLogo() {
            return logoPath == null ? "" : logoPath;
        }

        public boolean hasLogo() {
            return !TextUtils.isEmpty(getLogo());
        }

        public String getBackdrop() {
            return backdropPath == null ? "" : backdropPath;
        }

        public String getPoster() {
            return posterPath == null ? "" : posterPath;
        }

        public String getMediaType() {
            return mediaType == null ? "" : mediaType;
        }

        /** 题材：优先取 {@code genreTitle}，缺失时取 {@code genreItems} 的第一个标题。 */
        public String getGenre() {
            return genreTitle == null ? "" : genreTitle;
        }

        /** 导演姓名串（可能为空）。 */
        public String getDirectors() {
            return directors == null ? "" : directors;
        }

        /** 主演姓名串，最多 3 名（可能为空）。 */
        public String getActors() {
            return actors == null ? "" : actors;
        }

        public int getVoteCount() {
            return voteCount;
        }

        public int getRank() {
            return rank;
        }

        public double getRating() {
            return rating;
        }

        /** 评分文本；0 分视为无评分，返回空串。 */
        public String getRatingText() {
            if (rating <= 0d) return "";
            return new DecimalFormat("0.#").format(rating);
        }

        public boolean isTv() {
            return "tv".equalsIgnoreCase(mediaType);
        }

        public boolean isMovie() {
            return "movie".equalsIgnoreCase(mediaType);
        }

        public boolean hasBackdrop() {
            return !TextUtils.isEmpty(getBackdrop());
        }
    }
}

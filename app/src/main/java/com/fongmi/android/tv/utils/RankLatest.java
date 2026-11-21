package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbApi;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.setting.TmdbSite;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
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
 * <p><b>图片地址</b>：服务端只给 {@code backdropPath}（如 {@code /k0BY....jpg}），
 * 展示时拼成 {@code {base}/t/p/w1280/{path}}。{@code base} 优先取「设置 → 影片刮削设置」
 * 里已配置的第一个站点地址；一个都没配时回落到官方 {@code https://image.tmdb.org}。
 * 整条链路<b>不需要 API 密钥</b>，站点密钥只服务于刮削接口。</p>
 *
 * <p><b>缓存</b>：内存 + {@code files/rank_latest.json} 双层，TTL 30 分钟。命中直接回调、
 * 不联网；过期后后台刷新，刷新失败继续用旧数据，栏目不会因一次网络抖动而消失。
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

    private static final long TTL = 30L * 60 * 1000;
    private static final String CACHE_NAME = "rank_latest.json";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "RankLatest");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Data memory;
    private static volatile long memoryTime;

    private RankLatest() {
    }

    public interface Callback {

        /** 结果一定切回主线程；失败时若存在旧数据则回传旧数据，否则回传空数据。 */
        void onResult(@NonNull Data data);
    }

    /* ------------------------------------------------------------- 对外入口 */

    /** 统一清除缓存时调用：删掉磁盘榜单并丢弃内存副本。 */
    public static void clearCache() {
        memory = null;
        memoryTime = 0;
        cacheFile().delete();
    }

    /** 备份恢复后调用：磁盘文件已被整体替换，只丢内存副本，不删文件。 */
    public static void reset() {
        memory = null;
        memoryTime = 0;
    }

    /** 读取榜单：缓存新鲜则立即回调，否则后台刷新；回调只会触发一次。 */
    public static void load(Callback callback) {
        Data cache = memory;
        // 「新鲜」还要「带 logo」：结构升级前写下的缓存没有 logoPath，
        // 若直接命中，主视觉在 TTL 内会一直没有片名 logo。宁可多拉一次。
        if (cache != null && !cache.isEmpty() && cache.hasAnyLogo() && System.currentTimeMillis() - memoryTime < TTL) {
            App.post(() -> callback.onResult(cache));
            return;
        }
        Disk disk = read();
        if (disk != null && !disk.data.isEmpty() && disk.data.hasAnyLogo() && System.currentTimeMillis() - disk.time < TTL) {
            memory = disk.data;
            memoryTime = disk.time;
            App.post(() -> callback.onResult(disk.data));
            return;
        }
        // 旧缓存（无 logo）不直接返回，但在刷新失败时仍然作为兜底展示。
        if (disk != null && !disk.data.isEmpty()) {
            memory = disk.data;
            memoryTime = disk.time;
        }
        Data stale = disk != null && !disk.data.isEmpty() ? disk.data : cache;
        EXECUTOR.execute(() -> {
            Fetched fetched = fetch();
            if (fetched == null || fetched.data.isEmpty()) {
                Data fallback = stale == null ? Data.empty() : stale;
                App.post(() -> callback.onResult(fallback));
                return;
            }
            write(fetched.json);
            memory = fetched.data;
            memoryTime = System.currentTimeMillis();
            App.post(() -> callback.onResult(fetched.data));
        });
    }

    /* ------------------------------------------------------------- 取数 / 解析 */

    private static Fetched fetch() {
        try {
            okhttp3.Response response = OkHttp.newCall(URL).execute();
            if (response.body() == null) return null;
            String json = response.body().string();
            if (TextUtils.isEmpty(json)) return null;
            Data data = parse(json);
            return data.isEmpty() ? null : new Fetched(json, data);
        } catch (Exception e) {
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

    /** 图片基地址：设置里第一个有效站点，未配置时官方默认地址。 */
    public static String imageBase() {
        for (TmdbSite site : TmdbSetting.getSites()) {
            if (site != null && site.isValid()) return site.base();
        }
        return DEFAULT_IMAGE_BASE;
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
        if (!file.exists() || file.length() == 0) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            Data data = parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
            return data.isEmpty() ? null : new Disk(file.lastModified(), data);
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(String json) {
        try {
            File target = cacheFile();
            File tmp = new File(target.getParentFile(), CACHE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (target.exists() && !target.delete()) return;
            tmp.renameTo(target);
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

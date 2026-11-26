package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbApi;
import com.fongmi.android.tv.setting.TmdbSetting;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页分类模块的数据层：豆瓣 / TMDB 热榜，以及奈飞 / HBO / 迪士尼的热门电影与剧集。
 *
 * <p><b>两种响应结构</b>：{@code /rank/latest/movie|series} 返回 {@code items[]}，
 * 字段是驼峰（{@code title} / {@code posterPath} / {@code rating}），海报是豆瓣绝对地址；
 * {@code /trending/movie|tv} 返回 TMDB 原生 {@code results[]}，字段下划线
 * （{@code title|name} / {@code poster_path} / {@code vote_average}），海报是相对路径。
 * 解析时统一成「片名 + 海报 + 评分」三项，其余字段（简介、演员、类型、票数等）一律丢弃。</p>
 *
 * <p><b>条数</b>：每个分类合并多页结果并按 TMDB ID 去重，最多 50 条；首页每行只取前
 * {@link #HOME_LIMIT} 条，其余进「更多影片」页。缺失片名或海报的条目直接跳过。</p>
 *
 * <p><b>缓存</b>：内存 + {@code files/home_board_v3_<n>.json}，TTL 12 小时。
 * 过期时重新拉取；拉取失败继续用旧数据，只有「从未成功过」才回空列表，由展示层收起基础榜单或显示平台提示。</p>
 *
 * <p><b>海报地址</b>：豆瓣图床有防盗链（无 Referer 直接 418），请求头由
 * {@link ImgUtil#getUrl(String)} 统一补；TMDB 相对路径在这里拼成
 * {@code image.tmdb.org/t/p/w342{path}}。默认 API 反代只负责取数，图片走独立 CDN，
 * 避免图片请求挤占反代链路；自定义连接仍沿用其图片地址。w342 的下载体积约为 w500 的一半。</p>
 */
public final class HomeBoard {

    public static final int DOUBAN_MOVIE = 0;
    public static final int DOUBAN_SERIES = 1;
    public static final int TMDB_MOVIE = 2;
    public static final int TMDB_TV = 3;
    public static final int NETFLIX_MOVIE = 4;
    public static final int NETFLIX_TV = 5;
    public static final int HBO_MOVIE = 6;
    public static final int HBO_TV = 7;
    public static final int DISNEY_MOVIE = 8;
    public static final int DISNEY_TV = 9;
    public static final int COUNT = 10;

    /** 剧集 Discover 使用制作/播出网络；电影 Discover 不支持该参数。 */
    private static final int[] NETWORKS = {0, 0, 0, 0, 213, 213, 49, 49, 2739, 2739};
    /** 电影按美国区播放供应商筛选：Netflix / HBO Max / Disney+。 */
    private static final int[] MOVIE_PROVIDERS = {0, 0, 0, 0, 8, 0, 1899, 0, 337, 0};

    /** 首页每行最多展示的影片数；到 10 部时末尾追加「查看全部」卡片。 */
    public static final int HOME_LIMIT = 10;

    /** 「更多影片」页固定展示 50 部；接口不足时保留全部可用条目。 */
    private static final int MAX_ITEMS = 50;
    private static final int MAX_PAGES = 3;

    private static final String BASE = "https://rank-json.tmdbapi.cc.cd";
    private static final String[] PATHS = {
            "/rank/latest/movie",
            "/rank/latest/series",
            "/trending/movie",
            "/trending/tv"
    };
    /** v3 使旧缓存失效，同时把更多影片从 20/40 条扩容到 50 条。 */
    private static final String CACHE_PREFIX = "home_board_v3_";
    private static final long TTL = 12L * 60 * 60 * 1000;
    /** 首页卡片无需 w500；w342 体积约为其一半，加载更快。 */
    private static final String POSTER_SIZE = "w342";

    // 平台榜单不能排在前四个基础榜单之后；独立线程池让两组请求互不阻塞。
    private static final ExecutorService BASE_EXECUTOR = newExecutor("HomeBoardBase", 4);
    private static final ExecutorService PLATFORM_EXECUTOR = newExecutor("HomeBoardPlatform", 3);

    private static final Cache[] MEMORY = new Cache[COUNT];
    private static final boolean[] FETCHING = new boolean[COUNT];

    private HomeBoard() {
    }

    public interface Callback {

        /** 结果一定切回主线程；失败且无旧数据时回空列表。 */
        void onResult(@NonNull List<Item> items);
    }

    /** 一条卡片数据：只保留片名、海报、评分。 */
    public static final class Item {

        private final String title;
        private final String poster;
        private final String score;

        private Item(String title, String poster, String score) {
            this.title = title;
            this.poster = poster;
            this.score = score;
        }

        public String getTitle() {
            return title;
        }

        public String getPoster() {
            return poster;
        }

        /** 评分文本；无评分返回空串，卡片不显示角标。 */
        public String getScore() {
            return score;
        }
    }

    /* ------------------------------------------------------------- 对外入口 */

    public static void load(int board, Callback callback) {
        if (board < 0 || board >= COUNT) {
            App.post(() -> callback.onResult(Collections.emptyList()));
            return;
        }
        Cache cache = MEMORY[board];
        if (cache != null && System.currentTimeMillis() - cache.time < TTL) {
            App.post(() -> callback.onResult(cache.items));
            return;
        }
        synchronized (HomeBoard.class) {
            if (FETCHING[board]) {
                // 同一分类已有任务在跑：先回内存旧数据，不排队等第二次网络。
                App.post(() -> callback.onResult(cache == null ? Collections.emptyList() : cache.items));
                return;
            }
            FETCHING[board] = true;
        }
        // 磁盘读取与网络都在后台线程完成：分类 JSON 不能压在主线程上。
        ExecutorService executor = board <= TMDB_TV ? BASE_EXECUTOR : PLATFORM_EXECUTOR;
        executor.execute(() -> loadAndDispatch(board, callback));
    }

    private static ExecutorService newExecutor(String name, int threads) {
        return Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 清除缓存：删磁盘文件并丢弃内存副本。 */
    public static void clearCache() {
        for (int i = 0; i < COUNT; i++) {
            MEMORY[i] = null;
            cacheFile(i).delete();
        }
    }

    /** 备份恢复后调用：磁盘文件已被整体替换，只丢内存副本。 */
    public static void reset() {
        for (int i = 0; i < COUNT; i++) MEMORY[i] = null;
    }

    /* ------------------------------------------------------------- 取数 / 解析 */

    private static void loadAndDispatch(int board, Callback callback) {
        List<Item> result = Collections.emptyList();
        try {
            Cache disk = read(board);
            if (disk != null && System.currentTimeMillis() - disk.time < TTL) {
                MEMORY[board] = disk;
                result = disk.items;
            } else {
                // 过期旧数据先兜底：本次拉取失败时至少还有内容，不会让整行突然消失。
                if (disk != null) result = disk.items;
                String json = fetchBoard(board);
                if (!TextUtils.isEmpty(json)) {
                    List<Item> items = parse(json);
                    if (!items.isEmpty()) {
                        write(board, json);
                        MEMORY[board] = new Cache(System.currentTimeMillis(), items);
                        result = items;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 兜底沿用旧数据
        } finally {
            synchronized (HomeBoard.class) {
                FETCHING[board] = false;
            }
        }
        final List<Item> dispatch = result;
        App.post(() -> callback.onResult(dispatch));
    }

    private static String fetchBoard(int board) {
        JsonArray items = new JsonArray();
        if (board <= TMDB_TV) {
            append(items, fetch(BASE + PATHS[board]));
            boolean douban = board <= DOUBAN_SERIES;
            String type = board == DOUBAN_MOVIE || board == TMDB_MOVIE ? "movie" : "tv";
            int firstPage = douban ? 1 : 2;
            for (int page = firstPage; page <= MAX_PAGES && parse(items).size() < MAX_ITEMS; page++) {
                append(items, fetchTmdb("/3/trending/" + type + "/week?language=zh-CN&page=" + page));
            }
        } else {
            boolean movie = board % 2 == 0;
            String type = movie ? "movie" : "tv";
            String filter = movie
                    ? "watch_region=US&with_watch_providers=" + MOVIE_PROVIDERS[board]
                    : "with_networks=" + NETWORKS[board];
            String path = "/3/discover/" + type + "?language=zh-CN&sort_by=popularity.desc"
                    + "&vote_count.gte=" + (movie ? 50 : 10) + "&include_adult=false&" + filter;
            for (int page = 1; page <= MAX_PAGES && parse(items).size() < MAX_ITEMS; page++) {
                append(items, fetchTmdb(path + "&page=" + page));
            }
        }
        JsonObject root = new JsonObject();
        root.add("results", items);
        return root.toString();
    }

    private static String fetchTmdb(String path) {
        try {
            return TmdbApi.getJson(path);
        } catch (Exception e) {
            return "";
        }
    }

    private static void append(JsonArray target, String json) {
        JsonArray source = array(json);
        if (source != null) for (JsonElement element : source) target.add(element);
    }

    private static JsonArray array(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (root == null || !root.isJsonObject()) return null;
            JsonObject object = root.getAsJsonObject();
            if (object.has("items") && object.get("items").isJsonArray()) return object.getAsJsonArray("items");
            if (object.has("results") && object.get("results").isJsonArray()) return object.getAsJsonArray("results");
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String fetch(String url) {
        try (okhttp3.Response response = OkHttp.newCall(url).execute()) {
            if (response.body() == null) return "";
            return response.body().string();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<Item> parse(String json) {
        JsonArray array = array(json);
        return array == null ? Collections.emptyList() : parse(array);
    }

    private static List<Item> parse(JsonArray array) {
        try {
            String posterBase = TmdbSetting.getMode() == TmdbSetting.MODE_CUSTOM
                    ? RankLatest.imageBase() : RankLatest.DEFAULT_IMAGE_BASE;
            List<Item> out = new ArrayList<>(MAX_ITEMS);
            Set<String> seen = new HashSet<>();
            for (JsonElement element : array) {
                if (out.size() >= MAX_ITEMS) break;
                if (element == null || !element.isJsonObject()) continue;
                JsonObject json = element.getAsJsonObject();
                String title = optString(json, "title");
                if (TextUtils.isEmpty(title)) title = optString(json, "name");
                String poster = optString(json, "posterPath");
                if (TextUtils.isEmpty(poster)) poster = optString(json, "poster_path");
                if (TextUtils.isEmpty(title) || TextUtils.isEmpty(poster)) continue;
                title = title.trim();
                String id = optString(json, "id");
                if (!seen.add(TextUtils.isEmpty(id) ? title : id)) continue;
                double rating = json.has("rating") ? optDouble(json, "rating") : optDouble(json, "vote_average");
                out.add(new Item(title, absolute(poster, posterBase), ratingText(rating)));
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** 豆瓣给的是绝对地址；TMDB 相对路径按默认 CDN 或自定义图片地址拼接。 */
    private static String absolute(String poster, String posterBase) {
        if (poster.startsWith("http://") || poster.startsWith("https://")) return poster;
        String path = poster.startsWith("/") ? poster : "/" + poster;
        return posterBase + "/t/p/" + POSTER_SIZE + path;
    }

    /** 评分统一走 ArtRating：10 分制固定一位小数（9 → 9.0），无效值返回空串不显示角标。 */
    private static String ratingText(double rating) {
        return ArtRating.normalize(String.valueOf(rating));
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

    private static double optDouble(JsonObject json, String key) {
        if (json == null || !json.has(key)) return 0d;
        try {
            JsonElement element = json.get(key);
            return element == null || element.isJsonNull() ? 0d : element.getAsDouble();
        } catch (Exception e) {
            return 0d;
        }
    }

    /* ------------------------------------------------------------- 磁盘缓存 */

    private static final class Cache {

        private final long time;
        private final List<Item> items;

        private Cache(long time, List<Item> items) {
            this.time = time;
            this.items = items;
        }
    }

    private static File cacheFile(int board) {
        return new File(Path.files(), CACHE_PREFIX + board + ".json");
    }

    private static Cache read(int board) {
        File file = cacheFile(board);
        if (!file.exists() || file.length() == 0) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
            List<Item> items = parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
            return items.isEmpty() ? null : new Cache(file.lastModified(), items);
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(int board, String json) {
        try {
            File target = cacheFile(board);
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (target.exists() && !target.delete()) return;
            tmp.renameTo(target);
        } catch (Exception ignored) {
            // 写盘失败不影响本次展示
        }
    }
}

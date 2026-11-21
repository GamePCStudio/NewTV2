package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页四个分类模块的数据层：豆瓣热门电影 / 豆瓣热门剧集 / TMDB今日电影 / TMDB今日剧集。
 *
 * <p><b>两种响应结构</b>：{@code /rank/latest/movie|series} 返回 {@code items[]}，
 * 字段是驼峰（{@code title} / {@code posterPath} / {@code rating}），海报是豆瓣绝对地址；
 * {@code /trending/movie|tv} 返回 TMDB 原生 {@code results[]}，字段下划线
 * （{@code title|name} / {@code poster_path} / {@code vote_average}），海报是相对路径。
 * 解析时统一成「片名 + 海报 + 评分」三项，其余字段（简介、演员、类型、票数等）一律丢弃。</p>
 *
 * <p><b>条数</b>：整份榜单都保留（上限 {@code MAX_ITEMS}），首页每行只取前
 * {@link #HOME_LIMIT} 条，其余进「更多影片」页；缺失片名或海报的条目直接跳过，
 * 保证整行都是可用海报。</p>
 *
 * <p><b>缓存</b>：内存 + {@code files/home_board_<n>.json}，TTL 12 小时，与主视觉榜单同口径。
 * 过期时重新拉取；拉取失败继续用旧数据，只有「从未成功过」才回空列表，由展示层整行收起。</p>
 *
 * <p><b>海报地址</b>：豆瓣图床有防盗链（无 Referer 直接 418），请求头由
 * {@link ImgUtil#getUrl(String)} 统一补；TMDB 相对路径在这里拼成
 * {@code {base}/t/p/w500{path}}，{@code base} 跟随全局 TMDB 连接方式。</p>
 */
public final class HomeBoard {

    public static final int DOUBAN_MOVIE = 0;
    public static final int DOUBAN_SERIES = 1;
    public static final int TMDB_MOVIE = 2;
    public static final int TMDB_TV = 3;
    public static final int COUNT = 4;

    /** 首页每行最多展示的影片数；到 10 部时末尾追加「查看全部」卡片。 */
    public static final int HOME_LIMIT = 10;

    /**
     * 每个分类最多保留的条数（「更多影片」页的数据上限）。
     *
     * <p>数据源本身是有限榜单：rank 系列 40 条、trending 系列 20 条，这里给一个上限
     * 只为防御异常响应，不影响正常展示。</p>
     */
    private static final int MAX_ITEMS = 60;

    private static final String BASE = "https://rank-json.tmdbapi.cc.cd";
    private static final String[] PATHS = {
            "/rank/latest/movie",
            "/rank/latest/series",
            "/trending/movie",
            "/trending/tv"
    };
    private static final String CACHE_PREFIX = "home_board_";
    private static final long TTL = 12L * 60 * 60 * 1000;
    /** 豆瓣海报尺寸：卡片最宽约 300dp，w500 足够且比 w780 省一半流量。 */
    private static final String POSTER_SIZE = "w500";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "HomeBoard");
        thread.setDaemon(true);
        return thread;
    });

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
        // 磁盘读取与网络都在后台线程完成：四个分类加起来约 150KB，不能压在主线程上。
        EXECUTOR.execute(() -> loadAndDispatch(board, callback));
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
                String json = fetch(BASE + PATHS[board]);
                if (!TextUtils.isEmpty(json)) {
                    List<Item> items = parse(json, board);
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

    private static String fetch(String url) {
        try (okhttp3.Response response = OkHttp.newCall(url).execute()) {
            if (response.body() == null) return "";
            return response.body().string();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<Item> parse(String json, int board) {
        try {
            JsonElement rootElement = JsonParser.parseString(json);
            if (rootElement == null || !rootElement.isJsonObject()) return Collections.emptyList();
            JsonObject root = rootElement.getAsJsonObject();
            // rank 系列是 items[]，trending 是 TMDB 原生 results[]
            JsonArray array = null;
            if (root.has("items") && root.get("items").isJsonArray()) array = root.getAsJsonArray("items");
            else if (root.has("results") && root.get("results").isJsonArray()) array = root.getAsJsonArray("results");
            if (array == null) return Collections.emptyList();
            boolean douban = board == DOUBAN_MOVIE || board == DOUBAN_SERIES;
            List<Item> out = new ArrayList<>(MAX_ITEMS);
            for (JsonElement element : array) {
                if (out.size() >= MAX_ITEMS) break;
                if (element == null || !element.isJsonObject()) continue;
                JsonObject json1 = element.getAsJsonObject();
                String title = optString(json1, "title");
                if (TextUtils.isEmpty(title)) title = optString(json1, "name");
                String poster = optString(json1, douban ? "posterPath" : "poster_path");
                if (TextUtils.isEmpty(title) || TextUtils.isEmpty(poster)) continue;
                double rating = optDouble(json1, douban ? "rating" : "vote_average");
                out.add(new Item(title.trim(), absolute(poster), ratingText(rating)));
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** 豆瓣给的是绝对地址；TMDB 只给相对路径，按全局连接方式拼图片基地址。 */
    private static String absolute(String poster) {
        if (poster.startsWith("http://") || poster.startsWith("https://")) return poster;
        String path = poster.startsWith("/") ? poster : "/" + poster;
        return RankLatest.imageBase() + "/t/p/" + POSTER_SIZE + path;
    }

    private static String ratingText(double rating) {
        if (rating <= 0d) return "";
        return new DecimalFormat("0.#").format(rating);
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
            List<Item> items = parse(new String(out.toByteArray(), StandardCharsets.UTF_8), board);
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

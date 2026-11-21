package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.google.gson.annotations.SerializedName;

/**
 * 首页主视觉轮播的一条数据。
 *
 * <p>全部字段来自 {@link com.fongmi.android.tv.utils.RankLatest} 拉取的榜单：
 * 服务端已经通过 TMDB 完成富化，片名、简介、年份、评分、题材与横版剧照一次给全。
 * 展示层<b>不再</b>按片名去 {@code TmdbStore} 查素材、也不再触发 TMDB 刮削，
 * 因此这里直接带着 {@code backdrop}（相对路径，如 {@code /k0BY....jpg}），
 * 由 {@code RankLatest.imageUrl()} 拼成完整地址。</p>
 *
 * <p>{@code type} 只有剧集 / 电影两种。</p>
 */
public class DoubanHotItem {

    /** 剧集。 */
    public static final String TYPE_TV = "tv";
    /** 电影。 */
    public static final String TYPE_MOVIE = "movie";

    @SerializedName("title")
    private String title;
    @SerializedName("type")
    private String type;
    /** 评分，保留一位小数的字符串（如 "8.6"）；空串表示暂无评分。 */
    @SerializedName("rating")
    private String rating;
    /** 上映 / 首播年份。 */
    @SerializedName("year")
    private String year;
    /** 题材（如「剧情」）。 */
    @SerializedName("genre")
    private String genre;
    /** 剧情简介。 */
    @SerializedName("overview")
    private String overview;
    /** 横版剧照相对路径；为空表示该条目没有可用剧照。 */
    @SerializedName("backdrop")
    private String backdrop;
    @SerializedName("id")
    private String id;

    public String getTitle() {
        return title == null ? "" : title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getType() {
        return type == null ? "" : type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRating() {
        return rating == null ? "" : rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
    }

    public String getYear() {
        return year == null ? "" : year;
    }

    public void setYear(String year) {
        this.year = year;
    }

    public String getGenre() {
        return genre == null ? "" : genre;
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public String getOverview() {
        return overview == null ? "" : overview;
    }

    public void setOverview(String overview) {
        this.overview = overview;
    }

    public String getBackdrop() {
        return backdrop == null ? "" : backdrop;
    }

    public void setBackdrop(String backdrop) {
        this.backdrop = backdrop;
    }

    public String getId() {
        return id == null ? "" : id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean hasBackdrop() {
        return !TextUtils.isEmpty(getBackdrop());
    }
}

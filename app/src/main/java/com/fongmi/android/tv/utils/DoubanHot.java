package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.bean.DoubanHotItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 首页主视觉的数据源：{@link RankLatest} 的剧集 / 电影两段榜单 → 2 + 2 交替合成 12 条。
 *
 * <p><b>数据来源</b>：{@code rank/latest} 单一接口，服务端已用 TMDB 完成富化，
 * 每条都带横版剧照相对路径、简介、评分、年份与题材。因此这里不再像旧实现那样
 * 只拿片名、再按片名逐个触发 TMDB 刮削补图 —— 主视觉的显示链路完全不依赖
 * 刮削配置里的 API 密钥，只需图片基地址（取已配置站点，未配置用官方地址）。</p>
 *
 * <p><b>顺序</b>：2 剧集 → 2 电影 交替，循环到 12 条；某一段不足时由另一段补位，
 * 两段都取完则提前结束。去重按归一化片名，避免电影榜里重复出现同一部。</p>
 *
 * <p><b>缓存</b>：完全交给 {@link RankLatest}（内存 + 磁盘，TTL 30 分钟，失败回退旧数据）。
 * 本类只做模型转换，不再单独落盘。</p>
 */
public final class DoubanHot {

    /** 主视觉条数：2 剧集 + 2 电影 交替，共 12 条。 */
    public static final int LIMIT = RankLatest.HERO_LIMIT;

    private DoubanHot() {
    }

    public interface Callback {

        /** 结果一定切回主线程；失败时若存在旧数据则回传旧数据，否则回传空列表。 */
        void onResult(@NonNull List<DoubanHotItem> items);
    }

    /** 统一清除缓存时调用：删掉磁盘榜单并丢弃内存副本，下次进首页重新拉取。 */
    public static void clearCache() {
        RankLatest.clearCache();
    }

    /** 备份恢复后调用：磁盘缓存已被整体替换，只丢内存副本，不删文件。 */
    public static void reset() {
        RankLatest.reset();
    }

    public static void load(Callback callback) {
        RankLatest.load(data -> callback.onResult(convert(data.hero(LIMIT))));
    }

    private static List<DoubanHotItem> convert(List<RankLatest.RankItem> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        List<DoubanHotItem> out = new ArrayList<>(source.size());
        for (RankLatest.RankItem rank : source) {
            if (rank == null || TextUtils.isEmpty(rank.getTitle())) continue;
            DoubanHotItem item = new DoubanHotItem();
            item.setTitle(rank.getTitle());
            item.setType(rank.isTv() ? DoubanHotItem.TYPE_TV : DoubanHotItem.TYPE_MOVIE);
            item.setRating(rank.getRatingText());
            item.setYear(rank.getYear());
            item.setGenre(rank.getGenre());
            item.setOverview(rank.getDescription());
            item.setBackdrop(rank.getBackdrop());
            out.add(item);
        }
        return out;
    }
}

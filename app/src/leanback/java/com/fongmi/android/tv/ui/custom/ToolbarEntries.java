package com.fongmi.android.tv.ui.custom;

import android.view.View;

import com.fongmi.android.tv.databinding.ViewHomeToolbarBinding;
import com.fongmi.android.tv.setting.TmdbSetting;

/**
 * 顶栏频道入口的显隐。
 *
 * <p>首页、三个频道页、片库共用同一份 {@code view_home_toolbar.xml}，所以「电影 / 剧集 / 综艺」
 * 三个入口的判定集中在这里，避免各页各自实现后出现「首页隐藏了、片库还在」的不一致。</p>
 *
 * <p>三个频道页的内容全部来自热榜 + 刮削素材：影片刮削总开关关闭，或 TMDB 未配置站点
 * 且 Douban Bridge 也不可用时，入口没有可看的内容，直接 {@code GONE}；
 * 同一行的左右走位由布局顺序决定，隐藏后自然串联，不需要改焦点链。</p>
 */
public final class ToolbarEntries {

    private ToolbarEntries() {
    }

    public static void apply(ViewHomeToolbarBinding toolbar) {
        if (toolbar == null) return;
        int visibility = TmdbSetting.isChannelAvailable() ? View.VISIBLE : View.GONE;
        toolbar.navMovie.setVisibility(visibility);
        toolbar.navSeries.setVisibility(visibility);
        toolbar.navVariety.setVisibility(visibility);
    }
}

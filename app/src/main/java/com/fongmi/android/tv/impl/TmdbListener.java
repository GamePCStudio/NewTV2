package com.fongmi.android.tv.impl;

/** 「设置 → 影片刮削设置」中 TMDB 配置保存后的刷新回调。 */
public interface TmdbListener {

    /** 连接方式或站点信息已变更，设置页需要刷新摘要与文本。 */
    void onTmdbConfigChanged();
}

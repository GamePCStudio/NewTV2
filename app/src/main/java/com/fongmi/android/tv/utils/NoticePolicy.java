package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.setting.NoticeSetting;

import java.util.List;
import java.util.Locale;

/**
 * 提示（Toast）过滤的唯一判定入口，所有出口共用同一套规则：<b>总开关 + 关键词包含匹配</b>。
 *
 * <p>覆盖的出口：{@link Notify}（应用内统一提示）、{@link ToastGuard}（站源 native 直连的
 * 系统 Toast 入队前拦截）、站源 {@code Result.msg}（播放详情页与网页解析）、
 * 弹幕搜索浮层的空结果与失败提示。浮层自身的空态、进度与「请配置弹幕接口」引导不受影响。</p>
 *
 * <p>判定不缓存、不注册全局 hook、不改数据模型；总开关关闭或词表为空时一律返回 false，
 * 行为与未开启过滤完全一致。</p>
 */
public final class NoticePolicy {

    private NoticePolicy() {
    }

    /** 站源消息（Result.msg / Runtime.toast / WebActivity）是否属于要静默的播报。 */
    public static boolean shouldBlockStationMessage(CharSequence text) {
        return shouldBlockNotice(text);
    }

    /** 一条提示是否要静默：总开关开启，且文案命中词表里的任一关键词。 */
    public static boolean shouldBlockNotice(CharSequence text) {
        if (!NoticeSetting.isMasterEnabled()) return false;
        if (TextUtils.isEmpty(text)) return false;
        List<String> keys = NoticeSetting.getKeywords();
        if (keys.isEmpty()) return false;
        String body = text.toString();
        String lower = body.toLowerCase(Locale.ROOT);
        for (String key : keys) {
            if (lower.contains(key.toLowerCase(Locale.ROOT)) || body.contains(key)) return true;
        }
        return false;
    }
}

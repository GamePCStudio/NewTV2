package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示（Toast）过滤设置：只决定「哪些提示不弹」，不参与播放、弹幕加载与站源解析。
 *
 * <p>判定维度只有两个：<b>总开关</b> 与 <b>关键词表</b>。站源播报、站源自带的弹幕加载播报、
 * 弹幕搜索浮层的空结果与失败提示，全部统一走关键词包含匹配 —— 早先按类别拆开的子开关
 * 已经合并进默认词表，用户想放宽或收紧直接改词表即可。</p>
 *
 * <p>所有取值都在判定时实时读取，设置页改完立即生效，不需要重启应用。</p>
 */
public class NoticeSetting {

    /**
     * 默认关键词：只保留四个核心词，覆盖实测出现的两类噪音 —— 弹幕相关播报与去广告播报。
     *
     * <p>注意这是<b>包含匹配</b>的宽词：命中「失败」「加载」的其它提示（如播放失败、解析失败）
     * 也会一起被静默。想保留某些提示，把它们从词表里删掉即可。</p>
     */
    public static final String DEFAULT_KEYWORDS = "弹幕|失败|加载|广告";

    /** 首版内置词表：老设备上已写入偏好，读取时自动升级到当前默认词表。 */
    private static final String LEGACY_KEYWORDS_V1 = "去广告|去廣告|广告已|廣告已|已跳过广告|已跳過廣告|移除广告|移除廣告|广告过滤|廣告過濾";

    /** 第二版内置词表（去广告 + 弹幕匹配结果的完整短语）。 */
    private static final String LEGACY_KEYWORDS_V2 = "已过滤视频中广告|已過濾視頻中廣告|过滤视频中广告|過濾視頻中廣告|自动匹配不到弹幕|自動匹配不到彈幕|匹配不到弹幕|匹配不到彈幕|去广告|去廣告|广告已|廣告已|已跳过广告|已跳過廣告|移除广告|移除廣告|广告过滤|廣告過濾";

    private static final String KEY_MASTER = "notice_master";
    private static final String KEY_KEYWORDS = "notice_keywords";

    /** 总开关：关闭后提示全部恢复原样，等价于本次改动未发生。默认开启。 */
    public static boolean isMasterEnabled() {
        return Prefers.getBoolean(KEY_MASTER, true);
    }

    public static void putMaster(boolean value) {
        Prefers.put(KEY_MASTER, value);
    }

    /** 原始关键词串；用户清空后就是空串，不会再回落到默认值。历史内置词表自动升级一次。 */
    public static String getKeywordsRaw() {
        String raw = Prefers.getString(KEY_KEYWORDS, DEFAULT_KEYWORDS);
        if (LEGACY_KEYWORDS_V1.equals(raw) || LEGACY_KEYWORDS_V2.equals(raw)) {
            Prefers.put(KEY_KEYWORDS, DEFAULT_KEYWORDS);
            return DEFAULT_KEYWORDS;
        }
        return raw;
    }

    public static void putKeywordsRaw(String value) {
        Prefers.put(KEY_KEYWORDS, value == null ? "" : value);
    }

    public static void resetKeywords() {
        Prefers.put(KEY_KEYWORDS, DEFAULT_KEYWORDS);
    }

    /** 换行 / 竖线 / 中英文逗号 / 顿号分隔，忽略空项与首尾空格。 */
    public static List<String> getKeywords() {
        List<String> list = new ArrayList<>();
        String raw = getKeywordsRaw();
        if (raw == null || raw.isEmpty()) return list;
        for (String part : raw.split("[\\n\\r|,，、]+")) {
            String key = part.trim();
            if (!key.isEmpty()) list.add(key);
        }
        return list;
    }
}

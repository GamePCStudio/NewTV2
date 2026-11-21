package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.TmdbApi;
import com.fongmi.android.tv.utils.RankLatest;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TMDB 刮削配置：全局连接方式 + 单一 API 站点 + 刮削开关与失败提示。
 *
 * <p>连接方式决定所有 TMDB 请求的基地址：默认反代、官方 API、自定义反代。
 * 项目内不再有其它数据源，所有图片与元数据拉取都经由这里给出的地址。</p>
 *
 * <p>站点记录形如 {@code {"url":"...","key":"..."}}，自定义反代地址可带专属密钥，
 * 密钥允许为空（反代服务可自行注入）；官方地址下填写的密钥同样通过这条记录读取。</p>
 *
 * <p>兼容早期版本：旧版 {@code tmdb_api_url} + {@code tmdb_api_key} 或站点数组会在首次读取时
 * 迁移成单条站点记录，不注入任何内置密钥。</p>
 */
public final class TmdbSetting {

    /** 默认反代：内置公共镜像，密钥可留空。 */
    public static final int MODE_PROXY = 0;
    /** 官方 API：使用 themoviedb 官方接口地址。 */
    public static final int MODE_OFFICIAL = 1;
    /** 自定义反代：地址与密钥由用户提供。 */
    public static final int MODE_CUSTOM = 2;

    public static final String PROXY_API_URL = "https://1.tmdbapi.cc.cd";
    public static final String OFFICIAL_API_URL = "https://api.themoviedb.org";

    private static final String KEY_MODE = "tmdb_mode";
    private static final String KEY_SITE = "tmdb_site";
    private static final String KEY_LEGACY_SITES = "tmdb_sites";
    private static final String KEY_LEGACY_URL = "tmdb_api_url";
    private static final String KEY_LEGACY_KEY = "tmdb_api_key";
    private static final String KEY_TMDB_SCRAPE = "tmdb_scrape_tmdb";
    private static final String KEY_ERROR_TOAST = "tmdb_error_toast";

    /** 轮换游标保留给 API 层的统一请求入口；当前只有一个全局站点。 */
    private static final AtomicInteger CURSOR = new AtomicInteger();
    private static volatile TmdbSite cache;

    private TmdbSetting() {
    }

    /** 当前连接方式。 */
    public static int getMode() {
        int mode = Prefers.getInt(KEY_MODE, MODE_PROXY);
        return mode == MODE_OFFICIAL || mode == MODE_CUSTOM ? mode : MODE_PROXY;
    }

    public static void putMode(int mode) {
        if (mode != MODE_PROXY && mode != MODE_OFFICIAL && mode != MODE_CUSTOM) mode = MODE_PROXY;
        Prefers.put(KEY_MODE, mode);
        CURSOR.set(0);
        TmdbApi.onConfigChanged();
        RankLatest.markConfigChanged();
    }

    /** 用户保存的站点记录；固定连接方式下地址字段不参与请求，但密钥仍从这里读。 */
    public static TmdbSite getSite() {
        TmdbSite site = cache;
        if (site != null) return site;
        synchronized (TmdbSetting.class) {
            if (cache != null) return cache;
            cache = migrate();
            return cache;
        }
    }

    public static void putSite(TmdbSite site) {
        TmdbSite value = site == null ? new TmdbSite() : site;
        Prefers.put(KEY_SITE, App.gson().toJson(value));
        cache = value;
        CURSOR.set(0);
        TmdbApi.onConfigChanged();
        RankLatest.markConfigChanged();
    }

    /**
     * 统一站点列表：API 层沿用它做请求入口，内容永远只有当前连接方式对应的那一条。
     */
    public static List<TmdbSite> getSites() {
        TmdbSite saved = getSite();
        TmdbSite active = new TmdbSite(getApiBase(), saved.getKey());
        return Collections.singletonList(active);
    }

    /** 当前连接方式实际使用的 API 基地址，全局唯一来源。 */
    public static String getApiBase() {
        if (getMode() == MODE_OFFICIAL) return OFFICIAL_API_URL;
        if (getMode() == MODE_CUSTOM) return getSite().base();
        return PROXY_API_URL;
    }

    public static String getApiKey() {
        return getSite().getKey();
    }

    /** 是否已有可用地址；自定义反代必须由用户填地址。 */
    public static boolean isConfigured() {
        String base = getApiBase();
        if (TextUtils.isEmpty(base)) return false;
        if (getMode() != MODE_CUSTOM) return true;
        return getSite().isValid();
    }

    /** 备份恢复 / 偏好整体替换后调用，重新解析当前站点。 */
    public static void reset() {
        cache = null;
        CURSOR.set(0);
    }

    public static int nextSiteIndex(int size) {
        if (size <= 1) return 0;
        return (CURSOR.getAndIncrement() & 0x7FFFFFFF) % size;
    }

    /** TMDB 刮削开关；连接方式只决定地址，不再受任何上级总开关控制。 */
    public static boolean isTmdbScrapeEnabled() {
        return Prefers.getBoolean(KEY_TMDB_SCRAPE, true);
    }

    public static void putTmdbScrape(boolean value) {
        Prefers.put(KEY_TMDB_SCRAPE, value);
        TmdbApi.onConfigChanged();
    }

    public static boolean isEnabled() {
        return isTmdbScrapeEnabled();
    }

    /** 刮削失败时是否用 Toast 提示。 */
    public static boolean isErrorToast() {
        return Prefers.getBoolean(KEY_ERROR_TOAST, false);
    }

    public static void putErrorToast(boolean value) {
        Prefers.put(KEY_ERROR_TOAST, value);
    }

    private static TmdbSite migrate() {
        String json = Prefers.getString(KEY_SITE, "");
        if (!TextUtils.isEmpty(json)) {
            try {
                TmdbSite site = App.gson().fromJson(json, TmdbSite.class);
                if (site != null) return site;
            } catch (Exception ignored) {
                // 配置损坏时继续尝试更早的版本。
            }
        }
        String sites = Prefers.getString(KEY_LEGACY_SITES, "");
        if (!TextUtils.isEmpty(sites)) {
            try {
                TmdbSite[] array = App.gson().fromJson(sites, TmdbSite[].class);
                if (array != null) for (TmdbSite site : array) if (site != null && site.isValid()) return site;
            } catch (Exception ignored) {
                // 继续尝试旧的单站点配置。
            }
        }
        String url = Prefers.getString(KEY_LEGACY_URL, "");
        String key = Prefers.getString(KEY_LEGACY_KEY, "");
        return new TmdbSite(url == null ? "" : url.trim(), key == null ? "" : key.trim());
    }
}

package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.Prefers;

public class Setting {

    /** 热词榜最小刷新间隔：15 分钟。榜单是低频数据，没必要每次进搜索页都打三次请求。 */

    private static final int MIN_SITE_MODE = 0;
    private static final int MAX_SITE_MODE = 1;
    private static final int MIN_SYNC_MODE = 0;
    private static final int MAX_SYNC_MODE = 2;

    public static String getSwitch(boolean value) {
        return ResUtil.getString(value ? R.string.setting_on : R.string.setting_off);
    }

    public static String getDoh() {
        return Prefers.getString("doh");
    }

    public static void putDoh(String doh) {
        Prefers.put("doh", doh);
    }

    public static String getUa() {
        return Prefers.getString("ua");
    }

    public static void putUa(String ua) {
        Prefers.put("ua", ua);
    }

    public static String getKeyword() {
        return Prefers.getString("keyword");
    }

    public static void putKeyword(String keyword) {
        Prefers.put("keyword", keyword);
    }

    /**
     * 旧版单列表热词缓存，仅供 mobile 变体使用。
     * TV 搜索页的热播剧集 / 热播电影已改为由 {@code RankLatest} 统一供数与缓存，
     * 不再把榜单写进偏好。
     */
    public static String getHot() {
        return Prefers.getString("hot");
    }

    public static void putHot(String hot) {
        Prefers.put("hot", hot);
    }

    public static int getThemeColor() {
        return Prefers.getInt("theme_color", -1);
    }

    public static void putThemeColor(int color) {
        Prefers.put("theme_color", color);
    }

    public static int getDynamicColor() {
        return Math.max(getThemeColor(), 0);
    }

    public static int getSiteMode() {
        return Math.clamp(Prefers.getInt("site_mode"), MIN_SITE_MODE, MAX_SITE_MODE);
    }

    public static void putSiteMode(int mode) {
        Prefers.put("site_mode", Math.clamp(mode, MIN_SITE_MODE, MAX_SITE_MODE));
    }

    public static int getSyncMode() {
        return Math.clamp(Prefers.getInt("sync_mode"), MIN_SYNC_MODE, MAX_SYNC_MODE);
    }

    public static void putSyncMode(int mode) {
        Prefers.put("sync_mode", Math.clamp(mode, MIN_SYNC_MODE, MAX_SYNC_MODE));
    }

    public static boolean isIncognito() {
        return Prefers.getBoolean("incognito");
    }

    public static void putIncognito(boolean incognito) {
        Prefers.put("incognito", incognito);
    }

    public static boolean isDiagAccepted() {
        return Prefers.getInt("diag_accepted", 0) == 1;
    }

    public static void putDiagAccepted(boolean accepted) {
        Prefers.put("diag_accepted", accepted ? 1 : 0);
    }

    public static boolean getUpdate() {
        return Prefers.getBoolean("update", true);
    }

    public static void putUpdate(boolean update) {
        Prefers.put("update", update);
    }

    public static boolean isAdblock() {
        return Prefers.getBoolean("adblock", true);
    }

    public static void putAdblock(boolean adblock) {
        Prefers.put("adblock", adblock);
    }

    public static boolean isZhuyin() {
        return Prefers.getBoolean("zhuyin");
    }

    public static void putZhuyin(boolean zhuyin) {
        Prefers.put("zhuyin", zhuyin);
    }
}

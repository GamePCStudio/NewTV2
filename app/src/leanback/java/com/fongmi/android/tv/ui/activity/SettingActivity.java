package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivitySettingBinding;
import com.fongmi.android.tv.databinding.ViewHomeToolbarBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.setting.NoticeSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.DohDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.RestoreDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.HistoryEpisode;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class SettingActivity extends BaseActivity implements ConfigListener, SiteListener, DohDialog.Listener {

    private ActivitySettingBinding mBinding;
    private ViewHomeToolbarBinding mToolbar;
    private Clock mClock;
    private Result mResult;
    private String[] size;
    private String[] searchLayouts;
    /** 焦点切页是否已解除锁定：仅真实用户操作后才允许，避免启动 / 恢复时误触发。 */
    private boolean mNavSwitchArmed;

    public static void start(Activity activity) {
        start(activity, null);
    }

    /** 从首页进设置时带上榜单结果，供「片库」导航项直接切页用。 */
    public static void start(Activity activity, Result result) {
        Intent intent = new Intent(activity, SettingActivity.class);
        if (result != null) intent.putExtra("result", result);
        activity.startActivity(intent);
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    @Override
    protected ViewBinding getBinding() {
        mBinding = ActivitySettingBinding.inflate(getLayoutInflater());
        mToolbar = mBinding.homeToolbar;
        return mBinding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mResult = getIntent().getParcelableExtra("result");
        setToolbar();
        mToolbar.settingEntry.requestFocus();
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.versionText.setText(BuildConfig.VERSION_NAME);
        setCacheText();
        setOtherText();
    }

    /**
     * 顶部导航栏（与首页 / 片库共用同一份布局）：两个导航胶囊 + 搜索圆钮 + 设置图标 + 时钟。
     *
     * <p>时间左侧的设置图标是当前页，点击只把设置内容滚回顶部；「首页」用 CLEAR_TOP 回首页栈，
     * 「片库」优先复用从首页带进来的榜单结果，没有结果（从片库进设置）时直接回退。
     * 设置页不提供站点选择，站源入口整块隐藏，不与设置内的站点配置入口重复。</p>
     */
    private void setToolbar() {
        mToolbar.navHome.setOnClickListener(v -> gotoHome());
        mToolbar.navVod.setOnClickListener(v -> gotoVod());
        mToolbar.settingEntry.setOnClickListener(v -> mBinding.scrollView.scrollTo(0, 0));
        mToolbar.brandEntry.setOnClickListener(v -> SearchActivity.start(this));
        mToolbar.siteEntry.setVisibility(View.GONE);
        // 导航 ↓ 直接进设置内容首行（工具栏布局里的 nextFocusDown 指向首页的 recycler，本页没有）。
        View[] navs = {mToolbar.navHome, mToolbar.navVod, mToolbar.settingEntry};
        for (View nav : navs) nav.setNextFocusDownId(R.id.vod);
        /*
         * 与首页 / 片库一致：焦点落到「首页」或「片库」就立即切到对应页面，
         * 不再等确认键，也不限制来向。本页焦点首帧落在设置图标上，不会被误切。
         */
        for (View nav : navs) nav.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus || isFinishing() || isDestroyed()) return;
            if (view != mToolbar.navHome && view != mToolbar.navVod) return;
            // 启动 / 恢复期的自动焦点不算用户操作：退回本页设置图标，不误切页面。
            if (!mNavSwitchArmed) {
                mToolbar.settingEntry.requestFocus();
                return;
            }
            if (view == mToolbar.navHome) gotoHome();
            else gotoVod();
        });
        mToolbar.brandEntry.setNextFocusDownId(R.id.vod);
        mToolbar.settingEntry.setSelected(true);
        // 站点配置错误 / 为空 / 未加载完成时都不显示「片库」胶囊，与首页同一口径。
        boolean vodReady = isVodReady();
        mToolbar.navVod.setVisibility(vodReady ? View.VISIBLE : View.GONE);
        mToolbar.navVod.setFocusable(vodReady);
        mToolbar.navVod.setFocusableInTouchMode(vodReady);
    }

    /** 与首页同一判定：结果无错误、分类非空、站点表已加载且 home 站点有效。 */
    private boolean isVodReady() {
        if (mResult == null || !mResult.getMsg().isEmpty() || mResult.getTypes().isEmpty()) return false;
        if (VodConfig.get().getSites().isEmpty()) return false;
        Site home = VodConfig.get().getHome();
        return home != null && !home.getKey().isEmpty();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        // 只有真实遥控器 / 触摸操作之后，焦点切页才生效；启动期的自动焦点还原不算。
        mNavSwitchArmed = true;
    }

    private void gotoHome() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void gotoVod() {
        if (mResult != null && !mResult.getTypes().isEmpty()) VodActivity.start(this, mResult);
        else finish(); // 从片库进设置：栈里已有片库，回退即可
    }

    private void setOtherText() {
        mBinding.dohText.setText(getDohList()[getDohIndex()]);
        mBinding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[PlayerSetting.getSize()]);
        searchLayouts = new String[]{getString(R.string.search_layout_horizontal), getString(R.string.search_layout_vertical)};
        mBinding.searchLayoutText.setText(searchLayouts[Setting.getSearchLayout()]);
        setTmdbText();
        setNoticeText();
    }

    /**
     * 影片刮削行右侧摘要：显示当前 TMDB 连接方式。
     *
     * <p>子页已经没有开关，这里不再用开/关，直接反映「默认反代 / TMDB官方直连 /
     * 自定义TMDB反代」三种状态，进来前就能看到实际走哪条线路。</p>
     */
    private void setTmdbText() {
        mBinding.tmdbText.setText(TmdbSetting.getModeLabelRes());
    }

    /** 提示过滤行摘要：只看总开关，关闭显示「关」。 */
    private void setNoticeText() {
        mBinding.noticeText.setText(Setting.getSwitch(NoticeSetting.isMasterEnabled()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mClock != null) mClock.stop();
        mClock = Clock.create().view(mToolbar.clock).format("HH:mm");
        mClock.start();
        // 从子设置页返回时开关状态可能已变，回来就刷新这两行摘要
        if (mBinding != null) {
            setTmdbText();
            setNoticeText();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mClock != null) mClock.stop();
    }

    private void setCacheText() {
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                mBinding.cacheText.setText(result);
            }
        });
    }

    @Override
    protected void initEvent() {
        mBinding.vod.setOnClickListener(this::onVod);
        mBinding.doh.setOnClickListener(this::setDoh);
        mBinding.size.setOnClickListener(this::setSize);
        mBinding.searchLayout.setOnClickListener(this::setSearchLayout);
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.player.setOnClickListener(this::onPlayer);
        // 模块管理入口按产品要求隐藏（布局中 visibility="gone"），ModuleActivity 保留
        mBinding.danmaku.setOnClickListener(this::onDanmaku);
        mBinding.tmdb.setOnClickListener(this::onTmdb);
        mBinding.notice.setOnClickListener(this::onNotice);
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.version.setOnClickListener(this::onVersion);
        mBinding.vod.setOnLongClickListener(this::onVodEdit);
        mBinding.vodHome.setOnClickListener(this::onVodHome);
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.vodHistory.setOnClickListener(this::onVodHistory);
    }

    @Override
    public void setConfig(Config config) {
        if (config.getUrl().startsWith("file")) PermissionUtil.requestFile(this, allGranted -> load(config));
        else load(config);
    }

    private void load(Config config) {
        VodConfig.load(config, getCallback());
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(getActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                setCacheText();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    private void onVod(View view) {
        ConfigDialog.create().vod().show(this);
    }

    private boolean onVodEdit(View view) {
        ConfigDialog.create().vod().edit().show(this);
        return true;
    }

    private void onVodHome(View view) {
        SiteDialog.create().action().show(this);
    }

    private void onVodHistory(View view) {
        HistoryDialog.create().vod().show(this);
    }

    private void onPlayer(View view) {
        SettingPlayerActivity.start(this);
    }

    private void onDanmaku(View view) {
        SettingDanmakuActivity.start(this);
    }

    private void onTmdb(View view) {
        SettingTmdbActivity.start(this);
    }

    private void onNotice(View view) {
        SettingNoticeActivity.start(this);
    }

    private void onVersion(View view) {
        Updater.get().start(this, true);
    }

    private void setIncognito(View view) {
        Setting.putIncognito(!Setting.isIncognito());
        mBinding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
    }

    private void setSize(View view) {
        int index = (PlayerSetting.getSize() + 1) % size.length;
        mBinding.sizeText.setText(size[index]);
        PlayerSetting.putSize(index);
        RefreshEvent.size();
    }

    private void setSearchLayout(View view) {
        int index = (Setting.getSearchLayout() + 1) % searchLayouts.length;
        Setting.putSearchLayout(index);
        mBinding.searchLayoutText.setText(searchLayouts[index]);
    }

    private void setDoh(View view) {
        DohDialog.create().index(getDohIndex()).show(this);
    }

    @Override
    public void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        Setting.putDoh(doh.toString());
        mBinding.dohText.setText(doh.getName());
    }

    private void onCache(View view) {
        FileUtil.clearCache(new Callback() {
            @Override
            public void success() {
                setCacheText();
            }
        });
    }

    /**
     * 备份与恢复的入口：先走与「选择文件」相同的系统授权链路，再执行实际动作。
     *
     * <p>Android 11 起写外置的 TV/backup 需要「所有文件访问权限」，没有权限就会 EPERM；
     * 旧版那种「静默退到应用内部目录」的做法，备份会随卸载一起消失。
     * 这里统一调用系统权限请求：用户拒绝就中止并提示，不再偷偷降级。</p>
     */
    private void onBackup(View view) {
        requestStorageAccess(this::doBackup);
    }

    private void onRestore(View view) {
        requestStorageAccess(this::doRestore);
    }

    private void requestStorageAccess(Runnable action) {
        PermissionUtil.requestFile(this, granted -> {
            if (granted) action.run();
            else Notify.show(R.string.backup_access_denied);
        });
    }

    private void doBackup() {
        BackupManager.backup(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.backup_success);
            }

            @Override
            public void error() {
                Notify.show(R.string.backup_fail);
            }
        });
    }

    private void doRestore() {
        RestoreDialog.create().callback(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
                // 备份可能整体替换了观看历史与站源，丢掉这两处进程内缓存，
                // 避免恢复后首页仍显示恢复前解析出来的内容。
                HistoryEpisode.reset();
                com.fongmi.android.tv.bean.Cache.clear();
                setOtherText();
                setCacheText();
                initConfig();
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        }).show(this);
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() != ConfigEvent.Type.COMMON) return;
        mBinding.vodUrl.setText(VodConfig.getDesc());
    }

}

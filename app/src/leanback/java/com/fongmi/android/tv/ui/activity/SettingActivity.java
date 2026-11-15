package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivitySettingBinding;
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
    private String[] size;
    /** 从系统授权页回来后要继续执行的动作（备份或恢复）。 */
    private Runnable pendingGrantedAction;
    /** 一次会话只拉一次授权页：用户不授权就别反复跳，直接走内部目录这条退路。 */
    private static boolean allFilesAsked;

    /**
     * 「所有文件访问权限」授权页没有返回值语义，只能等用户回来再自行判定，
     * 所以这里不用权限库的回调，统一在返回时继续原动作（没授权时备份会退到内部目录）。
     */
    private final ActivityResultLauncher<Intent> allFilesLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Runnable action = pendingGrantedAction;
                pendingGrantedAction = null;
                if (action != null) action.run();
            });

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingActivity.class));
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
        return mBinding = ActivitySettingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.vod.requestFocus();
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.versionText.setText(BuildConfig.VERSION_NAME);
        setCacheText();
        setOtherText();
    }

    private void setOtherText() {
        mBinding.dohText.setText(getDohList()[getDohIndex()]);
        mBinding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[PlayerSetting.getSize()]);
        setTmdbText();
        setNoticeText();
    }

    /**
     * 影片刮削行右侧摘要：按两个数据源的实际配置与启用状态显示，
     * 不再显示固定的站点条数。两者都可用显示「开」，只可用一个时标出是哪一个，
     * 总开关关闭或两者都没配好显示「关」。
     */
    private void setTmdbText() {
        boolean master = TmdbSetting.isMasterEnabled();
        boolean tmdb = master && TmdbSetting.isTmdbScrapeEnabled() && !TmdbSetting.getSites().isEmpty();
        boolean bridge = TmdbSetting.isBridgeEnabled();
        if (tmdb && bridge) mBinding.tmdbText.setText(Setting.getSwitch(true));
        else if (tmdb) mBinding.tmdbText.setText(R.string.scrape_state_tmdb_only);
        else if (bridge) mBinding.tmdbText.setText(R.string.scrape_state_bridge_only);
        else mBinding.tmdbText.setText(Setting.getSwitch(false));
    }

    /** 提示过滤行摘要：只看总开关，关闭显示「关」。 */
    private void setNoticeText() {
        mBinding.noticeText.setText(Setting.getSwitch(NoticeSetting.isMasterEnabled()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从子设置页返回时开关状态可能已变，回来就刷新这两行摘要
        if (mBinding != null) {
            setTmdbText();
            setNoticeText();
        }
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
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.player.setOnClickListener(this::onPlayer);
        // 模块管理入口已按产品要求隐藏（布局中 visibility="gone"），ModuleActivity 保留
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
     * 备份与恢复默认读写外置存储的 TV/backup 目录，Android 11 起没有「所有文件访问权限」
     * 会直接 EPERM：备份报失败，恢复连列表都是空的。所以两个入口都先走权限判断：
     * 已授权直接执行；系统有授权页就拉起来，回来后再继续；完全没入口时也不拦，
     * 备份模块会自动改写到应用内部目录，恢复则同时列两处。
     */
    private void onBackup(View view) {
        runWithFileAccess(this::doBackup);
    }

    private void onRestore(View view) {
        runWithFileAccess(this::doRestore);
    }

    private void runWithFileAccess(Runnable action) {
        if (allFilesAsked || !PermissionUtil.needsAllFiles(this)) {
            action.run();
            return;
        }
        Intent intent = PermissionUtil.allFilesIntent(this);
        if (intent == null) {
            action.run();
            return;
        }
        allFilesAsked = true;
        pendingGrantedAction = action;
        allFilesLauncher.launch(intent);
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

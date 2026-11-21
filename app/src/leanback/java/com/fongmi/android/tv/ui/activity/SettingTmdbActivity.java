package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingTmdbBinding;
import com.fongmi.android.tv.impl.TmdbListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.setting.TmdbSite;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.TmdbSiteDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** 设置 → 影片刮削设置：TMDB 开关、全局连接方式、站点与失败提示。 */
public class SettingTmdbActivity extends BaseActivity implements TmdbListener {

    private ActivitySettingTmdbBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingTmdbActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingTmdbBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        updateText();
        mBinding.tmdbScrape.requestFocus();
    }

    @Override
    protected void initEvent() {
        mBinding.tmdbScrape.setOnClickListener(this::toggleTmdbScrape);
        mBinding.tmdbToast.setOnClickListener(this::toggleErrorToast);
        mBinding.tmdbMode.setOnClickListener(view -> showModeDialog());
        mBinding.tmdbSite.setOnClickListener(view -> TmdbSiteDialog.show(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBinding != null) updateText();
    }

    private void toggleTmdbScrape(View view) {
        TmdbSetting.putTmdbScrape(!TmdbSetting.isTmdbScrapeEnabled());
        updateText();
    }

    private void toggleErrorToast(View view) {
        TmdbSetting.putErrorToast(!TmdbSetting.isErrorToast());
        updateText();
    }

    /** 选择全局连接方式；确认后写配置，选自定义反代时紧接着打开站点输入。 */
    private void showModeDialog() {
        CharSequence[] labels = {
                getString(R.string.tmdb_mode_proxy),
                getString(R.string.tmdb_mode_official),
                getString(R.string.tmdb_mode_custom)
        };
        final int[] selected = {TmdbSetting.getMode()};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tmdb_mode)
                .setSingleChoiceItems(labels, selected[0], (dialog, which) -> selected[0] = which)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    int mode = selected[0];
                    boolean wasCustom = TmdbSetting.getMode() == TmdbSetting.MODE_CUSTOM;
                    if (mode != TmdbSetting.getMode()) TmdbSetting.putMode(mode);
                    updateText();
                    if (mode == TmdbSetting.MODE_CUSTOM && !wasCustom) TmdbSiteDialog.show(this);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void updateText() {
        mBinding.tmdbScrapeText.setText(Setting.getSwitch(TmdbSetting.isTmdbScrapeEnabled()));
        mBinding.tmdbToastText.setText(Setting.getSwitch(TmdbSetting.isErrorToast()));
        mBinding.tmdbModeText.setText(modeLabel(TmdbSetting.getMode()));
        TmdbSite site = TmdbSetting.getSite();
        String base = TmdbSetting.getApiBase();
        String key = site.maskedKey();
        mBinding.tmdbSiteText.setText(TextUtils.isEmpty(key) ? base : base + " · " + key);
    }

    private String modeLabel(int mode) {
        if (mode == TmdbSetting.MODE_OFFICIAL) return getString(R.string.tmdb_mode_official);
        if (mode == TmdbSetting.MODE_CUSTOM) return getString(R.string.tmdb_mode_custom);
        return getString(R.string.tmdb_mode_proxy);
    }

    @Override
    public void onTmdbConfigChanged() {
        updateText();
    }
}

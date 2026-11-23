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
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.TmdbSiteDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.Consumer;

/**
 * 设置 → 影片刮削设置：全局连接方式与自定义反代地址。
 *
 * <p>页面不提供任何刮削开关：TMDB 是唯一数据源，只要连接方式可用
 * （{@link TmdbSetting#isConfigured()}）就一直参与刮削，其余配置项互不依赖。</p>
 *
 * <p>自定义反代地址行只在「自定义TMDB反代」下显示，且只回显用户自己填过的地址；
 * 默认反代与官方直连的内置地址不会出现在这一行，避免把内置地址暴露到界面上。</p>
 */
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
        mBinding.tmdbMode.requestFocus();
    }

    @Override
    protected void initEvent() {
        mBinding.tmdbMode.setOnClickListener(view -> showModeDialog());
        mBinding.tmdbSite.setOnClickListener(view -> TmdbSiteDialog.show(this));
        // 图片分辨率：选中即生效；海报 / 横版剧照决定之后新刮削下载的档位。
        mBinding.tmdbPoster.setOnClickListener(view -> showImageSizeDialog(
                R.string.tmdb_image_poster, TmdbSetting.POSTER_SIZES, TmdbSetting.getPosterSize(), true, TmdbSetting::putPosterSize));
        mBinding.tmdbBackdrop.setOnClickListener(view -> showImageSizeDialog(
                R.string.tmdb_image_backdrop, TmdbSetting.BACKDROP_SIZES, TmdbSetting.getBackdropSize(), true, TmdbSetting::putBackdropSize));
        mBinding.tmdbStill.setOnClickListener(view -> showImageSizeDialog(
                R.string.tmdb_image_still, TmdbSetting.STILL_SIZES, TmdbSetting.getStillSize(), false, TmdbSetting::putStillSize));
        mBinding.tmdbProfile.setOnClickListener(view -> showImageSizeDialog(
                R.string.tmdb_image_profile, TmdbSetting.PROFILE_SIZES, TmdbSetting.getProfileSize(), false, TmdbSetting::putProfileSize));
    }

    /**
     * 图片分辨率选择：单击选项即生效并关闭弹窗（与连接方式的选择方式一致）。
     *
     * <p>{@code highest} 只影响「当前值非法 / 首次安装」时弹窗里预选哪一档，
     * 与实际写入无关 —— 写入的永远是用户点中的那一项。</p>
     */
    private void showImageSizeDialog(int titleRes, String[] sizes, String current, boolean highest, Consumer<String> save) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(sizes, TmdbSetting.indexOf(sizes, current, highest), (dialog, which) -> {
                    dialog.dismiss();
                    save.accept(sizes[which]);
                    updateText();
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBinding != null) updateText();
    }

    /**
     * 离开页面时收口。
     *
     * <p>选了「自定义TMDB反代」却没填地址（或填了非法地址）属于无效配置，
     * 会让所有 TMDB 请求直接失败；退出页面时回落默认反代，保证下次进来仍是可用状态。
     * 只在真正退出（{@code isFinishing()}）时处理，按 Home 键离开不算。</p>
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) return;
        if (TmdbSetting.getMode() == TmdbSetting.MODE_CUSTOM && !TmdbSetting.isConfigured()) {
            TmdbSetting.putMode(TmdbSetting.MODE_PROXY);
        }
    }

    /**
     * 选择全局连接方式：单击选项即生效并关闭弹窗，不再有「确定 / 取消」。
     *
     * <p>选中「自定义TMDB反代」且还没有可用地址时，紧跟着打开地址输入框，
     * 避免用户选完却不知道还要填地址。</p>
     */
    private void showModeDialog() {
        CharSequence[] labels = {
                getString(R.string.tmdb_mode_proxy),
                getString(R.string.tmdb_mode_official),
                getString(R.string.tmdb_mode_custom)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tmdb_mode)
                .setSingleChoiceItems(labels, TmdbSetting.getMode(), (dialog, which) -> {
                    dialog.dismiss();
                    applyMode(which);
                })
                .show();
    }

    private void applyMode(int mode) {
        if (mode != TmdbSetting.getMode()) TmdbSetting.putMode(mode);
        updateText();
        // 地址输入等弹窗真正关掉再开，避免两个 Dialog 同帧切换抢焦点。
        if (mode == TmdbSetting.MODE_CUSTOM && !TmdbSetting.isConfigured()) {
            mBinding.tmdbMode.post(() -> {
                if (!isFinishing() && !isDestroyed()) TmdbSiteDialog.show(this);
            });
        }
    }

    private void updateText() {
        int mode = TmdbSetting.getMode();
        mBinding.tmdbModeText.setText(getString(TmdbSetting.getModeLabelRes()));
        // 图片分辨率：摘要直接显示档位名（w342 / w780 …）。
        mBinding.tmdbPosterText.setText(TmdbSetting.getPosterSize());
        mBinding.tmdbBackdropText.setText(TmdbSetting.getBackdropSize());
        mBinding.tmdbStillText.setText(TmdbSetting.getStillSize());
        mBinding.tmdbProfileText.setText(TmdbSetting.getProfileSize());
        boolean custom = mode == TmdbSetting.MODE_CUSTOM;
        // 非自定义方式下整行收起：内置地址不落到界面上。
        // 先归位焦点再隐藏，避免正在这一行时 setVisibility(GONE) 把焦点丢掉。
        if (!custom && mBinding.tmdbSite.hasFocus()) mBinding.tmdbMode.requestFocus();
        mBinding.tmdbSite.setVisibility(custom ? View.VISIBLE : View.GONE);
        if (custom) {
            String url = TmdbSetting.getSite().getUrl();
            mBinding.tmdbSiteText.setText(TextUtils.isEmpty(url) ? getString(R.string.tmdb_site_unset) : url);
        } else {
            mBinding.tmdbSiteText.setText("");
        }
    }

    @Override
    public void onTmdbConfigChanged() {
        updateText();
    }
}

package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingTmdbBinding;
import com.fongmi.android.tv.impl.TmdbListener;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.TmdbKeyDialog;
import com.fongmi.android.tv.ui.dialog.TmdbSiteDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.Consumer;

/**
 * 设置 → 影片刮削设置：全局连接方式、独立 API 密钥与自定义反代地址。
 *
 * <p>页面不提供任何刮削开关：TMDB 是唯一数据源，只要连接方式可用
 * （{@link TmdbSetting#isConfigured()}）就一直参与刮削，其余配置项互不依赖。</p>
 *
 * <p>API 密钥是独立设置项：所有连接方式下都显示、都生效，不随反代地址行的显隐变化；
 * 切换连接方式不会清空密钥。Vercel 与 Cloudflare 部署地址始终保留，选中对应连接方式后启用。</p>
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
        // 密钥独立编辑：不依赖反代地址是否填写，所有连接方式共用这一份配置。
        mBinding.tmdbApiKey.setOnClickListener(view -> TmdbKeyDialog.show(this));
        mBinding.tmdbSite.setOnClickListener(view -> TmdbSiteDialog.show(this));
        mBinding.tmdbVercel.setOnClickListener(view -> TmdbSiteDialog.show(this, TmdbSetting.MODE_VERCEL));
        mBinding.tmdbCloudflare.setOnClickListener(view -> TmdbSiteDialog.show(this, TmdbSetting.MODE_CLOUDFLARE));
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
     * <p>选了自建反代却没填地址（或填了非法地址）属于无效配置，
     * 会让所有 TMDB 请求直接失败；退出页面时回落默认反代，保证下次进来仍是可用状态。
     * 只在真正退出（{@code isFinishing()}）时处理，按 Home 键离开不算。</p>
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) return;
        if (TmdbSetting.getMode() != TmdbSetting.MODE_PROXY
                && TmdbSetting.getMode() != TmdbSetting.MODE_OFFICIAL
                && !TmdbSetting.isConfigured()) {
            TmdbSetting.putMode(TmdbSetting.MODE_PROXY);
        }
    }

    /**
     * 选择全局连接方式：单击选项即生效并关闭弹窗，不再有「确定 / 取消」。
     *
     * <p>选中自建反代且还没有可用地址时，紧跟着打开对应的地址输入框，
     * 避免用户选完却不知道还要填地址。</p>
     */
    private void showModeDialog() {
        CharSequence[] labels = {
                getString(R.string.tmdb_mode_proxy),
                getString(R.string.tmdb_mode_official),
                getString(R.string.tmdb_mode_custom),
                getString(R.string.tmdb_mode_vercel),
                getString(R.string.tmdb_mode_cloudflare)
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
        // 自建反代尚未填地址时，紧跟着打开对应输入框，避免用户选完却不知道还要填地址。
        if (mode != TmdbSetting.MODE_PROXY && mode != TmdbSetting.MODE_OFFICIAL && !TmdbSetting.isConfigured()) {
            mBinding.tmdbMode.post(() -> {
                if (!isFinishing() && !isDestroyed()) TmdbSiteDialog.show(this, mode);
            });
        }
    }

    private void updateText() {
        int mode = TmdbSetting.getMode();
        mBinding.tmdbModeText.setText(getString(TmdbSetting.getModeLabelRes()));
        // API 密钥：始终显示；有值时只露头尾，未设置时显示占位文案。
        String masked = TmdbSetting.getApiKeyMasked();
        mBinding.tmdbApiKeyText.setText(TextUtils.isEmpty(masked) ? getString(R.string.tmdb_site_unset) : masked);
        // 图片分辨率：摘要直接显示档位名（w342 / w780 …）。
        mBinding.tmdbPosterText.setText(TmdbSetting.getPosterSize());
        mBinding.tmdbBackdropText.setText(TmdbSetting.getBackdropSize());
        mBinding.tmdbStillText.setText(TmdbSetting.getStillSize());
        mBinding.tmdbProfileText.setText(TmdbSetting.getProfileSize());
        boolean custom = mode == TmdbSetting.MODE_CUSTOM;
        // 非自定义方式下收起通用地址行；Vercel 与 Cloudflare 的独立配置始终保留。
        // 先归位焦点再隐藏，避免正在这一行时 setVisibility(GONE) 把焦点丢掉。
        if (!custom && mBinding.tmdbSite.hasFocus()) mBinding.tmdbMode.requestFocus();
        mBinding.tmdbSite.setVisibility(custom ? View.VISIBLE : View.GONE);
        if (custom) {
            String url = TmdbSetting.getSite().getUrl();
            mBinding.tmdbSiteText.setText(TextUtils.isEmpty(url) ? getString(R.string.tmdb_site_unset) : url);
        } else {
            mBinding.tmdbSiteText.setText("");
        }
        setSiteText(mBinding.tmdbVercelText, TmdbSetting.MODE_VERCEL);
        setSiteText(mBinding.tmdbCloudflareText, TmdbSetting.MODE_CLOUDFLARE);
    }

    private void setSiteText(TextView view, int mode) {
        String url = TmdbSetting.getSiteForMode(mode).getUrl();
        view.setText(TextUtils.isEmpty(url) ? getString(R.string.tmdb_site_unset) : url);
    }

    @Override
    public void onTmdbConfigChanged() {
        updateText();
    }
}

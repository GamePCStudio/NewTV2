package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogTmdbSiteBinding;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.TmdbListener;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.setting.TmdbSite;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * 编辑全局 TMDB API 站点：地址（仅自定义反代可改）+ 可选密钥。
 *
 * <p>默认反代与官方 API 的地址由连接方式决定，这里只让用户补充密钥；
 * 自定义反代复用同一组输入项填写地址与密钥，密钥允许留空。</p>
 */
public class TmdbSiteDialog extends BaseAlertDialog {

    private DialogTmdbSiteBinding binding;

    public static void show(FragmentActivity activity) {
        new TmdbSiteDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogTmdbSiteBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.tmdb_site_custom).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        boolean custom = TmdbSetting.getMode() == TmdbSetting.MODE_CUSTOM;
        TmdbSite site = TmdbSetting.getSite();
        binding.url.setText(custom ? site.getUrl() : TmdbSetting.getApiBase());
        binding.url.setEnabled(custom);
        binding.url.setFocusable(custom);
        binding.url.setFocusableInTouchMode(custom);
        binding.key.setText(site.getKey());
        binding.key.setSelection(site.getKey().length());
        binding.phone.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(this::onSave);
        binding.negative.setOnClickListener(view -> dismiss());
        binding.phone.setOnClickListener(view -> LocalConnectDialog.show(requireActivity(), "tmdb"));
    }

    /** 手机端同步过来的地址与密钥；固定连接方式下地址会被忽略。 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.TMDB || !isAdded()) return;
        if (TmdbSetting.getMode() == TmdbSetting.MODE_CUSTOM) binding.url.setText(event.text());
        binding.key.setText(event.name());
        binding.key.setSelection(binding.key.getText().length());
    }

    private void onSave(View view) {
        TmdbSite site = new TmdbSite(binding.url.getText().toString(), binding.key.getText().toString());
        if (TmdbSetting.getMode() == TmdbSetting.MODE_CUSTOM && !site.isValid()) {
            toast(R.string.tmdb_site_invalid);
            return;
        }
        // 自定义反代由服务方注入密钥，这里不强制填写。
        TmdbSetting.putSite(site);
        apply();
        dismiss();
    }

    private void apply() {
        if (requireActivity() instanceof TmdbListener) {
            ((TmdbListener) requireActivity()).onTmdbConfigChanged();
        }
    }

    private void toast(int resId, Object... args) {
        Toast.makeText(requireContext(), getString(resId, args), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.6f);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }
}

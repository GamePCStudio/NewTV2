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
 * 编辑「自定义TMDB反代地址」与可选密钥。
 *
 * <p>只服务 {@link TmdbSetting#MODE_CUSTOM}：入口是设置页里那一行，非自定义方式下该行整体收起，
 * 所以这里<b>永远不回显内置地址</b>（默认反代 / 官方直连都由代码内置），
 * 输入框只承载用户自己填过的地址，空就是空，避免把内置地址暴露到界面。</p>
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
        // 地址只回显用户自己的配置；内置地址一律不落到这条输入框里。
        TmdbSite site = TmdbSetting.getSite();
        binding.url.setText(site.getUrl());
        binding.url.setSelection(binding.url.getText().length());
        binding.key.setText(site.getKey());
        binding.key.setSelection(site.getKey().length());
        binding.phone.setVisibility(View.VISIBLE);
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(this::onSave);
        binding.negative.setOnClickListener(view -> dismiss());
        binding.phone.setOnClickListener(view -> LocalConnectDialog.show(requireActivity(), "tmdb"));
    }

    /** 手机端同步过来的自定义反代地址与密钥。 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.TMDB || !isAdded()) return;
        binding.url.setText(event.text());
        binding.key.setText(event.name());
        binding.key.setSelection(binding.key.getText().length());
    }

    private void onSave(View view) {
        TmdbSite site = new TmdbSite(binding.url.getText().toString(), binding.key.getText().toString());
        if (!site.isValid()) {
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

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
 * 编辑「自定义TMDB反代地址」。密钥已拆分为设置页里的独立项，这里不再承载密钥。
 *
 * <p>只服务 {@link TmdbSetting#MODE_CUSTOM}：入口是设置页里那一行，非自定义方式下该行整体收起，
 * 所以这里<b>永远不回显内置地址</b>（默认反代 / 官方直连都由代码内置），
 * 输入框只承载用户自己填过的地址，空就是空，避免把内置地址暴露到界面。</p>
 *
 * <p>保存地址时保留已有的独立密钥；手机端一次提交地址 + 密钥时，密钥先暂存，
 * 随「确定」一起写入独立配置，仍遵循「用户确认后才落盘」的约定。</p>
 */
public class TmdbSiteDialog extends BaseAlertDialog {

    private DialogTmdbSiteBinding binding;
    /** 手机端同步过来的密钥，等用户按「确定」时与地址一起保存。 */
    private String pendingKey = "";

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
        binding.phone.setVisibility(View.VISIBLE);
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(this::onSave);
        binding.negative.setOnClickListener(view -> dismiss());
        binding.phone.setOnClickListener(view -> LocalConnectDialog.show(requireActivity(), "tmdb"));
    }

    /** 手机端同步过来的自定义反代地址与密钥：地址进输入框，密钥暂存到确定时保存。 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.TMDB || !isAdded()) return;
        if (!TextUtils.isEmpty(event.text())) {
            binding.url.setText(event.text());
            binding.url.setSelection(binding.url.getText().length());
        }
        if (!TextUtils.isEmpty(event.name())) pendingKey = event.name();
    }

    private void onSave(View view) {
        String url = binding.url.getText().toString();
        // 手机端可能只下发了密钥（地址留空）：保存密钥即可，不拿地址校验拦住这次操作。
        if (TextUtils.isEmpty(url.trim())) {
            if (TextUtils.isEmpty(pendingKey)) {
                toast(R.string.tmdb_site_invalid);
                return;
            }
            TmdbSetting.putApiKey(pendingKey);
            apply();
            dismiss();
            return;
        }
        // 密钥不在本弹窗编辑：保存地址时沿用独立配置里的密钥，避免把密钥清掉。
        TmdbSite site = new TmdbSite(url, TmdbSetting.getApiKey());
        if (!site.isValid()) {
            toast(R.string.tmdb_site_invalid);
            return;
        }
        // 手机端一次提交的密钥随确认一起落盘；未提交则保持原值不变。
        if (!TextUtils.isEmpty(pendingKey)) TmdbSetting.putApiKey(pendingKey);
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

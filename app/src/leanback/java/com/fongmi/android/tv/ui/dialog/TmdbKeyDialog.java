package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogTmdbKeyBinding;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.TmdbListener;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * 编辑全局 TMDB API 密钥。
 *
 * <p>密钥独立于连接方式与自定义反代地址：默认反代、官方直连、自定义反代三种方式下
 * 都使用这同一份配置，切换连接方式不会清空或失效。这里允许保存空值，
 * 用于清除密钥（例如反代服务自行注入密钥、不需要客户端携带）。</p>
 */
public class TmdbKeyDialog extends BaseAlertDialog {

    private DialogTmdbKeyBinding binding;

    public static void show(FragmentActivity activity) {
        new TmdbKeyDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogTmdbKeyBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.tmdb_api_key).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        String key = TmdbSetting.getApiKey();
        binding.key.setText(key);
        binding.key.setSelection(key.length());
        binding.phone.setVisibility(View.VISIBLE);
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(this::onSave);
        binding.negative.setOnClickListener(view -> dismiss());
        binding.phone.setOnClickListener(view -> LocalConnectDialog.show(requireActivity(), "tmdb"));
    }

    /** 手机端同步过来的密钥：只填输入框，仍由用户按「确定」保存。 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.TMDB || !isAdded()) return;
        binding.key.setText(event.name());
        binding.key.setSelection(binding.key.getText().length());
    }

    private void onSave(View view) {
        TmdbSetting.putApiKey(binding.key.getText().toString());
        if (requireActivity() instanceof TmdbListener) {
            ((TmdbListener) requireActivity()).onTmdbConfigChanged();
        }
        dismiss();
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

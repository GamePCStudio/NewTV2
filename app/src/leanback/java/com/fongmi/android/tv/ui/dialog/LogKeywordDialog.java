package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSingleInputBinding;
import com.fongmi.android.tv.event.ServerEvent;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * 刮削日志的片名关键字输入框：单行，留空表示取消该筛选条件。
 * 遥控器直接输入，或点「手机输入」扫码在手机上打字。
 */
public class LogKeywordDialog extends BaseAlertDialog {

    private static final String ARG_KEYWORD = "keyword";

    private DialogSingleInputBinding binding;

    public interface Listener {
        void onLogKeyword(String keyword);
    }

    public static void show(FragmentActivity activity, String keyword) {
        LogKeywordDialog dialog = new LogKeywordDialog();
        Bundle args = new Bundle();
        args.putString(ARG_KEYWORD, keyword == null ? "" : keyword);
        dialog.setArguments(args);
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSingleInputBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.text.setHint(R.string.tmdb_log_keyword_hint);
        binding.text.setSingleLine(true);
        binding.text.setInputType(InputType.TYPE_CLASS_TEXT);
        String text = getArguments() == null ? "" : getArguments().getString(ARG_KEYWORD, "");
        binding.text.setText(TextUtils.isEmpty(text) ? "" : text);
        binding.text.setSelection(binding.text.getText().length());
    }

    @Override
    protected void initEvent() {
        binding.phone.setOnClickListener(view -> LocalConnectDialog.show(requireActivity(), "settings"));
        binding.positive.setOnClickListener(this::onPositive);
        binding.negative.setOnClickListener(this::onNegative);
        binding.text.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) binding.positive.performClick();
            return true;
        });
    }

    private void onPositive(View view) {
        ((Listener) requireActivity()).onLogKeyword(binding.text.getText().toString());
        dismiss();
    }

    private void onNegative(View view) {
        dismiss();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.SETTING) return;
        binding.text.setText(event.text());
        binding.positive.performClick();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.55f);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }
}

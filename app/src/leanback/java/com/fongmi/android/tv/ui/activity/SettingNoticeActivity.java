package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingNoticeBinding;
import com.fongmi.android.tv.setting.NoticeSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.NoticeKeywordDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ToastGuard;

/**
 * 设置 → 提示（Toast）过滤设置：总开关 + 关键词表 + 恢复默认，三项。
 *
 * <p>早先按类别拆开的「隐藏弹幕搜索失败提示 / 过滤站源播报 / 连弹幕播报一起过滤」三个子开关已移除，
 * 它们原本承担的过滤能力全部并入关键词表（默认词就是这几个类别里实测出现的核心词），
 * 由用户统一维护。</p>
 */
public class SettingNoticeActivity extends BaseActivity implements NoticeKeywordDialog.Listener {

    private ActivitySettingNoticeBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingNoticeActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingNoticeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.noticeMaster.requestFocus();
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.noticeMaster.setOnClickListener(this::toggleMaster);
        mBinding.noticeKeywords.setOnClickListener(view -> NoticeKeywordDialog.show(this));
        mBinding.noticeKeywordsReset.setOnClickListener(this::resetKeywords);
    }

    private void toggleMaster(View view) {
        NoticeSetting.putMaster(!NoticeSetting.isMasterEnabled());
        // 启动时若总开关是关的，框架层 hook 未安装；重新打开后补装一次
        if (NoticeSetting.isMasterEnabled()) ToastGuard.install();
        refresh();
    }

    private void resetKeywords(View view) {
        NoticeSetting.resetKeywords();
        Notify.show(R.string.notice_reset_done);
        refresh();
    }

    /** 总开关关闭时收起关键词两项；设置值每次都重读，改完立即生效。 */
    private void refresh() {
        boolean master = NoticeSetting.isMasterEnabled();
        mBinding.noticeMasterText.setText(Setting.getSwitch(master));
        mBinding.noticeKeywordsText.setText(getString(R.string.notice_keywords_count, NoticeSetting.getKeywords().size()));
        int visibility = master ? View.VISIBLE : View.GONE;
        mBinding.noticeKeywords.setVisibility(visibility);
        mBinding.noticeKeywordsReset.setVisibility(visibility);
    }

    @Override
    public void onNoticeKeywords(String value) {
        NoticeSetting.putKeywordsRaw(value);
        refresh();
    }
}

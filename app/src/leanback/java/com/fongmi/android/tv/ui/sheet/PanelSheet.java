package com.fongmi.android.tv.ui.sheet;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import com.fongmi.android.tv.databinding.ViewControlVodActionBinding;
import com.fongmi.android.tv.databinding.ViewSheetPanelBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 「播放设置」浮层的内容。
 *
 * <p>常显五个入口，统一「图标 + 文案」形态，**自下而上**依次为：
 * 信息 / 音轨 / 视轨 / 字幕 / 画面比例。浮层从控制条按钮向上展开，
 * 最底下一行离按钮最近，所以初始焦点落在「信息」上。
 * 面板转发到控制栏同名按钮，轨道浮层、对话框与状态同步沿用同一实现。
 *
 * <p>五个入口常显、不随轨道可用性隐藏，作为控制栏常用项的补充入口。
 *
 * <p>焦点链在每次打开时按可见行运行时重串：左右各行自封口，上下按行序走位，
 * 首行上 / 末行下自封口，保证焦点不会逃出浮层，也不会落到已隐藏的行上。
 */
public final class PanelSheet {

    /**
     * 面板内容宽度：固定一档。
     *
     * <p>不再用 {@code wrap_content} 实测：标题行是 {@code 0dp + weight}、行又是 {@code match_parent}
     * 且带 {@code minWidth}，在 AT_MOST 下量出来的宽度会随文本与可见行数抖动（时而比行宽窄、
     * 时而把整行挤到溢出），表现为浮层忽宽忽窄。菜单内容固定，直接给定值最稳。
     */
    private static final int WIDTH_DP = 260;

    public interface OnAction {
        void onAction();
    }

    private final ViewControlVodActionBinding action;
    private final ViewSheetPanelBinding binding;

    public PanelSheet(Activity activity, ViewControlVodActionBinding action) {
        this.action = action;
        this.binding = ViewSheetPanelBinding.inflate(activity.getLayoutInflater());
    }

    public View getView() {
        return binding.getRoot();
    }

    /** 内容宽度（浮层左右内边距由 VideoSheet 负责，调用方另加 12dp×2 的宿主内边距）。 */
    public int measureWidth(int maxWidth) {
        return Math.min(ResUtil.dp2px(WIDTH_DP), Math.max(0, maxWidth));
    }

    public void bind(OnAction onAction) {
        syncContent();
        relink();
        // 字幕 / 音轨 / 视轨：先收面板再弹轨道浮层，否则浮层会叠在面板上面
        binding.panelText.setOnClickListener(view -> forward(action.text, onAction));
        binding.panelAudio.setOnClickListener(view -> forward(action.audio, onAction));
        binding.panelVideo.setOnClickListener(view -> forward(action.video, onAction));
        // 信息：原地开关，不收起面板；图标与选中态立即回读
        binding.panelDebug.setOnClickListener(view -> {
            if (action.debug != null) action.debug.performClick();
            syncDebug();
        });
        // 画面比例：不关面板，原地循环切换，便于连续对比
        binding.panelScale.setOnClickListener(view -> {
            if (action.scale != null) action.scale.performClick();
            syncContent();
        });
        binding.panelDecoder.setOnClickListener(view -> forward(action.decoder, onAction));
        binding.panelEdition.setOnClickListener(view -> forward(action.edition, onAction));
        binding.panelChapter.setOnClickListener(view -> forward(action.chapter, onAction));
        binding.panelDiscMenu.setOnClickListener(view -> forward(action.discMenu, onAction));
        binding.panelPlayer.setOnClickListener(view -> forward(action.player, onAction));
        binding.panelReset.setOnClickListener(view -> forward(action.reset, onAction));
        binding.panelReplay.setOnClickListener(view -> forward(action.replay, onAction));
        binding.panelRepeat.setOnClickListener(view -> forward(action.repeat, onAction));
    }

    /** 其余入口先收面板再触发，否则浮层会盖在随后弹出的对话框上面。 */
    private void forward(View target, OnAction onAction) {
        if (onAction != null) onAction.onAction();
        if (target != null) target.performClick();
    }

    /** 打开 / 切换后同步：画面比例文案与信息开关态。 */
    private void syncContent() {
        if (action.scale != null) binding.panelScale.setText(action.scale.getText());
        syncDebug();
    }

    /** 信息开着时整行 activated：图标随 selector_sheet_icon_tint 常亮品牌橙。 */
    private void syncDebug() {
        binding.panelDebug.setActivated(action.debug != null && action.debug.isSelected());
    }

    /** 运行时统一重串焦点链：左右自封口、上下按行序、首末行上/下自封口。 */
    private void relink() {
        List<View> rows = visibleChildren(binding.sheetRows);
        for (int i = 0; i < rows.size(); i++) {
            View view = rows.get(i);
            view.setNextFocusLeftId(view.getId());
            view.setNextFocusRightId(view.getId());
            view.setNextFocusUpId(i == 0 ? view.getId() : rows.get(i - 1).getId());
            view.setNextFocusDownId(i == rows.size() - 1 ? view.getId() : rows.get(i + 1).getId());
        }
    }

    /**
     * 浮层打开后把焦点交给**最底下一行**。
     *
     * <p>浮层是贴着控制条按钮向上展开的，最底下一行离手指/焦点来源最近；菜单也按
     * 「自下而上」排序（信息 → 音轨 → 视轨 → 字幕 → 画面比例），所以初始选中框落在底部。
     */
    public void focus() {
        relink();
        View last = lastVisible(binding.sheetRows);
        if (last != null) last.requestFocus();
    }

    private View lastVisible(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) return child;
        }
        return null;
    }

    private List<View> visibleChildren(ViewGroup group) {
        List<View> views = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) views.add(child);
        }
        return views;
    }
}

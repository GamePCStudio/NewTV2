package com.fongmi.android.tv.ui.custom;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.utils.ResUtil;

/**
 * 列表顶部内边距的自适应。
 *
 * <p>主视觉存在时保持布局里的 8dp：主视觉的画框是通栏的，要贴到屏幕最顶部、
 * 让顶栏浮在画面之上。</p>
 *
 * <p>主视觉收起后，第一个栏目会直接顶到列表最上方而被 64dp 的顶栏压住 ——
 * 这时把顶部内边距补到<b>实测的工具栏底边</b>，首行刚好落在顶栏下方。
 * 用真实像素而不是写死 dp，1080p / 4K / 不同 density 下都不会错位。</p>
 */
public final class HeroInset {

    private static final int BASE_DP = 8;

    private HeroInset() {
    }

    public static void apply(RecyclerView recycler, View toolbar, boolean hero) {
        if (recycler == null || toolbar == null) return;
        int base = ResUtil.dp2px(BASE_DP);
        if (!hero && toolbar.getBottom() <= 0) {
            if (toolbar.isLaidOut()) {
                // 已经完成布局却拿不到底边（异常场景）：退回基础内边距，不留下死循环。
                setPadding(recycler, base);
            } else {
                // 首帧还没布局：等这一帧结束再按实测高度补。
                toolbar.post(() -> apply(recycler, toolbar, false));
            }
            return;
        }
        setPadding(recycler, hero ? base : toolbar.getBottom() + base);
    }

    private static void setPadding(RecyclerView recycler, int top) {
        if (recycler.getPaddingTop() == top) return;
        recycler.setPadding(recycler.getPaddingLeft(), top, recycler.getPaddingRight(), recycler.getPaddingBottom());
    }
}

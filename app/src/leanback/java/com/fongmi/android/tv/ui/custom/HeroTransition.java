package com.fongmi.android.tv.ui.custom;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 主视觉增删后的过渡。
 *
 * <p>主视觉是列表第 0 行：它出现 / 收起时，下方所有栏目会整体位移一段（一个主视觉的高度）。
 * 布局本身是瞬时的，直接用会「跳一下」。这里给列表一次很短的淡入，
 * 把重排的瞬间盖过去，观感上没有留白也没有突兀的位移。</p>
 *
 * <p>只改 alpha、不改尺寸与顺序：不参与焦点、不影响列表测量，也不会和卡片自己的
 * 聚焦缩放动画抢属性。</p>
 */
public final class HeroTransition {

    private static final long DURATION = 180L;
    private static final float FROM = 0.82f;

    private HeroTransition() {
    }

    public static void fade(View content) {
        if (content == null) return;
        content.animate().cancel();
        content.setAlpha(FROM);
        content.animate()
                .alpha(1f)
                .setDuration(DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }
}

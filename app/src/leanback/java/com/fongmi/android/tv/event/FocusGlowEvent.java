package com.fongmi.android.tv.event;

import org.greenrobot.eventbus.EventBus;

/**
 * 焦点卡主色上报事件：由首页卡片 Holder / Presenter 在聚焦时发出，
 * 驱动首页整页背景（PageGlowComposeView）的漫反射柔光与焦点卡局部提亮。
 *
 * <p>只用于视觉联动，不承载业务数据。{@code cx}/{@code cy} 是焦点卡中心在整屏中的
 * 归一化坐标（0..1），{@code cardW}/{@code cardH} 是卡片（含海报与片名区）的归一化宽高，
 * 让背景光圆心跟随焦点位置、局部提亮贴合卡片轮廓扩散。</p>
 */
public class FocusGlowEvent {

    /** 用显式 kind 区分事件，不再靠 color 值猜：主色可能是 Color.TRANSPARENT（也是 0）。 */
    private static final int KIND_SHOW = 0;
    private static final int KIND_CENTER = 1;
    private static final int KIND_CLEAR = 2;

    private final int kind;
    private final int color;
    private final float cx;
    private final float cy;
    private final float cardW;
    private final float cardH;

    private FocusGlowEvent(int kind, int color, float cx, float cy, float cardW, float cardH) {
        this.kind = kind;
        this.color = color;
        this.cx = cx;
        this.cy = cy;
        this.cardW = cardW;
        this.cardH = cardH;
    }

    /** 聚焦到某张卡：上报主色、焦点卡屏幕中心，以及卡片的归一化宽高。 */
    public static void show(int color, float cx, float cy, float cardW, float cardH) {
        EventBus.getDefault().post(new FocusGlowEvent(KIND_SHOW, color, cx, cy, cardW, cardH));
    }

    /**
     * 只更新局部提亮的位置与范围，颜色保持不变。
     *
     * <p>焦点换到新卡时提色是异步的（Palette 在后台线程跑），如果等它回来再更新圆心，
     * 这段时间里光圈会停在上一张卡的位置；这里先把几何推到新卡上。</p>
     */
    public static void center(float cx, float cy, float cardW, float cardH) {
        EventBus.getDefault().post(new FocusGlowEvent(KIND_CENTER, 0, cx, cy, cardW, cardH));
    }

    /** 焦点离开内容区（回到导航 / 主视觉）：熄灭整页背景光与局部提亮。 */
    public static void clear() {
        EventBus.getDefault().post(new FocusGlowEvent(KIND_CLEAR, 0, 0f, 0f, 0f, 0f));
    }

    public boolean isClear() {
        return kind == KIND_CLEAR;
    }

    public boolean isCenterOnly() {
        return kind == KIND_CENTER;
    }

    public int getColor() {
        return color;
    }

    public float getCx() {
        return cx;
    }

    public float getCy() {
        return cy;
    }

    public float getCardW() {
        return cardW;
    }

    public float getCardH() {
        return cardH;
    }
}

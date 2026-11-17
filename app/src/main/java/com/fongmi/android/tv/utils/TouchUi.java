package com.fongmi.android.tv.utils;

import android.content.Context;
import android.view.View;

import com.fongmi.android.tv.R;

/**
 * 交互模式判定：Pad 变体用触控，leanback / mobile 保持遥控器焦点模式。
 *
 * <p>共用 Java（main + leanback 源集）通过 {@code R.bool.pad_touch} 分流，
 * 避免为 Pad 复制 Activity；所有焦点高亮、焦点框绘制与方向键走位逻辑
 * 在触控模式下都要短路，触控反馈改由按压态与 {@code PadTouchHelper} 提供。
 */
public final class TouchUi {

    private TouchUi() {
    }

    public static boolean isTouch(Context context) {
        return context != null && context.getResources().getBoolean(R.bool.pad_touch);
    }

    public static boolean isTouch(View view) {
        return view != null && isTouch(view.getContext());
    }

    /** 输入控件仍需要可聚焦才能弹出键盘；其余控件在触控模式下不参与焦点。 */
    public static boolean isInput(View view) {
        if (view == null) return false;
        String name = view.getClass().getSimpleName().toLowerCase();
        return name.contains("edittext") || name.contains("autocompletetextview") || name.contains("searchview")
                || name.contains("textinput");
    }
}

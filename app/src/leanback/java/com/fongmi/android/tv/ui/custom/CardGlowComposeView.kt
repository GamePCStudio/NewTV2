package com.fongmi.android.tv.ui.custom

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.View
import androidx.annotation.ColorInt
import com.fongmi.android.tv.event.FocusGlowEvent

/**
 * 卡片提色 / 几何上报工具。
 *
 * <p><b>历史命名保留</b>：本类早期曾持有每张海报卡一个 ComposeView 绘制叶子，但那层光
 * 铺在不透明海报之下、视觉上被整块盖住，真正可见的整页漫反射与焦点卡局部提亮全部由
 * [PageGlowComposeView] 提供。性能整改后已移除每卡的 ComposeView（合成层、重组与渐变
 * 绘制随卡片数线性增长的纯浪费），这里只保留两个静态上报入口，避免调用点大范围改动。</p>
 *
 * <p><b>定向分发</b>：上报时带上卡片所在 Activity 的身份标识，事件订阅者据此只让来源
 * 页面自己响应，页面栈里其它存活 Activity 不再被唤醒做几何重算。</p>
 */
object CardGlowComposeView {

    /**
     * 焦点卡提色成功后，把主色、卡片中心与卡片尺寸（均按整屏归一化）上报给整页背景光。
     *
     * <p>灰片 / 占位矢量图 / 无位图时 HeroGlowColor 返回 Color.TRANSPARENT，那只是
     * 「这一张提不出主色」，不是熄灭指令。直接跳过上报，让整页光沿用上一张卡的颜色继续亮着；
     * 行末常见这类素材，以前它被当成 clear 事件关掉整页光。</p>
     *
     * <p>颜色事件里的几何已被页面忽略（几何只由焦点实时校正负责），因此这里不再用
     * width<=0 拦整条上报：卡片刚复用、尚未测量完时提色回调照样要把颜色送达。</p>
     */
    @JvmStatic
    fun reportPageGlow(card: View, @ColorInt color: Int) {
        if (color == Color.TRANSPARENT) return
        val b = normalizedBounds(card)
        FocusGlowEvent.show(brightenColor(color), b[0], b[1], b[2], b[3], pageHash(card))
    }

    /**
     * 只把局部提亮的几何推到这张卡上（颜色不变）。
     *
     * <p>焦点换到新卡时提色是异步的，等回调再更新圆心会让光圈停在上一张卡的位置；
     * 聚焦瞬间先调这个方法，光就立刻跟着走，颜色稍后补齐。</p>
     */
    @JvmStatic
    fun reportPageGlowCenter(card: View) {
        if (card.width <= 0 || card.height <= 0) return
        val b = normalizedBounds(card)
        FocusGlowEvent.center(b[0], b[1], b[2], b[3], pageHash(card))
    }

    /** [cx, cy, w, h]，均按整屏宽高归一化。 */
    private fun normalizedBounds(card: View): FloatArray {
        val dm = card.resources.displayMetrics
        val screenW = dm.widthPixels.toFloat()
        val screenH = dm.heightPixels.toFloat()
        val location = IntArray(2)
        card.getLocationOnScreen(location)
        if (screenW <= 0f || screenH <= 0f) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(
            (location[0] + card.width / 2f) / screenW,
            (location[1] + card.height / 2f) / screenH,
            card.width / screenW,
            card.height / screenH
        )
    }

    /** 从 View 的 Context 链上找到宿主 Activity；找不到时返回 0（表示不限定页面）。 */
    private fun pageHash(view: View): Int {
        var context: Context? = view.context
        while (context is ContextWrapper) {
            if (context is Activity) return System.identityHashCode(context)
            context = context.baseContext
        }
        return 0
    }

    /** 保持色相、饱和度抬到 0.67、明度拉满；透明色原样返回。 */
    private fun brightenColor(@ColorInt color: Int): Int {
        if (color == Color.TRANSPARENT) return color
        val hsv = floatArrayOf(0f, 0f, 0f)
        Color.colorToHSV(color, hsv)
        hsv[1] = maxOf(hsv[1], 0.67f)
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }
}

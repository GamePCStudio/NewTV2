package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.ComposeView

/**
 * 网格卡片的提色绘制叶子。
 *
 * <p><b>分工</b>：本层铺满海报画框、插在海报之下（index 0），画一团提色径向渐变；
 * 海报完整不透明、铺满画框，所以这层在视觉上被整块盖住——它是提色链路与卡片内层的
 * 兜底绘制，真正可见的「白框外侧一圈提亮」由首页背景层的焦点卡局部提亮负责
 * （[PageGlowComposeView]）：那里画在内容之下，海报自然遮挡中间，只在白框外侧露出，
 * 不受卡片容器约束，且单个径向渐变没有分层叠加的色阶断层。</p>
 *
 * <p>绘制用 Compose Canvas 路径，不依赖 VIDAA 不支持的 clipToOutline；
 * 不参与焦点、导航与命中测试，不改变卡片尺寸与报告高度。</p>
 *
 * <p><b>仅聚焦显示</b>：[setFocused] 切换显隐、300ms 快起慢收补间；颜色由调用方提色后
 * 经 [setGlowColor] 传入。</p>
 */
class CardGlowComposeView(context: Context) {

    /** 供宿主 addView 的容器：游离态测量给 0，attach 后才创建 Compose 内容。 */
    val view: View = GlowHost(context)

    /** 最近一次主色 RGB；淡出时保留它只降 alpha，避免经过暗色。 */
    private var currentColor by mutableStateOf(Color.TRANSPARENT)
    private var shown by mutableStateOf(false)

    /** 设置主色；透明表示淡出不画。按 CapyPlayer 口径把色归一化到高饱和亮色。 */
    fun setGlowColor(@ColorInt color: Int) {
        if (color == Color.TRANSPARENT) {
            shown = false
        } else {
            currentColor = brighten(color)
            shown = true
        }
    }

    /** 保持色相，把饱和度抬到 0.67、明度拉满——提色原色偏暗时也能透出可见的彩色光。 */
    private fun brighten(@ColorInt color: Int): Int = brightenColor(color)

    /** 焦点态切换：只改 alpha，颜色由提色链路异步补齐。 */
    fun setFocused(focused: Boolean) {
        shown = focused && currentColor != Color.TRANSPARENT
    }

    /** 卡片复用 / 解绑时清空颜色与显隐，避免下一部片闪出上一部的光。 */
    fun reset() {
        currentColor = Color.TRANSPARENT
        shown = false
    }

    /**
     * Leanback 的 GridLayoutManager 会对尚未 attach 的 scrap child 做预测量；
     * 此时 ComposeView.onMeasure 会因拿不到 windowRecomposer 直接抛异常。
     * 这里在游离态把自身测成 0，attach 之后才 setContent 并正常参与测量。
     */
    private inner class GlowHost(context: Context) : FrameLayout(context) {

        private var created = false

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (created) return
            created = true
            val composeView = ComposeView(context).apply {
                isFocusable = false
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setContent {
                    val target = androidx.compose.ui.graphics.Color(currentColor).copy(alpha = if (shown) 1f else 0f)
                    val animated by animateColorAsState(
                        targetValue = target,
                        animationSpec = tween(durationMillis = GLOW_DURATION, easing = GLOW_EASING),
                        label = "cardGlowColor"
                    )
                    CardGlowCanvas(animated)
                }
            }
            // 光层铺满海报画框，参与正常布局；白框是 poster 的 foreground，不受影响。
            addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (!isAttachedToWindow) {
                setMeasuredDimension(0, 0)
                return
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    companion object {
        const val GLOW_DURATION = 300
        val GLOW_EASING = CubicBezierEasing(0f, 0f, 0.2f, 1f)
        /** 海报内容四周内缩，给外发光留出的空间（dp）。Java 侧 image inset 必须用同值。 */
        const val CARD_INSET_DP = 4
        /** 光块外轮廓圆角 = 海报圆角 16dp + inset 4dp。 */
        const val GLOW_CORNER_DP = 20
        const val PEAK_ALPHA = 0x78 / 255f

        /**
         * 卡片内光层的布局参数：铺满海报画框，不向外溢出。
         *
         * <p>海报铺满画框且不透明，这层绘制会被整块盖住——白框外侧那圈可见的提亮
         * 改由首页背景层统一提供（[PageGlowComposeView] 的焦点卡局部提亮）：
         * 那里画在内容之下，海报自然遮挡中间，只在白框外侧露出，且不受卡片容器约束、
         * 单个径向渐变没有分层叠加的色阶断层。这里保持满铺是为了不重复叠光、也不留锯齿源。</p>
         */
        @JvmStatic
        fun createLayoutParams(view: View): FrameLayout.LayoutParams {
            return FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        /**
         * 给海报 View 四周加 margin 留出光道。海报本身保持完整不透明、圆角不变，
         * 光层（本类，index 0 铺满画框）从这圈缝隙透出，即 CapyPlayer 的衬底露边观感。
         * 只在构造绑定时调用一次，不在测量阶段改 LayoutParams。
         */
        @JvmStatic
        fun insetImage(image: View) {
            val lp = image.layoutParams as? FrameLayout.LayoutParams ?: return
            val px = Math.round(CARD_INSET_DP * image.resources.displayMetrics.density)
            lp.setMargins(px, px, px, px)
            image.layoutParams = lp
        }

        /**
         * 焦点卡提色成功后，把主色、卡片中心与卡片尺寸（均按整屏归一化）上报给首页
         * 整页背景光。圆心随焦点卡移动，卡片尺寸驱动背景层上的局部提亮范围。只做视觉联动。
         */
        @JvmStatic
        fun reportPageGlow(card: View, @ColorInt color: Int) {
            /*
             * 灰片 / 占位矢量图 / 无位图时 HeroGlowColor 返回 Color.TRANSPARENT，那只是
             * 「这一张提不出主色」，不是熄灭指令。直接跳过上报，让整页光沿用上一张卡的
             * 颜色继续亮着；行末常见这类素材，以前它被当成 clear 事件关掉整页光。
             *
             * 颜色事件里的几何已被 HomeActivity 忽略（几何只由焦点实时校正负责），
             * 因此这里不再用 width<=0 拦整条上报：卡片刚复用、尚未测量完时提色回调
             * 照样要把颜色送达。与卡片内光层同一口径做提亮：Palette 取到的常是偏暗的
             * swatch，直接上报会让暗色海报周围的背景光几乎看不见。
             */
            if (color == Color.TRANSPARENT) return
            val b = normalizedBounds(card)
            com.fongmi.android.tv.event.FocusGlowEvent.show(brightenColor(color), b[0], b[1], b[2], b[3])
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
            com.fongmi.android.tv.event.FocusGlowEvent.center(b[0], b[1], b[2], b[3])
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
}

@Composable
private fun CardGlowCanvas(color: androidx.compose.ui.graphics.Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (color.alpha <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        val w = size.width
        val h = size.height
        /*
         * 与整页背景光（PageGlowComposeView）同一套画法：单个径向渐变铺满整层，
         * alpha 由内向外平滑衰减到 0，边缘不留硬边。
         *
         * 本层铺满海报画框、位于海报之下；海报完整不透明，会把这层整块盖住。
         * 白框外侧那一圈可见的提亮由背景层的焦点卡局部提亮负责（见 PageGlowComposeView），
         * 这里不做向外溢出，避免两处叠光、也避免多层叠加产生的色阶断层。
         */
        val cx = w / 2f
        val cy = h / 2f
        val rx = w / 2f
        val peak = GLOW_PEAK_ALPHA * color.alpha
        scale(1f, h / w, pivot = Offset(cx, cy)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to color.copy(alpha = peak),
                        0.78f to color.copy(alpha = peak * 0.90f),
                        0.92f to color.copy(alpha = peak * 0.24f),
                        1f to color.copy(alpha = 0f)
                    ),
                    center = Offset(cx, cy),
                    radius = rx
                ),
                radius = rx,
                center = Offset(cx, cy)
            )
        }
    }
}

/** 光晕靠近海报处的峰值不透明度（圆心被海报盖住，实际可见的是边缘一带）。 */
private const val GLOW_PEAK_ALPHA = 0.55f

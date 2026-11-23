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
import androidx.compose.ui.platform.ComposeView

/**
 * 首页主视觉的主色漫反射「绘制叶子」试点。
 *
 * <p>只做一件事：把调用方提取的 RGB 主色画成一团低透明度径向柔光，颜色切换用
 * 300ms 补间淡入淡出。不参与焦点、不参与导航、不参与命中测试；不改变
 * [HomeHeroBackdropView] 的报告高度与焦点链。</p>
 *
 * <p>Compose 1.12 的 ComposeView 是 final，且游离态被 measure 会因拿不到
 * windowRecomposer 抛异常——故用持有式 GlowHost：游离态测成 0，attach 后才创建 Compose 内容。</p>
 */
class HeroGlowComposeView(context: Context) {

    /** 供宿主 addView 的容器：游离态测量给 0，attach 后才创建 Compose 内容。 */
    val view: View = GlowHost(context)

    /** 最近一次主色 RGB；淡出时保留它，只把 alpha 动画到 0，避免经过暗色。 */
    private var currentColor by mutableStateOf(Color.TRANSPARENT)
    /** 是否显示：淡出时保留 currentColor，仅降 alpha。 */
    private var shown by mutableStateOf(false)

    /** 设置主色；透明表示淡出不画。调用方在异步回调里自行判 generation。 */
    fun setGlowColor(@ColorInt color: Int) {
        if (color == Color.TRANSPARENT) {
            shown = false
        } else {
            currentColor = color
            shown = true
        }
    }

    /**
     * Leanback 预测量未 attach 的 scrap child 时，ComposeView.onMeasure 会抛
     * "not attached to a window"。游离态测成 0，attach 后才 setContent。
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
                        label = "heroGlowColor"
                    )
                    HeroGlowCanvas(animated)
                }
            }
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
        // DecelerateInterpolator(1.8f) 的 Compose 等价：快起慢收。
        val GLOW_EASING = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    }
}

private const val GLOW_PEAK_ALPHA = 0x40 / 255f

@Composable
private fun HeroGlowCanvas(color: androidx.compose.ui.graphics.Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (color.alpha <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        // 圆心落在绘制区底边中点，半径按宽高取大者；渐变整团留在绘制区内。
        val radius = maxOf(size.width * 0.55f, size.height * 0.42f)
        val center = color.copy(alpha = GLOW_PEAK_ALPHA * color.alpha)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(center, color.copy(alpha = 0f)),
                center = Offset(size.width / 2f, size.height),
                radius = radius
            )
        )
    }
}

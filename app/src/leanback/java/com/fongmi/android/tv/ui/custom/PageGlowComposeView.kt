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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp

/**
 * 整页背景漫反射：铺满根布局、画在内容之下的全屏主色柔光 + 焦点卡局部提亮。
 *
 * <p>挂在 activity_home / activity_vod / activity_home_more / activity_collect 根 FrameLayout
 * 的最底层（index 0），聚焦卡的主色经事件总线上报后，在这里画成一条低透明度的径向渐变，
 * 圆心随焦点卡在整屏中的位置移动。只做绘制：不参与焦点、导航与命中测试。</p>
 *
 * <p><b>性能口径</b>（整改后）：
 * <ul>
 *   <li>几何与动画色值全部在 <b>draw phase</b> 读取：滚动 / 补间每帧只触发重绘，不再重组；</li>
 *   <li>32 档 stops 与 Brush 按几何 + RGB 缓存，动画 alpha 走 drawRect 的 alpha 参数，
 *       补间帧不重建 shader；低配设备降到 8 档；</li>
 *   <li>两次 drawRect 都裁剪到真正有像素的区域：光只画径向有效矩形与 scrim 下沿之下，
 *       scrim 只画渐入窄带，避免全屏 overdraw；</li>
 *   <li>setColor / setCenter / setScrimEdge 均做等值判断，坐标未变不写 state。</li>
 * </ul></p>
 *
 * <p>用持有式 GlowHost 规避 ComposeView 游离态被 measure 时拿不到 windowRecomposer 的崩溃，
 * 并在 detach 时销毁 composition 释放 SlotTable。</p>
 */
class PageGlowComposeView(context: Context) {

    val view: View = GlowHost(context)

    private var currentColor by mutableStateOf(Color.TRANSPARENT)
    /** 圆心归一化坐标 0..1，默认整屏中心偏下。 */
    private var centerX by mutableStateOf(0.5f)
    private var centerY by mutableStateOf(0.55f)
    /**
     * 主视觉羽化 scrim 的绘制下沿在整屏中的归一化 y（随列表滚动实时变化）。
     * scrim 末端是纯不透明 #101114，会挡住本层的光；光必须严格从这条边之下才渐入，
     * 交界处光强为 0、与 scrim 纯色同值，才不会出现水平断层。
     */
    private var edgeRatio by mutableStateOf(1f)
    /**
     * 是否绘制主视觉 scrim 的遮挡渐变。
     *
     * <p><b>没有主视觉的页面必须关掉</b>：这层用的是 verticalGradient(startY = edgeY)，
     * 默认 Clamp 平铺意味着 startY 以上全部取第一个颜色 —— 不透明的页面底色。
     * 若页面不设置 edge（停在默认 1f），整屏会被它盖死，下面的光完全看不见。</p>
     */
    private var scrimShown by mutableStateOf(true)
    /** 焦点卡（含海报与片名区）的归一化宽高：驱动本层上的局部提亮范围。 */
    private var cardWRatio by mutableStateOf(0f)
    private var cardHRatio by mutableStateOf(0f)
    private var shown by mutableStateOf(false)

    /** 渐变 shader 缓存：几何或 RGB 不变时跨帧复用（动画 alpha 不进 key）。 */
    private var glowKey: GlowKey? = null
    private var glowBrush: Brush? = null
    private var scrimKey: ScrimKey? = null
    private var scrimBrush: Brush? = null

    /**
     * 主色 + 圆心 + 卡片尺寸由焦点卡上报；透明表示淡出。
     *
     * @param cardW 海报画框宽度 / 屏幕宽度（可略越界，见 [setCenter]）
     * @param cardH 海报画框高度 / 屏幕高度
     */
    fun setGlow(@ColorInt color: Int, cx: Float, cy: Float, cardW: Float = 0f, cardH: Float = 0f) {
        setColor(color)
        // 尺寸无效说明卡片还没布局完，此时只换色、不动位置，否则光晕会被甩到屏幕左上角。
        if (cardW > 0f && cardH > 0f) setCenter(cx, cy, cardW, cardH)
    }

    /**
     * 只换主色、完全不动几何。
     *
     * <p>提色（Palette）在后台线程跑，快速连按方向键时，上一张卡的提色回调会晚于焦点
     * 切换到达；若让它带着旧坐标更新圆心，光圈就会被拽回旧卡甚至行首。几何一律由
     * [setCenter] / HomeActivity 的实时焦点校正负责，颜色事件只负责颜色。</p>
     *
     * <p><b>透明色必须忽略</b>：灰片 / 无位图时 HeroGlowColor 会返回 Color.TRANSPARENT，
     * 那只是「这一张提不出主色」，不是「熄灭光效」。熄灭只由 [clear] 负责。</p>
     */
    fun setColor(@ColorInt color: Int) {
        if (color == Color.TRANSPARENT) return
        if (currentColor == color && shown) return
        currentColor = color
        shown = true
    }

    /**
     * 只更新几何、不动颜色。列表滚动时焦点卡在移动、主色却没变，
     * 必须在滚动过程中持续校正圆心，否则局部提亮会滞留在滚动前那一行。
     *
     * <p>坐标允许略微越界（-0.5..1.5）：快速连按方向键时焦点会先落到**还没滚进视口**
     * 的卡上，若把圆心硬钳到 0/1，光晕就会死死贴在屏幕边缘。</p>
     */
    fun setCenter(cx: Float, cy: Float, cardW: Float, cardH: Float) {
        val nx = cx.coerceIn(-0.5f, 1.5f)
        val ny = cy.coerceIn(-0.5f, 1.5f)
        val nw = cardW.coerceIn(0f, 1f)
        val nh = cardH.coerceIn(0f, 1f)
        /*
         * 重新回到海报区时先复用上一张的主色把光点亮：焦点从站点导航挪回海报，
         * 几何事件会先到、提色要等后台 Palette 回来，中间这段如果不点亮，观感就是
         * 「回到海报还黑着，随后突然亮一下」。熄灭仍然只由 clear() 负责。
         */
        if (nw > 0f && nh > 0f && currentColor != Color.TRANSPARENT) shown = true
        // 归一化坐标差值小于约 2px（1080p）时视为同一位置，不写 state、不触发重绘。
        if (kotlin.math.abs(nx - centerX) < EPS && kotlin.math.abs(ny - centerY) < EPS
            && kotlin.math.abs(nw - cardWRatio) < EPS && kotlin.math.abs(nh - cardHRatio) < EPS) return
        centerX = nx
        centerY = ny
        cardWRatio = nw
        cardHRatio = nh
    }

    /** 由 HomeActivity 在主视觉行滚动 / 焦点变化时实时传入 scrim 绘制下沿（0..1）。 */
    fun setScrimEdge(edge: Float) {
        val value = edge.coerceIn(0f, 1f)
        if (kotlin.math.abs(value - edgeRatio) < EPS) return
        edgeRatio = value
    }

    /** 页面没有主视觉时关掉遮挡渐变（详见字段说明）。 */
    fun setScrimEnabled(enabled: Boolean) {
        if (scrimShown == enabled) return
        scrimShown = enabled
    }

    fun clear() {
        if (!shown) return
        shown = false
    }

    private fun DrawScope.drawGlow(color: ComposeColor) {
        // 全部几何 state 在 draw phase 读取：滚动 / 补间变化只 invalidate draw，不重组。
        val cx = centerX
        val cy = centerY
        val scrimEdge = edgeRatio
        val scrim = scrimShown
        val cardW = cardWRatio
        val cardH = cardHRatio
        val a = color.alpha
        if (a <= 0f || size.width <= 0f || size.height <= 0f) return
        val w = size.width
        val h = size.height
        val radius = kotlin.math.hypot(w, h) * 0.72f
        val center = Offset(w * cx, h * cy)

        val key = GlowKey(w, h, cx, cy, cardW, cardH, color.copy(alpha = 1f).toArgb(), GLOW_STOPS, HALO_STOPS)
        val brush = glowBrush?.takeIf { glowKey == key }
            ?: buildGlowBrush(key, color, w, h, radius, center, cardW, cardH, CARD_SPREAD_DP.dp.toPx())
                .also { glowBrush = it; glowKey = key }

        /*
         * 光只画「径向渐变有效矩形」与屏幕的交集；渐变在半径外本来就被 Clamp 成全透明，
         * 裁掉这部分不改变任何一个像素，却省下大片填充率。
         * 有主视觉 scrim 时再裁掉 edge 之上：那一段原先用不透明底色盖住，不画光等价，
         * 顺带让 scrim 只需画渐入窄带，不必全屏铺一层底色。
         */
        val left = (center.x - radius).coerceIn(0f, w)
        val right = (center.x + radius).coerceIn(0f, w)
        val bottom = (center.y + radius).coerceIn(0f, h)
        val top = if (scrim) maxOf((center.y - radius).coerceIn(0f, h), h * scrimEdge)
        else (center.y - radius).coerceIn(0f, h)
        if (right > left && bottom > top) {
            clipRect(left, top, right, bottom) {
                // 动画 alpha 走绘制参数，缓存的 brush 不含 alpha，补间帧不重建 shader。
                drawRect(brush = brush, alpha = a)
            }
        }

        if (!scrim) return
        val edgeY = (h * scrimEdge).coerceIn(0f, h)
        val fadeBand = h * 0.10f
        val scrimBottom = (edgeY + fadeBand).coerceIn(0f, h)
        if (scrimBottom <= edgeY) return
        val sKey = ScrimKey(h, edgeY)
        val sBrush = scrimBrush?.takeIf { scrimKey == sKey } ?: Brush.verticalGradient(
            colors = listOf(PAGE_BG, ComposeColor.Transparent),
            startY = edgeY,
            endY = edgeY + fadeBand
        ).also { scrimBrush = it; scrimKey = sKey }
        clipRect(0f, edgeY, w, scrimBottom) {
            drawRect(brush = sBrush)
        }
    }

    /** 构造 radialGradient 的 32 档 stops（低配 8 档）；曲线与原实现完全一致。 */
    private fun buildGlowBrush(
        key: GlowKey,
        color: ComposeColor,
        w: Float,
        h: Float,
        radius: Float,
        center: Offset,
        cardW: Float,
        cardH: Float,
        spread: Float
    ): Brush {
        val halfW = w * cardW / 2f
        val halfH = h * cardH / 2f
        val halfDiag = kotlin.math.hypot(halfW, halfH)
        val hasCard = cardW > 0f && cardH > 0f && halfDiag > 0f
        val flat = halfDiag * CARD_FLAT_SCALE
        val zero = halfDiag + spread
        val tFlat = if (hasCard) (flat / radius).coerceIn(0f, 0.80f) else 0f
        val tEnd = if (hasCard) (zero / radius).coerceIn(tFlat + 0.02f, 0.92f) else 0f
        val span = tEnd - tFlat

        val raw = ArrayList<Float>(key.stops + key.halo + 1)
        for (i in 0 until key.stops) raw.add(i / (key.stops - 1f))
        if (hasCard && span > 0f) {
            for (i in 0..key.halo) raw.add((tFlat + span * i / key.halo).coerceIn(0f, 1f))
        }
        raw.sort()
        val ts = ArrayList<Float>(raw.size)
        for (v in raw) if (ts.isEmpty() || v - ts[ts.size - 1] > 1e-4f) ts.add(v)

        val stops: Array<Pair<Float, ComposeColor>> = Array(ts.size) { i ->
            val t = ts[i]
            val base = PAGE_PEAK_ALPHA * (1f - 3f * t * t + 2f * t * t * t)
            val u = if (hasCard && span > 0f) ((t - tFlat) / span).coerceIn(0f, 1f) else 1f
            val halo = CARD_EXTRA_ALPHA * (1f - u * u * (3f - 2f * u))
            // 不含动画 alpha：由 drawRect(alpha = ...) 统一施加，shader 才能跨帧复用。
            t to color.copy(alpha = (base + halo).coerceIn(0f, 1f))
        }
        return Brush.radialGradient(colorStops = stops, center = center, radius = radius)
    }

    private inner class GlowHost(context: Context) : FrameLayout(context) {

        private var created = false
        private var composeView: ComposeView? = null

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (created) return
            created = true
            val cv = ComposeView(context).apply {
                isFocusable = false
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setContent {
                    // 只有主色 / 显隐变化才重组一次；动画色值在 Canvas 的 draw 阶段取，不触发重组。
                    val target = ComposeColor(currentColor).copy(alpha = if (shown) 1f else 0f)
                    val animated = animateColorAsState(
                        targetValue = target,
                        animationSpec = tween(durationMillis = GLOW_DURATION, easing = GLOW_EASING),
                        label = "pageGlowColor"
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) { drawGlow(animated.value) }
                }
            }
            composeView = cv
            addView(cv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        override fun onDetachedFromWindow() {
            disposeComposition()
            super.onDetachedFromWindow()
        }

        fun disposeComposition() {
            composeView?.disposeComposition()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (!isAttachedToWindow) {
                setMeasuredDimension(0, 0)
                return
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private data class GlowKey(
        val w: Float, val h: Float, val cx: Float, val cy: Float,
        val cardW: Float, val cardH: Float, val rgb: Int, val stops: Int, val halo: Int
    )

    private data class ScrimKey(val h: Float, val edgeY: Float)

    companion object {
        private const val EPS = 0.002f
        val GLOW_DURATION: Int = if (GlowTier.isLowEnd()) 200 else 400
        val GLOW_EASING = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    }
}

private const val PAGE_PEAK_ALPHA = 0x2A / 255f
private const val CARD_EXTRA_ALPHA = 0.128f
private const val CARD_FLAT_SCALE = 0.6f
private const val CARD_SPREAD_DP = 30
/** 整屏半径上的均匀采样档数；低配降到 8 档，观感接近而分配 / 绘制成本减半。 */
private val GLOW_STOPS = if (GlowTier.isLowEnd()) 8 else 20
private val HALO_STOPS = if (GlowTier.isLowEnd()) 4 else 12
private val PAGE_BG = ComposeColor(0xFF101114.toInt())

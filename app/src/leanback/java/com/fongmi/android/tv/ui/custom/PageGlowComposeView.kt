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
import androidx.compose.ui.unit.dp

/**
 * 首页整页背景漫反射：铺满根布局、画在内容之下的全屏主色柔光。
 *
 * <p>与卡片框内的 [CardGlowComposeView] 不同，本层挂在 activity_home 根 FrameLayout 的
 * 最底层（index 0），绘制范围是整屏。聚焦卡的主色经事件总线上报后，在这里画成一团
 * 低透明度的径向渐变，圆心随焦点卡在整屏中的位置移动，让整页背景随焦点产生漫反射。</p>
 *
 * <p>只做绘制：不参与焦点、导航与命中测试，不影响 recycler 的焦点链与数据。
 * 用持有式 GlowHost 规避 ComposeView 游离态被 measure 时拿不到 windowRecomposer 的崩溃。</p>
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
     * <p><b>没有主视觉的页面必须关掉</b>：这层用的是 {@code verticalGradient(startY = edgeY)}，
     * 默认 Clamp 平铺意味着 {@code startY} 以上全部取第一个颜色 —— 不透明的页面底色。
     * 若页面不设置 edge（停在默认 1f），整屏会被它盖死，下面的光完全看不见。</p>
     */
    private var scrimShown by mutableStateOf(true)
    /** 焦点卡（含海报与片名区）的归一化宽高：驱动本层上的局部提亮范围。 */
    private var cardWRatio by mutableStateOf(0f)
    private var cardHRatio by mutableStateOf(0f)
    private var shown by mutableStateOf(false)

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
     * 那只是「这一张提不出主色」，不是「熄灭光效」。以前透明色会把 shown 置 false，
     * 于是恰好命中这种素材的卡片（行末常见）整页光直接消失。熄灭只由 [clear] 负责。</p>
     */
    fun setColor(@ColorInt color: Int) {
        if (color == Color.TRANSPARENT) return
        currentColor = color
        shown = true
    }

    /**
     * 只更新几何、不动颜色。列表滚动时焦点卡在移动、主色却没变，
     * 必须在滚动过程中持续校正圆心，否则局部提亮会滞留在滚动前那一行。
     *
     * <p>坐标允许略微越界（-0.5..1.5）：快速连按方向键时焦点会先落到**还没滚进视口**
     * 的卡上，若把圆心硬钳到 0/1，光晕就会死死贴在屏幕边缘；保留真实坐标时，
     * 圆心的越界量会把光推出屏外，只在边上露出一小片，这才是卡片尚未进入视野的样子。</p>
     */
    fun setCenter(cx: Float, cy: Float, cardW: Float, cardH: Float) {
        centerX = cx.coerceIn(-0.5f, 1.5f)
        centerY = cy.coerceIn(-0.5f, 1.5f)
        cardWRatio = cardW.coerceIn(0f, 1f)
        cardHRatio = cardH.coerceIn(0f, 1f)
        /*
         * 重新回到海报区时先复用上一张的主色把光点亮：焦点从站点导航挪回海报，
         * 几何事件会先到、提色要等后台 Palette 回来，中间这段（实测 100~500ms）
         * 如果不点亮，观感就是「回到海报还黑着，随后突然亮一下」。
         * 熄灭仍然只由 clear() 负责，这里仅在已有可用主色时恢复显示。
         */
        if (cardW > 0f && cardH > 0f && currentColor != Color.TRANSPARENT) shown = true
    }

    /** 由 HomeActivity 在主视觉行滚动 / 焦点变化时实时传入 scrim 绘制下沿（0..1）。 */
    fun setScrimEdge(edge: Float) {
        edgeRatio = edge.coerceIn(0f, 1f)
    }

    /**
     * 页面没有主视觉时关掉遮挡渐变。
     *
     * <p>不能靠把 edge 设成 1f「让它落到屏幕外」—— 那层渐变默认 Clamp，startY 以上一律取
     * 首个颜色（不透明页面底色），整屏会被盖住。必须显式跳过绘制。</p>
     */
    fun setScrimEnabled(enabled: Boolean) {
        scrimShown = enabled
    }

    fun clear() {
        shown = false
    }

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
                        label = "pageGlowColor"
                    )
                    PageGlowCanvas(animated, centerX, centerY, edgeRatio, scrimShown, cardWRatio, cardHRatio)
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
        const val GLOW_DURATION = 400
        val GLOW_EASING = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    }
}

private const val PAGE_PEAK_ALPHA = 0x2A / 255f
/**
 * 焦点卡附近的额外提亮强度（叠在整页底光之上合成）。
 *
 * <p>0.255 → 0.128 → 0.08（起衰点收进卡内后偏淡）→ **0.128**：起衰点内移让可见区
 * 只剩峰值的 39%~74%，收回原浓度补足观感。只作用于焦点卡那圈 halo，
 * 整页底光 [PAGE_PEAK_ALPHA] 不变，避免连背景漫反射一起变浓。</p>
 */
private const val CARD_EXTRA_ALPHA = 0.128f
/**
 * 起衰点相对海报半对角线的比例：0.6 表示把「满值平台」的边界收进海报内部。
 *
 * <p>取 0.6 而不是 1.0 的原因：1.0 时圆形边界正好穿过海报四角，海报外侧到该圆之间
 * 有一圈**满亮度平台**（侧边约 76px 宽），平台转衰减虽是一阶连续，但视觉上仍像一圈
 * 均匀亮带套在卡片外。收到 0.6 后，光从海报边缘露出时已经在缓降，侧边剩约 74%、
 * 四角剩约 38%，没有平台段，看起来才是「贴着卡片渐隐」。</p>
 */
private const val CARD_FLAT_SCALE = 0.6f
/**
 * 光自**海报轮廓**起再向外扩散多少 dp 归零（卡内那段衰减不计入）。
 *
 * <p>归零点 = 半对角线 + 30dp，所以露在白框外侧的光只有 30dp 就走完，最贴身。
 * 起衰点仍在卡内（[CARD_FLAT_SCALE] × 半对角线），两段合起来才是完整衰减带：
 * 卡内 0.4×半对角线 + 卡外 30dp。
 * 想铺得更开就加大本值；想更淡更窄就减小 [CARD_FLAT_SCALE]。</p>
 */
private const val CARD_SPREAD_DP = 30
/** 整屏半径上的均匀采样档数；档越密，相邻段斜率变化越小，越看不出任何环带。 */
private const val GLOW_STOPS = 20
/**
 * [tFlat, tEnd] 这一段衰减带内的额外采样档数。
 *
 * <p>衰减带（卡内 0.4×半对角线 + 卡外 30dp）只占整屏半径的百分之几；若只靠上面的
 * 均匀档，整条衰减会被压成一两段直线，衔接处重新出现可辨的折点。这里单独加密采样。</p>
 */
private const val HALO_STOPS = 12

@Composable
private fun PageGlowCanvas(
    color: androidx.compose.ui.graphics.Color,
    cx: Float,
    cy: Float,
    scrimEdge: Float,
    scrimShown: Boolean,
    cardW: Float,
    cardH: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (color.alpha <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        val w = size.width
        val h = size.height
        val a = color.alpha
        // 圆心随焦点卡在整屏的位置移动；半径按屏对角线取，让光铺开到整页背景而非一条。
        val radius = kotlin.math.hypot(w, h) * 0.72f
        val center = Offset(w * cx, h * cy)

        /*
         * 整页底光 + 焦点卡提亮合成**一条**径向曲线，而不是两次 draw 叠加。
         *
         * 此前是「整页渐变」再叠「卡片圆形渐变」：卡片那层在自己的半径处收敛到 0，
         * 亮度曲线在该点的斜率从陡降突然变成只剩底光的缓降 —— 这个导数突变就是用户
         * 反复看到的环状「边缘生硬」。无论把它的 colorStops 调多密、形状改成圆形，
         * 只要还是两层叠加，交界处就永远有一个斜率折点。
         *
         * 现在把两者写成同一条 alpha(t)（t = 距离 / 整屏半径）：
         *   base(t) = 整页底光，用首尾导数均为 0 的平滑多项式降到 0；
         *   halo(t) = 焦点卡额外提亮，起衰点（海报内部 0.6×半对角线）以内保持峰值，
         *             此后用 smoothstep 收敛到 0，归零点在海报轮廓外 30dp，两端导数同样为 0。
         * 采样成 colorStops 后由单次 radialGradient 绘制（长尾窄带单独加密，见下方）：整条曲线 C1 连续，
         * 白框外侧到整页背景之间不再有任何层叠边界，光自然融为一体。
         *
         * 起衰点以内被不透明海报挡住、不可见，所以保持峰值不会造成「实心光斑」；
         * 真正露出的是海报边缘之外那一段，此时光已在缓降，贴边渐隐、没有亮带平台。
         */
        val halfW = w * cardW / 2f
        val halfH = h * cardH / 2f
        val spread = CARD_SPREAD_DP.dp.toPx()
        val halfDiag = kotlin.math.hypot(halfW, halfH)
        val hasCard = cardW > 0f && cardH > 0f && halfDiag > 0f
        /*
         * 起衰点收在海报内部（0.6 × 半对角线），归零点落在海报轮廓外 30dp：
         * 光露到卡外时已在缓降，且只在白框外走 30dp 就收完，边界最贴身。
         */
        val flat = halfDiag * CARD_FLAT_SCALE
        val zero = halfDiag + spread
        val tFlat = if (hasCard) (flat / radius).coerceIn(0f, 0.80f) else 0f
        val tEnd = if (hasCard) (zero / radius).coerceIn(tFlat + 0.02f, 0.92f) else 0f
        val span = tEnd - tFlat

        /*
         * 采样点：整屏半径均匀取 GLOW_STOPS 档，再在 [tFlat, tEnd] 这段长尾里额外插入
         * HALO_STOPS 档。这一带只占半径百分之几；若只按均匀档采样，长尾会退化成一两段
         * 直线，与底光的斜率衔接处重新出现折点（即用户看到的硬边）。
         */
        val raw = ArrayList<Float>(GLOW_STOPS + HALO_STOPS + 1)
        for (i in 0 until GLOW_STOPS) raw.add(i / (GLOW_STOPS - 1f))
        if (hasCard && span > 0f) {
            for (i in 0..HALO_STOPS) raw.add((tFlat + span * i / HALO_STOPS).coerceIn(0f, 1f))
        }
        raw.sort()
        // 去掉几乎重合的档，避免出现零长度色段。
        val ts = ArrayList<Float>(raw.size)
        for (v in raw) if (ts.isEmpty() || v - ts[ts.size - 1] > 1e-4f) ts.add(v)

        val stops: Array<Pair<Float, androidx.compose.ui.graphics.Color>> =
            Array(ts.size) { i ->
                val t = ts[i]
                // 1 - 3t² + 2t³：t=0 与 t=1 处导数都为 0，整页底光无硬收边。
                val base = PAGE_PEAK_ALPHA * (1f - 3f * t * t + 2f * t * t * t)
                // u 在起衰点以内为 0（峰值）、到 tEnd 为 1（归零），smoothstep 首尾导数为 0。
                val u = if (hasCard && span > 0f) ((t - tFlat) / span).coerceIn(0f, 1f) else 1f
                val halo = CARD_EXTRA_ALPHA * (1f - u * u * (3f - 2f * u))
                val alpha = ((base + halo) * a).coerceIn(0f, 1f)
                t to color.copy(alpha = alpha)
            }
        drawRect(
            brush = Brush.radialGradient(
                colorStops = stops,
                center = center,
                radius = radius
            )
        )
        /*
         * 主视觉羽化 scrim 画在本层之上，末端是纯不透明 #101114，像挡板盖住 scrimEdge
         * 之上的光。让光严格从这条边之下渐入、交界处光强为 0：scrim 末端与页面底色同为
         * #101114，上下连续，就不会出现水平断层。scrimEdge 由 HomeActivity 按主视觉行的
         * 实时屏幕位置算出，随列表滚动更新。
         *
         * ⚠️ 没有主视觉的页面必须关掉这层（[PageGlowComposeView.setScrimEnabled]）：
         * verticalGradient 默认 Clamp 平铺，startY 以上一律取首个颜色（不透明的页面底色）。
         * 页面若从不设置 edge，默认 1f 就变成「整屏盖一层不透明底色」，下面的光完全看不见。
         */
        if (scrimShown) {
            val edgeY = h * scrimEdge
            val fadeBand = h * 0.10f
            val pageBg = androidx.compose.ui.graphics.Color(0xFF101114.toInt())
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(pageBg, androidx.compose.ui.graphics.Color.Transparent),
                    startY = edgeY,
                    endY = edgeY + fadeBand
                )
            )
        }
    }
}

package com.fongmi.android.tv.ui.custom

import android.util.Log
import android.view.View
import androidx.leanback.widget.BaseGridView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 首页竖直列表的「行落位」控制器（Kotlin）。
 *
 * ### 为什么会跳动
 * 首页 recycler 是 Leanback 的 [BaseGridView]，滚动策略为 {@code FOCUS_SCROLL_ALIGNED}：
 * 焦点换行时由父网格自己滚一次，并且按「行中心落在视口中线」对齐（实测进「最近观看」
 * 时行顶从 973 平滑滚到 378，行中心正好压在视口中线 540）。页面若再用 post / scrollBy
 * 二次纠偏，就变成「先滚上去、再弹回来」两段位移 —— 肉眼看到的就是跳动。
 *
 * ### 做法：改它「要滚到哪」，而不是跟它抢
 * [BaseGridView] 的对齐由两组参数决定：
 * - {@code windowAlignment*}：窗口侧的对齐边（默认 {@code BOTH_EDGE} + 50%，即视口中线）
 * - {@code itemAlignment*}：行侧的对齐点（默认 50%，即行中心）
 *
 * 把「行侧对齐点」改成行底边、「窗口侧对齐边」改成下边 + 16dp 偏移后，父网格那一次焦点
 * 滚动就直接滚到「行下沿 = 屏底 − 16dp」，全程只有一段动画，不需要任何纠偏。
 *
 * **一次性启用、不来回切**：从主视觉行开始就把对齐方式定成「行下沿贴屏底 16dp」，之后
 * 每次换行都只滚一段。中间若切回 Leanback 的居中再切回来，就会出现「居中滚一半 → 再纠回
 * 目标」的第二段位移 —— 实测正是这个来回切换在制造跳动，所以这里不做还原。
 * 代价是分类行 / 站源行也停在下沿（原来是垂直居中），换来全程一致的单一动画。
 *
 * [ensureBottom] / [ensureTop] 是校验兜底：不同 Leanback 版本对齐算法可能有出入，
 * 位置偏差超过 2px 才补一次绝对锚定（幂等），正常情况下一帧都不动。
 */
object HeroRowScroll {

    private const val TAG = "HeroRowScroll"

    private const val MAX_SAMPLE_FRAMES = 40
    private const val STABLE_FRAMES = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var job: Job? = null

    /** 原值快照：windowAlignment / windowAlignmentOffset。 */
    private var saved: IntArray? = null

    /** 是否已处于「底部对齐」状态，避免重复设置触发多余 requestLayout。 */
    private var armed = false

    /**
     * 启用「行下沿对齐到屏底 − [gapPx]」。
     *
     * <p>在焦点仍在主视觉行时就调用：这样按下方向键触发的下一次对齐滚动直接落在目标位置。
     * 若等焦点已经进到「最近观看」再改，父网格已经按居中滚完了，改参数只会再补一次移动。</p>
     */
    @JvmStatic
    fun arm(recycler: RecyclerView, gapPx: Int) {
        val grid = recycler as? BaseGridView ?: return
        if (saved == null) saved = intArrayOf(grid.windowAlignment, grid.windowAlignmentOffset)
        if (armed) return
        armed = true
        /*
         * 只动「行侧对齐点」，不碰窗口侧：
         * 窗口对齐保持默认（BOTH_EDGE + 50%）＝ 视口中线；行侧对齐点取行底边（100%），
         * 再给一个负偏移，把「行底边」压到中线以下 (屏高/2 − gap) 处。
         * 于是行底边落点 = 中线 + (屏高/2 − gap) = 屏底 − gap，正是目标。
         *
         * 实测直接改 windowAlignment 的边（LOW / HIGH 两种取值结果完全一样，行底都停在
         * 屏顶下方 32px）不生效，那是条死路；改行侧对齐点才真正被 GridLayoutManager 读走。
         */
        grid.setItemAlignmentOffsetPercent(100f)
        grid.setItemAlignmentOffset(gapPx - grid.height / 2)
        Log.i(TAG, "arm gap=$gapPx offset=${gapPx - grid.height / 2} h=${grid.height}")
    }

    /** 还原 Leanback 默认对齐（行中心 → 视口中线）。 */
    @JvmStatic
    fun release(recycler: RecyclerView?) {
        val grid = recycler as? BaseGridView ?: return
        val backup = saved ?: return
        if (!armed) return
        armed = false
        saved = null
        grid.setWindowAlignment(backup[0])
        grid.setWindowAlignmentOffset(backup[1])
        grid.setItemAlignmentOffset(0)
        grid.setItemAlignmentOffsetPercent(50f)
        Log.i(TAG, "release")
    }

    /** 校验「最近观看」下沿是否真的落在 屏底 − [gapPx]；偏差 &gt; 2px 才补一次锚定。 */
    @JvmStatic
    fun ensureBottom(recycler: RecyclerView, position: Int, gapPx: Int) {
        if (position < 0) return
        job?.cancel()
        job = scope.launch {
            try {
                val row = awaitRow(recycler, position) ?: return@launch
                if (row.height <= 0) return@launch
                awaitSettled(recycler, position)
                converge(recycler, position, recycler.bottom - gapPx - row.height)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.i(TAG, "ensureBottom failed: $t")
            }
        }
    }

    /** 校验列表是否真的回到顶部；还在半路才补一次锚定。 */
    @JvmStatic
    fun ensureTop(recycler: RecyclerView, position: Int) {
        if (position < 0) return
        job?.cancel()
        job = scope.launch {
            try {
                awaitRow(recycler, position) ?: return@launch
                awaitSettled(recycler, position)
                repeat(6) { attempt ->
                    if (!recycler.canScrollVertically(-1)) return@launch
                    val manager = recycler.layoutManager as? LinearLayoutManager ?: return@launch
                    Log.i(TAG, "ensureTop attempt=$attempt top=${rowTop(recycler, position)}")
                    manager.scrollToPositionWithOffset(position, 0)
                    delay(32)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.i(TAG, "ensureTop failed: $t")
            }
        }
    }

    /** 页面销毁时兜底：取消在途校验，状态清零，下次进入重新取快照。 */
    @JvmStatic
    fun cancel() {
        job?.cancel()
        job = null
        saved = null
        armed = false
    }

    private suspend fun awaitRow(recycler: RecyclerView, position: Int): View? {
        repeat(8) {
            recycler.findViewHolderForAdapterPosition(position)?.itemView?.let { return it }
            delay(16)
        }
        return recycler.findViewHolderForAdapterPosition(position)?.itemView
    }

    /** 等父网格自己的焦点滚动落定：连续 [STABLE_FRAMES] 帧位置不变。 */
    private suspend fun awaitSettled(recycler: RecyclerView, position: Int) {
        var last = Int.MIN_VALUE
        var stable = 0
        repeat(MAX_SAMPLE_FRAMES) {
            val top = rowTop(recycler, position) ?: return@repeat
            if (top == last) {
                if (++stable >= STABLE_FRAMES) return
            } else {
                stable = 0
                last = top
            }
            delay(16)
        }
    }

    private suspend fun converge(recycler: RecyclerView, position: Int, targetTop: Int) {
        val row = recycler.findViewHolderForAdapterPosition(position)?.itemView ?: return
        repeat(4) { attempt ->
            if (!row.isAttachedToWindow) return
            val loc = IntArray(2)
            row.getLocationOnScreen(loc)
            val delta = loc[1] - targetTop
            if (abs(delta) <= 2) return
            Log.i(TAG, "converge try=$attempt top=${loc[1]} target=$targetTop")
            val manager = recycler.layoutManager as? LinearLayoutManager ?: return
            manager.scrollToPositionWithOffset(position, targetTop - recycler.top - recycler.paddingTop)
            delay(32)
        }
    }

    private fun rowTop(recycler: RecyclerView, position: Int): Int? {
        val view = recycler.findViewHolderForAdapterPosition(position)?.itemView ?: return null
        if (!view.isAttachedToWindow) return null
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return loc[1]
    }
}

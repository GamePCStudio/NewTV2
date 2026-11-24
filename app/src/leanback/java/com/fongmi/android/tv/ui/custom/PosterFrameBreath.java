package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 海报选中框的慢闪呼吸：白框静止 500ms 后在 100% ↔ 20% 之间缓慢往复。
 *
 * <p><b>为什么做在 foreground 上</b>：白框是 {@code R.id.poster}（或堆叠按钮的
 * {@code layers}）的 foreground（{@code selector_vod}），与海报图片、卡片缩放互不干扰。
 * 只改这层 drawable 的 alpha，卡片本身、文字层、光效都不受影响。</p>
 *
 * <p><b>alpha 必须 mutate 后再动</b>：从 XML 加载的 {@code selector_vod} 及其子
 * {@code shape_vod_focused} 会和别的卡片共享 ConstantState，不 mutate 时一张卡设 alpha
 * 会把整屏白框一起带动（GradientDrawable 的 alpha 存于自身实例，但 ConstantState 共享
 * 时旧版本仍可能串）。这里在构造期 mutate 一次，彻底隔离。</p>
 *
 * <p><b>滑动判定</b>：同时监听焦点卡上层的所有 {@link RecyclerView}（行内
 * HorizontalGridView + 外层列表）与 ViewTreeObserver 的滚动回调，任一滚动都把呼吸
 * 停掉、白框回到 100%，并重新计时 500ms。只有「真的静止」满 500ms 才开始呼吸，
 * 避免遥控器连按时白框一边飞一边闪。</p>
 */
public final class PosterFrameBreath {

    /** 选中框静止多久之后开始呼吸。 */
    private static final long IDLE_MS = 500L;
    /** 单程时长：100% → 20%，往复一周期 2.4s。 */
    private static final long HALF_CYCLE_MS = 1200L;
    /** 呼吸最低亮度：20%。 */
    private static final int ALPHA_MIN = 51;
    private static final int ALPHA_MAX = 255;
    /**
     * 往复次数上限：改为有限次数（原来是 INFINITE），静止太久后自然停表回到满亮，
     * 避免焦点长时间不动时白框一直闪、整卡每帧重绘。3 个来回 ≈ 7.2s 后停。
     */
    private static final int BREATH_REPEAT = 5;
    /** 低配电视直接关闭呼吸，只保留聚焦时的瞬时白框。 */
    private static final boolean BREATH_DISABLED = GlowTier.isLowEnd();

    private final View frame;
    private final Drawable foreground;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable startTask = this::start;
    private final List<RecyclerView> scrollers = new ArrayList<>();

    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            if (dx != 0 || dy != 0) defer();
        }
    };

    /** 兜底：非 RecyclerView 的滚动（如整页滚动的容器）也能触发重新计时。 */
    private final ViewTreeObserver.OnScrollChangedListener treeScrollListener = this::defer;

    private ValueAnimator animator;
    private boolean focused;
    /** 所有存活实例：页面退后台时统一停表，避免呼吸动画在后台持续重绘。 */
    private static final Set<PosterFrameBreath> ALIVE = new CopyOnWriteArraySet<>();
    /** 页面是否处于前台（由 BaseActivity 的 onResume/onPause 统一驱动）。 */
    private static volatile boolean pageActive = true;

    /**
     * @param frame 承载白框的那个视图：网格卡传 {@code R.id.poster}，
     *              「查看全部」堆叠按钮传 {@code PosterStackView#getGlowFrame()}。
     */
    public PosterFrameBreath(View frame) {
        this.frame = frame;
        ALIVE.add(this);
        Drawable fg = frame.getForeground();
        if (fg != null) {
            Drawable mutated = fg.mutate();
            if (mutated != fg) frame.setForeground(mutated);
            this.foreground = mutated;
        } else {
            this.foreground = null;
        }
    }

    /** 由卡片的焦点回调驱动；聚焦时开始 500ms 倒计时，失焦立即复位。 */
    public void setFocused(boolean value) {
        if (focused == value) return;
        focused = value;
        if (value) {
            attachScrollers(true);
            defer();
        } else {
            attachScrollers(false);
            stop();
        }
    }

    /** 取消呼吸并把白框恢复到 100%，实例保留，下一轮聚焦仍可用。 */
    public void stop() {
        handler.removeCallbacks(startTask);
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        setAlpha(ALPHA_MAX);
    }

    /** ViewHolder 解绑时调用：停表、摘监听、复位 alpha。 */
    public void release() {
        focused = false;
        attachScrollers(false);
        stop();
        ALIVE.remove(this);
    }

    /** 页面进前台 / 退后台：退后台统一停表并复位，前台只放行、等下次聚焦再计时。 */
    public static void setPageActive(boolean active) {
        pageActive = active;
        if (!active) {
            for (PosterFrameBreath breath : ALIVE) breath.stop();
        }
    }

    /** 有滚动：先停呼吸、白框回满，再重新计 500ms。 */
    private void defer() {
        stop();
        if (focused && pageActive && !BREATH_DISABLED) handler.postDelayed(startTask, IDLE_MS);
    }

    private void start() {
        if (!focused || !pageActive || BREATH_DISABLED || foreground == null) return;
        // 从 1 → 0 起步：起手就是满亮（视觉上无跳变），随后平滑降到 20%，再由 REVERSE 回升。
        // 有限次数往复后停在满亮，避免静止时无限重绘。
        ValueAnimator value = ValueAnimator.ofFloat(1f, 0f);
        value.setDuration(HALF_CYCLE_MS);
        value.setRepeatCount(BREATH_REPEAT);
        value.setRepeatMode(ValueAnimator.REVERSE);
        value.setInterpolator(new AccelerateDecelerateInterpolator());
        value.addUpdateListener(anim -> {
            if (!pageActive) {
                stop();
                return;
            }
            float t = (float) anim.getAnimatedValue();
            setAlpha(Math.round(ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * t));
        });
        animator = value;
        value.start();
    }

    private void setAlpha(int alpha) {
        if (foreground == null || foreground.getAlpha() == alpha) return;
        foreground.setAlpha(alpha);
    }

    /** 挂上 / 摘掉焦点卡上层所有 RecyclerView 的滚动监听。 */
    private void attachScrollers(boolean register) {
        if (!register) {
            for (RecyclerView recycler : scrollers) recycler.removeOnScrollListener(scrollListener);
            scrollers.clear();
            ViewTreeObserver observer = frame.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnScrollChangedListener(treeScrollListener);
            return;
        }
        if (!scrollers.isEmpty()) return;
        ViewParent parent = frame.getParent();
        while (parent != null) {
            if (parent instanceof RecyclerView recycler) {
                recycler.addOnScrollListener(scrollListener);
                scrollers.add(recycler);
            }
            parent = parent.getParent();
        }
        ViewTreeObserver observer = frame.getViewTreeObserver();
        if (observer.isAlive()) observer.addOnScrollChangedListener(treeScrollListener);
    }
}

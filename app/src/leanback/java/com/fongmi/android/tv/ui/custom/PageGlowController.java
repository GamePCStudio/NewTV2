package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.event.FocusGlowEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 整页背景漫反射 + 焦点卡局部提亮的通用控制器（首页之外的分页共用）。
 *
 * <p>首页那套实现写在 {@code HomeActivity} 里，且和主视觉 scrim 的断层修复强耦合，
 * 直接复制到分类页 / 搜索结果页 / homemore 会带进一堆用不上的几何计算。这里抽成独立控制器：
 * 页面只要在自己的布局最底层放一个空的 {@code pageGlow} 容器，然后在 {@code initView} 里
 * {@code new PageGlowController(this, binding.pageGlow).attach()} 即可。</p>
 *
 * <p><b>不依赖具体 RecyclerView</b>：几何按「当前焦点所在的卡片」现算 —— 从焦点沿父链找到
 * 最近一层父级是 RecyclerView 的 itemView，再取它内部的 {@code R.id.poster}（或「查看全部」
 * 堆叠按钮的画框）。因此分类页（pager 里每页一个网格）、搜索结果页（每站点一页）、
 * homemore（单层网格）都用同一份代码。</p>
 *
 * <p><b>颜色来自既有的上报链路</b>：{@code VodRectHolder} / {@code HistoryPresenter} 提色成功后
 * 会 post {@link FocusGlowEvent#show}。本控制器只负责把这团光画到当前页面的背景层上，
 * 不改动任何提色逻辑。</p>
 *
 * <p>页面没有主视觉，故 scrim 遮罩保持默认 [PageGlowComposeView] 的 1f（整条遮罩落在屏幕
 * 下沿之外，等于不遮），与首页「先挡板再渐入」的效果不冲突。</p>
 */
public final class PageGlowController {

    private final Activity activity;
    private final FrameLayout host;
    private final PageGlowComposeView glow;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable centerTask = this::updateCenter;
    private final List<RecyclerView> scrollers = new ArrayList<>();

    private boolean attached;

    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            // 滚动途中焦点往往还没落到新卡，延后一帧取几何才不会让光圈先跟旧卡漂一段。
            scheduleCenter();
        }

        @Override
        public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
            // 停稳时再补一次：最后一帧的 post 可能早于焦点归位。
            if (newState == RecyclerView.SCROLL_STATE_IDLE) scheduleCenter();
        }
    };

    private final android.view.ViewTreeObserver.OnGlobalFocusChangeListener focusWatcher =
            (oldFocus, newFocus) -> {
                // newFocus == null 是滚动重组的中间态，此时熄灭会让随后的提色回调无处可去。
                if (newFocus == null) return;
                View card = findCard(newFocus);
                if (card == null) {
                    // 真正离开列表（顶栏 / 分类胶囊 / 底部按钮）：熄灭并摘掉滚动监听。
                    detachScrollers();
                    FocusGlowEvent.clear();
                    return;
                }
                attachScrollers(card);
                scheduleCenter();
            };

    /**
     * @param host 布局最底层的空容器（{@code pageGlow}），光层会作为唯一子 View 铺满它。
     */
    public PageGlowController(Activity activity, FrameLayout host) {
        this.activity = activity;
        this.host = host;
        this.glow = new PageGlowComposeView(activity);
    }

    /** 在 Activity 的 {@code initView} 里调用（此时 setContentView 已完成）。 */
    public void attach() {
        if (attached) return;
        attached = true;
        host.addView(glow.getView(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        host.setClickable(false);
        host.setFocusable(false);
        // 本页没有主视觉，也就不存在需要遮挡的 scrim：显式关掉遮罩。
        glow.setScrimEnabled(false);
        activity.getWindow().getDecorView().getViewTreeObserver()
                .addOnGlobalFocusChangeListener(focusWatcher);
        EventBus.getDefault().register(this);
    }

    /** 在 Activity 的 {@code onDestroy} 里调用，摘监听并注销事件订阅，避免持有 Activity。 */
    public void detach() {
        if (!attached) return;
        attached = false;
        activity.getWindow().getDecorView().getViewTreeObserver()
                .removeOnGlobalFocusChangeListener(focusWatcher);
        EventBus.getDefault().unregister(this);
        detachScrollers();
        handler.removeCallbacks(centerTask);
        glow.clear();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFocusGlowEvent(FocusGlowEvent event) {
        if (!attached) return;
        if (event.isClear()) {
            glow.clear();
            return;
        }
        /*
         * 只把「焦点已经不在任何列表项上」当成不该点亮。以前这里要求必须找到 R.id.poster，
         * 列表样式 / 圆形卡 / 结构变化的卡片找不到画框时，颜色与几何会被一起挡掉，
         * 表现就是整页光彻底不亮 —— 宁可退化成整卡范围，也不能整页熄灭。
         */
        View focus = activity.getCurrentFocus();
        if (focus != null && findCard(focus) == null) return;
        if (event.isCenterOnly()) {
            glow.setCenter(event.getCx(), event.getCy(), event.getCardW(), event.getCardH());
        } else {
            // 颜色与几何解耦：完整事件只换色，几何交给 scheduleCenter / 滚动校正。
            glow.setColor(event.getColor());
        }
        scheduleCenter();
    }

    private void scheduleCenter() {
        handler.removeCallbacks(centerTask);
        handler.post(centerTask);
    }

    /** 按当前焦点卡的真实屏幕位置校正圆心与卡片尺寸。 */
    private void updateCenter() {
        View card = findCard(activity.getCurrentFocus());
        if (card == null) return;
        View frame = frameOf(card);
        if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) return;
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        if (screenW <= 0 || screenH <= 0) return;
        int[] loc = new int[2];
        frame.getLocationOnScreen(loc);
        glow.setCenter(
                (loc[0] + frame.getWidth() / 2f) / screenW,
                (loc[1] + frame.getHeight() / 2f) / screenH,
                frame.getWidth() / (float) screenW,
                frame.getHeight() / (float) screenH);
    }

    /**
     * 从焦点出发找「焦点所在的**海报卡**」。
     *
     * <p>判据两条：① 父级是 {@link RecyclerView}（Leanback 网格 / 行里 itemView 的固定特征，
     * 也是「焦点还在列表里」的唯一可靠标志）；② 这个 itemView 内部确实有海报画框。
     * 第二条是为了把<b>站点分类导航</b>排除掉——搜索结果页 / 分类页顶部那排站点胶囊同样是
     * RecyclerView 里的 item，但它只是一排文字，光效不该跟着它跑。</p>
     *
     * <p>画框判据取三种卡片样式的共同特征，不写死单一 id：网格海报是 {@code R.id.poster}，
     * 列表样式 / 圆形卡是 {@code R.id.image}，「查看全部」是 {@code R.id.stack}。
     * 三条都不命中才判为「不是海报卡」，避免将来改结构后整页光永久熄灭。</p>
     *
     * @return 卡片 itemView；焦点在站点导航 / 顶栏 / 输入框上时返回 null。
     */
    private View findCard(View focus) {
        View node = focus;
        while (node != null) {
            ViewParent parent = node.getParent();
            if (parent instanceof RecyclerView) return isPosterCard(node) ? node : null;
            if (!(parent instanceof View)) return null;
            node = (View) parent;
        }
        return null;
    }

    /** 列表项里是否真的有海报画框（三种卡片样式任一命中即可）。 */
    private boolean isPosterCard(View item) {
        if (item.findViewById(R.id.stack) instanceof PosterStackView) return true;
        return item.findViewById(R.id.poster) != null || item.findViewById(R.id.image) != null;
    }

    /**
     * 卡片里真正用来算光晕范围的那一层：优先海报画框，「查看全部」用画框容器，
     * 都没有时退回整卡 —— 范围略大也好过整页不亮。
     */
    private View frameOf(View card) {
        View stack = card.findViewById(R.id.stack);
        if (stack instanceof PosterStackView) return ((PosterStackView) stack).getGlowFrame();
        View poster = card.findViewById(R.id.poster);
        return poster != null ? poster : card;
    }

    /** 挂上焦点卡所在（及外层）所有 RecyclerView 的滚动监听。 */
    private void attachScrollers(View card) {
        if (!scrollers.isEmpty()) return;
        ViewParent parent = card.getParent();
        while (parent != null) {
            if (parent instanceof RecyclerView recycler) {
                recycler.addOnScrollListener(scrollListener);
                scrollers.add(recycler);
            }
            parent = parent.getParent();
        }
    }

    private void detachScrollers() {
        for (RecyclerView recycler : scrollers) recycler.removeOnScrollListener(scrollListener);
        scrollers.clear();
    }
}

package com.fongmi.android.tv.ui.presenter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.FutureTarget;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.bean.DoubanHotItem;
import com.fongmi.android.tv.bean.DoubanHotRow;
import com.fongmi.android.tv.bean.TmdbArt;
import com.fongmi.android.tv.databinding.AdapterDoubanHotBinding;
import com.fongmi.android.tv.ui.custom.BackdropStyle;
import com.fongmi.android.tv.utils.RankLatest;
import com.fongmi.android.tv.utils.ResUtil;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 首页主视觉：一条占满整行的横版剧照 + 片名（logo 优先）+ 评分 + 「查看详情」按钮。
 *
 * <ul>
 *   <li><b>数据源</b>：{@link RankLatest} 的 {@code rank/latest} 榜单（剧集 / 电影两段，
 *       2 + 2 交替）。服务端已通过 TMDB 完成富化，片名、简介、年份、评分、题材、
 *       <b>横版剧照相对路径</b>一次给全；</li>
 *   <li><b>不再刮削</b>：主视觉不按片名请求 TMDB，也不需要 API 密钥。剧照直接用
 *       {@link RankLatest#imageUrl(String)} 拼出完整地址交给 Glide；
 *       图片基地址取设置里已配置的站点，未配置时用官方 {@code image.tmdb.org}，
 *       站点不支持图片路径时自动回落官方地址重试一次；</li>
 *   <li><b>顺序预取</b>：绑定后立即从列表第一条开始，按榜单顺序串行下载「横版剧照 + logo」，
 *       不等到切换才拉图；</li>
 *   <li><b>就绪才展示</b>：只有剧照真正下载成功的条目才进入轮播池，下载中与下载失败的
 *       一律不显示（不占位、不闪空白）；全都没就绪时主视觉区保持底色；</li>
 *   <li><b>自动轮播</b>：7 秒一张，页面失去窗口焦点（退到后台）时暂停；</li>
 *   <li><b>交叉淡入</b>：两层 ImageView 交替，600ms 淡入淡出；</li>
 *   <li><b>聚焦反馈</b>：整体放大已取消，仅「查看详情」按钮切换底色与字色
 *       （常态不透明 {@code #3C3C3C} 实底 + 白字 / 聚焦纯白底 + 近黑字）；</li>
 *   <li><b>片名层</b>：logo 与文字片名共用容器、贴容器底、二选一显示；容器高度按 logo
 *       宽高比分三档 —— 竖版（&lt; 1:1.6）200dp、类正方形（&le; 1:1）120dp、横版 80dp，
 *       logo 态宽度按图片比例自适应且一律不超过 300dp；<b>取不到 logo、回退文字片名时
 *       不限制容器宽度</b>（wrap_content，最多占满 details 内容宽度）。
 *       logo 只读 {@link TmdbStore} 已有的本地缓存（不联网、不触发抓取）：</li>
 *   <li><b>简介行</b>：评分 0 / 空显示「暂无评分」，其下常驻显示榜单给出的剧情简介
 *       （屏宽 70%、最多 2 行、超出省略、行距 1.2 倍且只作用于本 TextView）。</li>
 *   <li><b>指示点</b>：右下角一排，当前项实心白。</li>
 * </ul>
 */
public class DoubanHotPresenter extends Presenter {

    private static final long AUTO_DELAY = 7_000;
    private static final long IMAGE_FADE = 600;
    /** ic_tmdb 的 viewport 宽高比：图标高度对齐文字后，宽度按此比例等比缩放，避免拉扁 logo。 */
    private static final float TMDB_ICON_RATIO = 185.04f / 133.4f;
    /**
     * 剧照层不透明度：与播放详情页的整页背景同源（{@link BackdropStyle#IMAGE_ALPHA}）。
     * 淡入淡出的终值也用这个值，而不是 1f —— 否则每切一张图就闪一下满亮度。
     */
    private static final float IMAGE_ALPHA = BackdropStyle.IMAGE_ALPHA;
    /** 预取超时：剧照给足 15s（w1280），logo 只是片名图，6s 足够。 */
    private static final long BACKDROP_TIMEOUT_MS = 15_000L;
    private static final long LOGO_TIMEOUT_MS = 6_000L;
    /**
     * 主视觉预取线程：严格串行。
     *
     * <p>一屏最多 12 条 × 2 个资源，若并发下发会在启动瞬间打十几个请求，
     * 电视端带宽和内存都会抖；串行还能保证「先就绪的先展示」的顺序与榜单一致。</p>
     */
    private static final ExecutorService PRELOAD = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "HeroPrefetch");
        thread.setDaemon(true);
        return thread;
    });

    private final OnClickListener listener;

    public interface OnClickListener {

        /** 点击主视觉：按片名直接打开搜索结果页（榜单热词不承载播放地址，返回不经搜索页）。 */
        void onDoubanHotClick(DoubanHotItem item);
    }

    public DoubanHotPresenter(OnClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        AdapterDoubanHotBinding binding = AdapterDoubanHotBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        applyBleed(binding.getRoot(), parent);
        /*
         * 主视觉是「背景层」，显式声明为列表里 Z 最低的一行。
         *
         * recycler 内所有行是兄弟 View、共用一个堆叠上下文，默认 Z 全为 0，
         * 谁在上层完全由 ViewGroup 的子 View 绘制顺序决定（Leanback 会按焦点位置重排）。
         * 主视觉会向下溢出 HOME_BOTTOM_BLEED_DP 画一层不透明 #101114 羽化 scrim，
         * 一旦被排到最后绘制，就会盖住「最近观看」标题与海报顶部。
         *
         * 这里给一个负值 translationZ：ViewGroup.buildOrderedChildList() 按 Z 升序绘制，
         * 硬件加速下 RenderNode 同样按 Z 合成 —— 两种路径都保证主视觉恒定在最底层。
         * 与焦点位置、滚动位置、density、屏幕高度全部无关。
         */
        binding.getRoot().setTranslationZ(-1f);
        return new Holder(binding, listener);
    }

    /**
     * 让主视觉的剧照<b>左、右、上三边贴住屏幕边缘</b>，与播放详情页的整页背景严格对齐。
     *
     * <p>首页这一行是 {@code VerticalGridView} 的普通 item，父容器的
     * {@code paddingStart/End = 48dp}（全局左右基线）、{@code paddingTop = 8dp}
     * 会把画框整体缩进一圈。这里给 item 加<b>等量的负 margin</b> 抵消；leanback 的
     * {@code GridLayoutManager} 在测量与落位两处都会计入 margin，因此负值是有效的。</p>
     */
    private static void applyBleed(View view, ViewGroup parent) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) params;
        lp.leftMargin = -parent.getPaddingLeft();
        lp.rightMargin = -parent.getPaddingRight();
        lp.topMargin = -parent.getPaddingTop();
        view.setLayoutParams(lp);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, Object item) {
        ((Holder) viewHolder).bind((DoubanHotRow) item);
    }

    @Override
    public void onUnbindViewHolder(@NonNull ViewHolder viewHolder) {
        ((Holder) viewHolder).unbind();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder viewHolder) {
        super.onViewAttachedToWindow(viewHolder);
        ((Holder) viewHolder).attach();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder viewHolder) {
        ((Holder) viewHolder).detach();
        super.onViewDetachedFromWindow(viewHolder);
    }

    private static final class Holder extends ViewHolder {

        private final AdapterDoubanHotBinding binding;
        private final OnClickListener listener;
        private final Handler handler = new Handler(Looper.getMainLooper());
        /** 全部候选条目，保持榜单原始排片顺序，供逐条补入时复位。 */
        private final List<DoubanHotItem> candidates = new ArrayList<>();
        /** 已确认有剧照、真正参与轮播的条目。 */
        private final List<DoubanHotItem> items = new ArrayList<>();
        /** 剧照解码失败的片名：本次会话不再加入轮播，避免反复重试造成闪动。 */
        private final Set<String> blocked = new HashSet<>();
        private ImageView front;
        private int index;
        /** 每次切换自增：晚到的 Glide 回调据此丢弃，避免旧图覆盖新图。 */
        private int generation;
        /** logo 单独一套序号：网络 logo 是异步的，晚到回调不能盖住下一条的片名层。 */
        private int logoGeneration;
        /** 待预取的条目，严格按榜单顺序；剧照下载成功才进入 items。 */
        private final Deque<DoubanHotItem> queue = new ArrayDeque<>();
        /** 预取批次号：重新绑定 / 离屏后，旧批次的结果不再入池。 */
        private int prefetchGeneration;
        private boolean prefetching;
        /** 是否已经显示过第一条：首条剧照到位前，主视觉区保持空白。 */
        private boolean shownOnce;
        private final Runnable rotate;
        private Holder(@NonNull AdapterDoubanHotBinding binding, OnClickListener listener) {
            super(binding.getRoot());
            this.binding = binding;
            this.listener = listener;
            this.front = binding.backdrop.getImageA();
            this.rotate = () -> {
                if (binding.getRoot().hasWindowFocus()) show(index + 1, true);
                schedule();
            };
            setListeners();
            initTextWidths();
        }

        /**
         * 屏宽 / 字号相关的约束只能在运行时算：简介 = 屏宽 70%；
         * TMDB 图标<b>高度 = 评分行文字高度</b>（取 {@code meta.getTextSize()}，随系统字体缩放），
         * 宽度按 {@link #TMDB_ICON_RATIO} 等比缩放，保证 logo 不被拉扁。
         * <b>文字片名不设 maxWidth</b> —— 只有 logo 态才按 300dp 上限收窄容器。
         */
        private void initTextWidths() {
            int screen = ResUtil.getScreenWidth();
            ViewGroup.LayoutParams lp = binding.overview.getLayoutParams();
            lp.width = (int) (screen * 0.70f);
            binding.overview.setLayoutParams(lp);

            int iconHeight = Math.max(1, Math.round(binding.meta.getTextSize()));
            ViewGroup.LayoutParams icon = binding.tmdbIcon.getLayoutParams();
            icon.height = iconHeight;
            icon.width = Math.max(1, Math.round(iconHeight * TMDB_ICON_RATIO));
            binding.tmdbIcon.setLayoutParams(icon);
        }

        /** logo 态的容器宽度上限：300dp，同时不越过屏幕左右安全边距。文字片名不使用此上限。 */
        private static int maxTitleWidth(int screen) {
            return Math.min(ResUtil.dp2px(300), Math.max(1, screen - ResUtil.dp2px(72)));
        }

        private void setListeners() {
            // 聚焦放大动画已取消；按钮改用「底色 + 文字色」两态反馈（见 styleAction）。
            binding.getRoot().setOnFocusChangeListener((view, hasFocus) -> styleAction(hasFocus));
            binding.getRoot().setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN || items.size() < 2) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    show(index - 1, true);
                    restart();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    show(index + 1, true);
                    restart();
                    return true;
                }
                return false;
            });
            binding.getRoot().setOnClickListener(view -> {
                DoubanHotItem item = current();
                if (item != null) listener.onDoubanHotClick(item);
            });
        }

        /**
         * 「查看详情」按钮两态样式：常态（未选中）= 50% 透明黑 {@code #80000000} 实底 + 白字；
         * 聚焦（选中）= 纯白实底 + 近黑字（{@code #101114}）。字号 14sp / 加粗由布局给定，
         * 这里只切底色与字色；按钮已是纯文字，实心三角图标与对应的染色逻辑均已移除。
         *
         * <p>不用 state_focused 选择器：焦点落在整行 item 的根布局上，
         * 按钮及其子 View 不会进入 focused 状态，选择器不会命中。</p>
         *
         * <p><b>不再改动整体 alpha</b>：半透明由 {@code #80000000} 的 alpha 通道表达，
         * 而非给 view 设 alpha，所以不会被冲淡；聚焦反馈由纯白底 + 近黑字的强对比承担。</p>
         */
        private void styleAction(boolean focused) {
            binding.action.setBackgroundResource(focused ? R.drawable.shape_hot_action_focused : R.drawable.shape_hot_action);
            binding.actionText.setTextColor(focused ? 0xFF101114 : 0xFFFFFFFF);
        }

        private void bind(DoubanHotRow row) {
            stop();
            candidates.clear();
            candidates.addAll(row.getItems());
            items.clear();
            blocked.clear();
            queue.clear();
            for (DoubanHotItem item : candidates) {
                if (item == null || !item.hasBackdrop()) continue;
                queue.add(item);
            }
            index = 0;
            generation++;
            logoGeneration++;
            // 用户可能刚在设置里换过图片站点，重新给新地址一次机会。
            RankLatest.resetBaseImage();
            prefetchGeneration++;
            prefetching = false;
            shownOnce = false;
            resetLayers();
            // 先清干净再预取：首条剧照真正下载成功之前，主视觉区不展示任何一部影片。
            clearContent();
            bindDots(0);
            if (!queue.isEmpty()) prefetchNext();
        }

        /**
         * 按列表顺序逐个预取「横版剧照 + logo」。
         *
         * <p>每条必须等剧照真正落盘后才加入轮播池：下载中的条目一律不显示，
         * 失败的直接隐藏，避免主视觉出现空白帧或半张图。logo 是装饰，失败不阻塞展示，
         * 片名层会退到本地缓存或文字。</p>
         */
        private void prefetchNext() {
            if (prefetching) return;
            DoubanHotItem item = queue.poll();
            if (item == null) return;
            prefetching = true;
            int gen = prefetchGeneration;
            PRELOAD.execute(() -> {
                boolean backdrop = download(item.getBackdrop(), true);
                // 剧照一就绪就交给主线程入池：首条不必等 logo（logo 下载慢时会拖慢首屏）。
                App.post(() -> {
                    if (gen != prefetchGeneration) return;
                    if (backdrop) onPrefetched(item);
                    else blocked.add(item.getTitle());
                });
                if (backdrop && item.hasLogo()) download(item.getLogo(), false);
                App.post(() -> {
                    if (gen != prefetchGeneration) return;
                    prefetching = false;
                    prefetchNext();
                });
            });
        }

        /** 一部影片预取完成：加入轮播池；第一条到位才真正显示主视觉。 */
        private void onPrefetched(DoubanHotItem item) {
            if (items.contains(item)) return;
            items.add(item);
            bindDots(items.size());
            if (!shownOnce) {
                shownOnce = true;
                show(0, false);
            }
            // 首条时池里只有 1 条，schedule() 不会启动轮播；到第 2 条才补上一次调度。
            // 之后不再 restart：否则预取期间每来一条都会重置 7 秒计时，主视觉会长时间停在第一条。
            if (items.size() == 2) restart();
        }

        /** 下载一个资源：先配置站点，失败再官方地址；两者相同只试一次。 */
        private boolean download(String path, boolean backdrop) {
            if (TextUtils.isEmpty(path)) return false;
            String primary = backdrop ? RankLatest.imageUrl(path) : RankLatest.logoUrl(path);
            if (fetch(primary, backdrop)) return true;
            if (backdrop) RankLatest.markBaseImageUnavailable();
            String official = backdrop ? RankLatest.officialImageUrl(path) : RankLatest.officialLogoUrl(path);
            return !TextUtils.isEmpty(official) && !official.equals(primary) && fetch(official, backdrop);
        }

        /**
         * 同步下载到 Glide 的 DATA 缓存（不绑定 ImageView、不解码）。
         *
         * <p>用 Application Context 走 Glide 单例：预取不受页面生命周期影响，
         * 不会在 Activity 销毁后抛「cannot start a load for a destroyed activity」。
         * 之后 {@code show()} 里的 into() 命中同一份磁盘缓存，不再发网络请求。</p>
         */
        private boolean fetch(String url, boolean backdrop) {
            if (TextUtils.isEmpty(url)) return false;
            FutureTarget<File> target = null;
            try {
                target = Glide.with(App.get()).asFile().load(url).submit();
                File file = target.get(backdrop ? BACKDROP_TIMEOUT_MS : LOGO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                return file != null && file.exists() && file.length() > 0;
            } catch (Exception e) {
                return false;
            } finally {
                if (target != null) {
                    try {
                        Glide.with(App.get()).clear(target);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        private void resetLayers() {
            binding.backdrop.getImageA().animate().cancel();
            binding.backdrop.getImageB().animate().cancel();
            binding.backdrop.getImageA().setAlpha(IMAGE_ALPHA);
            binding.backdrop.getImageA().setVisibility(View.VISIBLE);
            binding.backdrop.getImageB().setAlpha(0f);
            binding.backdrop.getImageB().setVisibility(View.GONE);
            front = binding.backdrop.getImageA();
        }

        /** 一条都没有：整块文字层隐藏，只留同色底，不显示占位图也不闪残留文字。 */
        private void clearContent() {
            logoGeneration++;
            binding.name.setText("");
            binding.name.setVisibility(View.INVISIBLE);
            binding.logo.setImageDrawable(null);
            binding.logo.setVisibility(View.INVISIBLE);
            binding.metaRow.setVisibility(View.GONE);
            binding.meta.setVisibility(View.GONE);
            binding.overview.setText("");
            binding.overview.setVisibility(View.GONE);
            binding.action.setVisibility(View.GONE);
            binding.indicator.setVisibility(View.GONE);
        }

        private void show(int position, boolean animate) {
            if (items.isEmpty()) return;
            index = Math.floorMod(position, items.size());
            DoubanHotItem item = current();
            if (item == null) return;
            binding.action.setVisibility(View.VISIBLE);
            // 复用 ViewHolder 时按钮可能残留上一部的聚焦样式，按当前焦点立即校正。
            styleAction(binding.getRoot().hasFocus());
            bindText(item);
            updateDots();
            int gen = ++generation;
            if (!animate) {
                ImageView back = front == binding.backdrop.getImageA() ? binding.backdrop.getImageB() : binding.backdrop.getImageA();
                RequestManager manager = glide(back);
                if (manager != null) manager.clear(back);
                back.setVisibility(View.GONE);
                front.setAlpha(IMAGE_ALPHA);
                front.setVisibility(View.VISIBLE);
                loadArtwork(item, front, null, false, gen);
                return;
            }
            ImageView next = front == binding.backdrop.getImageA() ? binding.backdrop.getImageB() : binding.backdrop.getImageA();
            ImageView old = front;
            next.animate().cancel();
            RequestManager manager = glide(next);
            if (manager != null) manager.clear(next);
            next.setAlpha(0f);
            next.setVisibility(View.INVISIBLE);
            front = next;
            loadArtwork(item, next, old, true, gen);
        }

        /**
         * 加载一张主视觉。
         *
         * <p>用榜单给的相对路径拼网络地址（{@link RankLatest#imageUrl(String)}），
         * 没有路径的条目在 {@link #bind(DoubanHotRow)} 阶段就不会进入预取队列，走不到这里。
         * 解码失败时：若用的是「设置里配置的站点」且该地址与官方不同，自动改用官方地址
         * 重试一次（配置站点未必代理 {@code /t/p/} 图片），仍失败才移出轮播。</p>
         */
        private void loadArtwork(DoubanHotItem item, ImageView target, ImageView old, boolean animate, int gen) {
            bindTitle(item);
            String model = RankLatest.imageUrl(item.getBackdrop());
            if (TextUtils.isEmpty(model)) {
                blocked.add(item.getTitle());
                dropCurrent();
                return;
            }
            startLoad(item, model, false, target, old, animate, gen);
        }

        private void startLoad(DoubanHotItem item, String model, boolean official, ImageView target, ImageView old, boolean animate, int gen) {
            RequestManager manager = glide(target);
            if (manager == null) return;
            manager.load(model)
                    // 不走 Glide 的 centerCrop/fitCenter：两者都会改 ImageView 的 scaleType，
                    // 破坏「宽度铺满 + 顶部贴齐」自算的 MATRIX。保留原图尺寸，矩阵自己算。
                    .dontTransform()
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> t, boolean first) {
                            if (gen != generation) return true;
                            String fallback = RankLatest.officialImageUrl(item.getBackdrop());
                            if (!official && !TextUtils.isEmpty(fallback) && !fallback.equals(model)) {
                                startLoad(item, fallback, true, target, old, animate, gen);
                                return true;
                            }
                            blocked.add(item.getTitle());
                            dropCurrent();
                            return true;
                        }

                        @Override
                        public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> t, @NonNull DataSource source, boolean first) {
                            if (gen != generation) return false;
                            // drawable 已就位，先按新图尺寸重算矩阵再淡入，避免第一帧错位
                            binding.backdrop.applyMatrices();
                            reveal(target, old, animate);
                            return false;
                        }
                    })
                    .into(target);
        }

        /** 剧照不可用时把当前条目从轮播中摘掉，并跳到下一部（没有下一部就清空文字层）。 */
        private void dropCurrent() {
            DoubanHotItem current = current();
            if (current != null) {
                blocked.add(current.getTitle());
                items.remove(current);
            }
            if (index >= items.size()) index = 0;
            bindDots(items.size());
            if (items.isEmpty()) clearContent();
            else show(index, false);
        }

        /** 把 target 淡入、old 淡出；两层都可见时才是真正的交叉淡化。终值恒为 {@link #IMAGE_ALPHA}。 */
        private void reveal(ImageView target, ImageView old, boolean animate) {
            target.animate().cancel();
            if (!animate) {
                target.setAlpha(IMAGE_ALPHA);
                target.setVisibility(View.VISIBLE);
                if (old != null) {
                    old.animate().cancel();
                    old.setAlpha(0f);
                    old.setVisibility(View.GONE);
                }
                return;
            }
            target.setAlpha(0f);
            target.setVisibility(View.VISIBLE);
            target.animate().alpha(IMAGE_ALPHA).setDuration(IMAGE_FADE).start();
            if (old != null && old != target) {
                old.animate().cancel();
                old.animate().alpha(0f).setDuration(IMAGE_FADE).withEndAction(() -> {
                    if (old != front) old.setVisibility(View.GONE);
                }).start();
            }
        }

        /**
         * 片名层：logo 与文字片名二选一，共用 titleBox 容器。
         *
         * <p>logo 用 {@link TmdbStore#logoBitmap(String)} 取<b>未经下采样的原始 Bitmap</b>，
         * 同步解码、同帧显示。早期版本走 Glide 的 {@code fitCenter()}：Glide 会按当时
         * 120dp 的 ImageView 尺寸把图降采样，容器随后被放大到 200dp，等于把小图拉大，
         * 于是出现锯齿和细微马赛克。</p>
         *
         * <p>这里<b>只读本地已有缓存</b>，不联网、不触发抓取：排行榜富化数据不含 logo，
         * 主视觉不能为了片名图再去请求 TMDB。缓存里有就用图片，没有就用文字片名。</p>
         *
         * <p>进入时<b>先清空文字并隐藏两者</b>，取到 bitmap 才显示 logo，取不到才显示文字，
         * 因此加载前不会闪过残留文字。</p>
         *
         * <p>容器高度按 logo 宽高比动态分三档：宽/高 &lt; 1/1.6（竖版）200dp、
         * &le; 1/1（类正方形）120dp、其余（横版）80dp；文字片名用 120dp。
         * logo 态宽度按图片比例自适应，一律不超过 300dp（且不越出屏幕）；
         * 文字片名态则完全不限制宽度，容器与 TextView 都回到 wrap_content。</p>
         */
        private void bindTitle(DoubanHotItem item) {
            int gen = ++logoGeneration;
            binding.name.setText("");
            binding.name.setVisibility(View.INVISIBLE);
            binding.logo.setImageDrawable(null);
            binding.logo.setVisibility(View.INVISIBLE);
            applyTextSize();
            binding.name.setText(item.getTitle());
            if (item.hasLogo()) {
                loadLogo(item, gen, false);
                return;
            }
            // 榜单没给 logo（旧缓存 / 该条目未匹配到）：直接退本地缓存，再退文字。
            bindLocalLogo(item, gen);
        }

        /**
         * 加载榜单给的片名 logo。
         *
         * <p>与主视觉剧照同一套地址规则：优先用设置里配置的站点，加载失败且地址与官方不同时
         * 换官方再试一次；两次都失败才回落到本地缓存 / 文字片名，避免片名层空着。</p>
         */
        private void loadLogo(DoubanHotItem item, int gen, boolean official) {
            RequestManager manager = glide(binding.logo);
            if (manager == null) return;
            String url = official ? RankLatest.officialLogoUrl(item.getLogo()) : RankLatest.logoUrl(item.getLogo());
            if (TextUtils.isEmpty(url)) {
                bindLocalLogo(item, gen);
                return;
            }
            manager.load(url)
                    // logo 是透明 PNG、体积不大；按原图解码，避免按当前容器尺寸下采样后放大发虚。
                    .override(Target.SIZE_ORIGINAL)
                    .dontAnimate()
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> t, boolean first) {
                            if (gen != logoGeneration) return true;
                            String fallback = RankLatest.officialLogoUrl(item.getLogo());
                            if (!official && !TextUtils.isEmpty(fallback) && !fallback.equals(model)) {
                                loadLogo(item, gen, true);
                                return true;
                            }
                            bindLocalLogo(item, gen);
                            return true;
                        }

                        @Override
                        public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> t, @NonNull DataSource source, boolean first) {
                            // 已手动按图片比例设置尺寸与可见性，阻止 Glide 再套一次默认 fitCenter。
                            if (gen != logoGeneration) return true;
                            showLogo(resource, item, gen);
                            return true;
                        }
                    })
                    .into(binding.logo);
        }

        /** logo 就位：按图片比例定容器尺寸后显示，文字片名隐藏。 */
        private void showLogo(Drawable drawable, DoubanHotItem item, int gen) {
            if (gen != logoGeneration) return;
            int width = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            if (width <= 0 || height <= 0) {
                bindLocalLogo(item, gen);
                return;
            }
            applyLogoSize(width, height);
            binding.logo.setImageDrawable(drawable);
            binding.logo.setVisibility(View.VISIBLE);
            binding.name.setVisibility(View.INVISIBLE);
        }

        /**
         * 本地兜底：读 {@link TmdbStore} 已缓存的 logo（不联网、不触发抓取）。
         * 没有缓存就用文字片名，保证片名层永远有内容。
         */
        private void bindLocalLogo(DoubanHotItem item, int gen) {
            if (gen != logoGeneration) return;
            TmdbArt art = TmdbStore.find(item.getTitle());
            Bitmap bitmap = art == null ? null : TmdbStore.logoBitmap(art.getLogo());
            if (bitmap == null || bitmap.isRecycled()) {
                binding.name.setVisibility(View.VISIBLE);
                return;
            }
            applyLogoSize(bitmap.getWidth(), bitmap.getHeight());
            binding.logo.setImageBitmap(bitmap);
            binding.logo.setVisibility(View.VISIBLE);
            binding.name.setVisibility(View.INVISIBLE);
        }

        /**
         * 按 logo 固有宽高比定容器尺寸（三档）：
         * 宽/高 &lt; 1/1.6 → 竖版 200dp；&le; 1/1 → 类正方形 120dp；其余横版 → 80dp。
         * 宽度按图片比例自适应，硬上限 300dp；触顶时按宽度反算高度保持等比。
         */
        private void applyLogoSize(int w, int h) {
            float ratio = w > 0 && h > 0 ? (float) w / h : 1.6f;
            int heightDp;
            if (ratio < 1f / 1.6f) heightDp = 200;
            else if (ratio <= 1f) heightDp = 120;
            else heightDp = 80;
            int height = ResUtil.dp2px(heightDp);
            int maxWidth = maxTitleWidth(ResUtil.getScreenWidth());
            int width = (int) (height * ratio);
            if (width > maxWidth) {
                width = maxWidth;
                height = (int) (width / Math.max(ratio, 0.01f));
            }
            setTitleBox(Math.max(1, width), Math.max(1, height));
        }

        /**
         * 文字片名：<b>不限制容器宽度</b>，高 120dp。
         *
         * <p>titleBox 与 name 都回到 wrap_content，宽度按文字自然排布，最多撑到 details
         * 的内容宽度（屏宽减去左右 padding）；超过仍由 {@code maxLines=2 + ellipsize=end}
         * 收尾。之前固定成 300dp 会把长片名硬折成两行甚至截掉，而 logo 态才需要按图片
         * 比例定宽，两者不能共用同一个上限。</p>
         */
        private void applyTextSize() {
            setTitleBox(ViewGroup.LayoutParams.WRAP_CONTENT, ResUtil.dp2px(120));
            binding.name.setMaxWidth(Integer.MAX_VALUE);
        }

        private void setTitleBox(int width, int height) {
            ViewGroup.LayoutParams box = binding.titleBox.getLayoutParams();
            box.width = width;
            box.height = height;
            binding.titleBox.setLayoutParams(box);
            ViewGroup.LayoutParams logo = binding.logo.getLayoutParams();
            logo.width = width;
            logo.height = height;
            binding.logo.setLayoutParams(logo);
        }

        private void bindText(DoubanHotItem item) {
            bindMeta(item);
            bindOverview(item);
        }

        /**
         * meta 行「评分 · 年份 · 类型 · 题材」。
         *
         * <p>四项全部来自榜单富化数据（{@link DoubanHotItem}）：服务端已经通过 TMDB
         * 把评分、上映年份与题材算好，展示层不再查本地素材、也不触发任何刮削。</p>
         */
        private void bindMeta(DoubanHotItem item) {
            List<String> meta = new ArrayList<>(4);
            String rating = item.getRating();
            // 0 分与「没有评分」在数据上是一回事，统一显示「暂无评分」。
            if (isNoRating(rating)) meta.add(binding.getRoot().getContext().getString(R.string.douban_hot_no_rating));
            else meta.add(binding.getRoot().getContext().getString(R.string.douban_hot_rating, rating));
            String year = item.getYear();
            if (!TextUtils.isEmpty(year)) meta.add(year);
            String type = typeLabel(item.getType());
            if (!TextUtils.isEmpty(type)) meta.add(type);
            String genre = item.getGenre();
            if (!TextUtils.isEmpty(genre)) meta.add(genre);
            // TMDB 图标只在「确有评分」时显示，用于标注评分口径来自 TMDB；无评分时不显示。
            boolean hasRating = !isNoRating(rating) && !TextUtils.isEmpty(rating);
            boolean empty = meta.isEmpty();
            binding.metaRow.setVisibility(empty ? View.GONE : View.VISIBLE);
            binding.meta.setText(empty ? "" : TextUtils.join(" · ", meta));
            binding.tmdbIcon.setVisibility(empty || !hasRating ? View.GONE : View.VISIBLE);
        }

        /** 0 / 0.0 / 空 / 负数都算「暂无评分」；非数字按有评分处理。 */
        private boolean isNoRating(String rating) {
            if (TextUtils.isEmpty(rating)) return true;
            try {
                return Float.parseFloat(rating.trim()) <= 0f;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /**
         * 剧情简介常显（有简介时）。
         *
         * <p>首行缩进 2 个字符（2em）：用 {@link LeadingMarginSpan.Standard} 包一层，
         * 只缩进第一行、其余行不动，且不受 API 限制（{@code android:textIndent} 需 API 28+，
         * 当前资源框架不含该属性）。缩进量按当前字号取 2×textSize（CJK 字宽≈1em）。</p>
         *
         * <p>简介直接取榜单里的 {@code description}，不需要 TMDB 再补。</p>
         */
        private void bindOverview(DoubanHotItem item) {
            String text = item.getOverview();
            if (TextUtils.isEmpty(text)) {
                binding.overview.setVisibility(View.GONE);
            } else {
                SpannableString ss = new SpannableString(text);
                int indent = (int) (binding.overview.getTextSize() * 2);
                ss.setSpan(new LeadingMarginSpan.Standard(indent, 0), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                binding.overview.setText(ss);
                binding.overview.setVisibility(View.VISIBLE);
            }
        }

        private String typeLabel(String type) {
            int res;
            if (DoubanHotItem.TYPE_TV.equals(type)) res = R.string.douban_hot_type_tv;
            else if (DoubanHotItem.TYPE_MOVIE.equals(type)) res = R.string.douban_hot_type_movie;
            else return "";
            return binding.getRoot().getContext().getString(res);
        }

        private void bindDots(int count) {
            binding.indicator.removeAllViews();
            binding.indicator.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
            int size = ResUtil.dp2px(7);
            int margin = ResUtil.dp2px(3);
            for (int i = 0; i < count; i++) {
                View dot = new View(binding.indicator.getContext());
                dot.setBackgroundResource(R.drawable.selector_hot_dot);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
                params.setMarginStart(margin);
                params.setMarginEnd(margin);
                binding.indicator.addView(dot, params);
            }
            updateDots();
        }

        private void updateDots() {
            for (int i = 0; i < binding.indicator.getChildCount(); i++) {
                binding.indicator.getChildAt(i).setSelected(i == index);
            }
        }

        /**
         * 取 Glide 的 RequestManager，Activity 已销毁时返回 {@code null}（本次放弃加载）。
         *
         * <p>{@code Glide.with(view)} 会沿 View 的 Context 找到宿主 Activity 并校验状态，
         * 已销毁即抛 {@code IllegalArgumentException: You cannot start a load for a destroyed
         * activity}。首页主视觉的 7 秒自动轮播定时器、图片加载回调都可能比页面存活更久，
         * 退出首页后再触发一次 {@link #show} 就会命中这条崩溃（实测 release 包首次启动后必现）。
         * 统一在这里短路，调用方判空即可。</p>
         */
        private static RequestManager glide(View view) {
            Context context = view.getContext();
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                if (activity.isFinishing() || activity.isDestroyed()) return null;
            }
            return Glide.with(view);
        }

        private DoubanHotItem current() {
            return index >= 0 && index < items.size() ? items.get(index) : null;
        }

        private void attach() {
            restart();
        }

        private void detach() {
            stop();
        }

        private void restart() {
            stop();
            schedule();
        }

        private void schedule() {
            handler.removeCallbacks(rotate);
            if (items.size() > 1 && binding.getRoot().isAttachedToWindow()) handler.postDelayed(rotate, AUTO_DELAY);
        }

        private void stop() {
            handler.removeCallbacks(rotate);
        }

        private void unbind() {
            detach();
            generation++;
            prefetchGeneration++;
            prefetching = false;
            queue.clear();
            items.clear();
            candidates.clear();
            blocked.clear();
            styleAction(false);
            binding.action.setVisibility(View.VISIBLE);
            clearContent();
            RequestManager imageA = glide(binding.backdrop.getImageA());
            if (imageA != null) imageA.clear(binding.backdrop.getImageA());
            RequestManager imageB = glide(binding.backdrop.getImageB());
            if (imageB != null) imageB.clear(binding.backdrop.getImageB());
            RequestManager logo = glide(binding.logo);
            if (logo != null) logo.clear(binding.logo);
        }
    }
}

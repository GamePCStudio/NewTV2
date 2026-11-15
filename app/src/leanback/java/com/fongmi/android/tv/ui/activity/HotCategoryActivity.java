package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.icu.text.BreakIterator;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.DoubanHotItem;
import com.fongmi.android.tv.bean.TmdbArt;
import com.fongmi.android.tv.bean.DoubanHotRow;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.RowHeader;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.databinding.ActivityHotCategoryBinding;
import com.fongmi.android.tv.databinding.ViewHomeToolbarBinding;
import com.fongmi.android.tv.databinding.ViewSiteSheetBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.ui.adapter.ConfigMenuAdapter;
import com.fongmi.android.tv.ui.adapter.SiteMenuAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.HeroInset;
import com.fongmi.android.tv.ui.custom.HeroTransition;
import com.fongmi.android.tv.ui.custom.ToolbarEntries;
import com.fongmi.android.tv.ui.presenter.DoubanHotPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ArtBlacklist;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.HotRank;
import com.fongmi.android.tv.utils.ArtSource;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 电影 / 剧集 / 综艺频道页的完整实现（三个页面共用这一份模板）。
 *
 * <p><b>与首页的关系</b>：布局、顶栏、站源面板、行样式、主视觉 Presenter、焦点链、返回分层
 * 全部沿用首页；差异只有三处 —— ① 数据源换成 {@link HotRank} 的分类热榜；② 整页移除
 * 「最近观看」容器（不注册 HistoryPresenter、不加载 History、无删除态）；③ 「更新推荐」
 * 换成四个平台热搜榜容器，容器结构仍是「标题行 + 一条 ListRow 横向海报行」。</p>
 *
 * <p><b>空态口径（与首页一致）</b>：主视觉按「有没有已落盘的横版剧照」判断，
 * 一张都没有就整行收起、下方栏目自动上移；平台容器按「网格是否为空」判断，
 * 网格空则「标题 + 网格」一起收起，不留孤立标题与占位卡。两者都用内容签名做幂等，
 * 重复广播不重建行，插入 / 收起时保持可视锚点，不产生留白、错位或抖动。</p>
 *
 * <p><b>数据口径</b>：主视觉直接调 {@code HotRank.fetch(category(), SitesCallback)}，
 * 输出与搜索页完全相同的合并榜（电影 / 电视剧按 爱奇艺→腾讯视频→芒果TV→优酷 轮转，
 * 综艺按 芒果TV→爱奇艺→腾讯视频→优酷 轮转，简繁归一 + 去重 + 前 10 条），
 * 不做首页 DoubanHot 的「2 剧集→2 电影→…」混排。四个平台容器按 site 过滤原始榜单，
 * 保持各平台自己的名次。</p>
 *
 * <p><b>海报与评分</b>：统一由 {@link ArtSource} 排队补 TMDB 素材（匀速、缓存、限次重试），
 * 抓取完成后原地更新卡片，不重建行、不抢焦点。</p>
 */
public abstract class HotCategoryActivity extends BaseActivity implements SiteListener, VodPresenter.OnClickListener {

    private static final long SHEET_ANIM = 220L;
    private static final long TOOLBAR_ANIM = 220L;
    private static final int SITE_ROWS = 8;
    private static final int SITE_ROW_DP = 30;
    private static final int SITE_NAME_LIMIT = 6;
    /** 四个平台榜容器的顺序与 HotRank.SITES 一一对应。 */
    private static final int[] HEADERS = {
            R.string.hot_rank_tencent,
            R.string.hot_rank_iqiyi,
            R.string.hot_rank_youku,
            R.string.hot_rank_mgtv
    };

    /** 打开任一频道页；带上首页的分类结果，保证「片库」入口与原页面行为一致。 */
    protected static void start(Activity activity, Class<? extends HotCategoryActivity> page, Result result) {
        Intent intent = new Intent(activity, page);
        if (result != null) intent.putExtra("result", result);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
    }

    /** 本页对应的热榜类目：HotRank.MOVIE / TV / VARIETY。 */
    protected abstract int category();

    /** 本页对应的导航胶囊 id：R.id.navMovie / navSeries / navVariety。 */
    protected abstract int navId();

    /** 主视觉条目类型，供 DoubanHotPresenter 取对应文案。 */
    protected abstract String hotType();

    private ActivityHotCategoryBinding mBinding;
    private ViewHomeToolbarBinding mToolbar;
    private ViewSiteSheetBinding mSheet;
    private ArrayObjectAdapter mAdapter;
    /** 平台榜单容器：标题资源 id → 该平台的海报行。无数据的平台不入表，整块（标题 + 网格）都不显示。 */
    private final Map<Integer, ArrayObjectAdapter> mSiteRows = new LinkedHashMap<>();
    private List<List<String>> mSiteTitles = new ArrayList<>();
    private List<DoubanHotItem> mHotItems = new ArrayList<>();
    private SiteMenuAdapter mSiteMenuAdapter;
    private ConfigMenuAdapter mConfigMenuAdapter;
    private Clock mClock;
    private Result mResult;
    private View mPendingPoster;
    private View mLastNav;
    private boolean mInitialFocusDone;
    private boolean mToolbarVisible = true;
    private boolean mContentVisited;
    /** 上一次构建时主视觉是否存在：只在「有无」发生变化时做过渡，普通刷新不闪。 */
    private boolean mHasHero;
    /** 主视觉当前内容签名：片名序列没变就不重建行，重复广播不闪。 */
    private String mHeroSignature = "";

    private final ArtSource.Listener mArtListener = this::refreshCards;

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusGuard = (oldFocus, newFocus) -> {
        if (mBinding == null || !isVisible(mSheet.getRoot())) return;
        if (isInside(newFocus, mSheet.getRoot())) return;
        focusSheetCurrent();
    };

    private final ViewTreeObserver.OnGlobalFocusChangeListener toolbarFocusWatcher =
            (oldFocus, newFocus) -> {
                if (isDescendant(newFocus, mBinding.recycler)) mContentVisited = true;
                updateToolbar(newFocus);
            };

    private final RecyclerView.OnScrollListener toolbarScrollWatcher = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            updateToolbar(getCurrentFocus());
        }
    };

    @Override
    protected ViewBinding getBinding() {
        mBinding = ActivityHotCategoryBinding.inflate(getLayoutInflater());
        mToolbar = mBinding.homeToolbar;
        mSheet = mBinding.siteSheet;
        return mBinding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mResult = getIntent().getParcelableExtra("result");
        setRecyclerView();
        setToolbar();
        applyTopScrim();
        setTitle();
        setAdapter();
        installInitialFocus();
        loadRanks();
        ArtSource.register(mArtListener);
    }

    @Override
    protected void initEvent() {
        ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
        observer.addOnGlobalFocusChangeListener(focusGuard);
        observer.addOnGlobalFocusChangeListener(toolbarFocusWatcher);
        mBinding.recycler.addOnScrollListener(toolbarScrollWatcher);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Result result = intent.getParcelableExtra("result");
        if (result != null) mResult = result;
        // 本页导航项再次点击（CLEAR_TOP + SINGLE_TOP 会走到这里）：回顶部。
        scrollBackToHero();
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        HeaderPresenter header = new HeaderPresenter();
        selector.addPresenter(Integer.class, header);
        // 栏目标题统一走 RowHeader：既能承载动态文字，也能按资源 id 稳定定位「标题 + 网格」整块增删。
        selector.addPresenter(RowHeader.class, header);
        selector.addPresenter(String.class, new ProgressPresenter());
        // 卡片自带 1.04 缩放，行容器必须关掉 Leanback 的 ZOOM_FACTOR_*（否则焦点抖动）。
        selector.addPresenter(ListRow.class, new CustomRowPresenter(Product.POSTER_SPACING_DP,
                FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ITEM, 0), VodPresenter.class);
        // 频道页片名多，主视觉不再自行触发抓取，统一走 ArtSource 的匀速队列。
        selector.addPresenter(DoubanHotRow.class, new DoubanHotPresenter(this::onDoubanHotClick, true));
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        // 主视觉恒在最底层，避免焦点行置顶把溢出的羽化层盖到标题 / 海报上。
        mBinding.recycler.setFocusDrawingOrderEnabled(false);
    }

    private void setAdapter() {
        mAdapter.add("progress");
    }

    private void loadRanks() {
        HotRank.fetch(category(), (merged, siteJson) -> App.post(() -> {
            if (mBinding == null || isFinishing() || isDestroyed()) return;
            applyRanks(merged, siteJson);
        }));
    }

    /** 把一次聚合结果拆成主视觉 10 条 + 四个平台榜，再统一排队补 TMDB。 */
    private void applyRanks(String merged, String[] siteJson) {
        mHotItems = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (Word.Data data : Word.objectFrom(merged).getData()) {
            if (TextUtils.isEmpty(data.getTitle())) continue;
            // 主视觉一律隐藏黑名单影片：确认没有素材的片子不适合当首屏推荐。
            if (ArtBlacklist.contains(data.getTitle())) continue;
            DoubanHotItem item = new DoubanHotItem();
            item.setTitle(data.getTitle());
            item.setType(hotType());
            mHotItems.add(item);
            all.add(data.getTitle());
        }
        mSiteTitles = new ArrayList<>();
        for (int i = 0; i < HEADERS.length; i++) {
            String json = siteJson == null || i >= siteJson.length ? null : siteJson[i];
            List<String> titles = new ArrayList<>();
            for (Word.Data data : Word.objectFrom(json).getData()) {
                if (!TextUtils.isEmpty(data.getTitle())) titles.add(data.getTitle());
            }
            // 综艺页覆写为 false：热榜本身就是节目单，缺素材的节目仍应能被浏览。
            if (hideBlacklistInGrid()) titles = ArtBlacklist.filterTitles(titles);
            mSiteTitles.add(titles);
            all.addAll(titles);
        }
        buildRows();
        // 四个页面共用同一套素材解析（TMDB 优先 → Douban Bridge兜底 → 失败分级处置）。
        ArtSource.enqueue(all, category());
    }

    /**
     * 海报网格是否隐藏黑名单影片。
     * 默认隐藏；综艺页覆写为 false —— 综艺热榜本身就是节目单，缺素材的节目也该能被找到，
     * 只有主视觉仍然隐藏。
     */
    protected boolean hideBlacklistInGrid() {
        return true;
    }

    /**
     * 行结构：主视觉（第 0 行，由 {@link #syncHero()} 统一管理）→ [标题 + 海报行] × n。
     *
     * <p><b>平台容器与首页「更新推荐」同一口径</b>：网格为空时整个容器一起收起，
     * 不留孤立标题、也不放「暂无数据」占位卡。行结构随数据自适应，页面不会因为
     * 某个平台缺榜而出现一排空标题。</p>
     *
     * <p>这里只重建主视觉之后的部分，不整表 clear：主视觉是异步挂上去的，
     * 整表清空会把它一起收掉，再补回来就必然闪一下。</p>
     */
    private void buildRows() {
        int start = indexOfHero() + 1;
        if (mAdapter.size() > start) mAdapter.removeItems(start, mAdapter.size() - start);
        mSiteRows.clear();
        VodPresenter presenter = new VodPresenter(this, Style.rect());
        for (int i = 0; i < HEADERS.length; i++) {
            List<String> titles = i < mSiteTitles.size() ? mSiteTitles.get(i) : Collections.emptyList();
            // 网格为空：整个容器一起收起，与首页「更新推荐」一致。
            if (titles.isEmpty()) continue;
            addSiteBlock(presenter, HEADERS[i], titles);
        }
        syncHeroAndInset();
        syncEmptyTip();
        pinToHeroIfContentUntouched();
    }

    /**
     * 主视觉可渲染条目：必须有已落盘的横版剧照。
     *
     * <p>只有片名、没有剧照时，轮播 Presenter 会把整块文字层隐藏，只留一块同色底——
     * 那就是频道页顶部的空档。所以「有没有主视觉」按剧照判断，不按榜单条数判断。</p>
     */
    private List<DoubanHotItem> heroItems() {
        List<DoubanHotItem> shown = new ArrayList<>();
        for (DoubanHotItem item : mHotItems) {
            TmdbArt art = TmdbStore.find(item.getTitle());
            if (art != null && art.hasBackdrop()) shown.add(item);
        }
        return shown;
    }

    /** 任一数据源落库：只更新卡片的图片与评分，保留行与焦点位置。 */
    private void refreshCards() {
        if (mBinding == null || isFinishing() || isDestroyed()) return;
        for (ArrayObjectAdapter row : mSiteRows.values()) {
            boolean changed = false;
            // 倒序遍历：刚被拉黑的卡片要直接移除，正序删会跳过下一项。
            for (int i = row.size() - 1; i >= 0; i--) {
                if (!(row.get(i) instanceof Vod old)) continue;
                if (hideBlacklistInGrid() && ArtBlacklist.contains(old.getName())) {
                    row.removeItems(i, 1);
                    changed = true;
                    continue;
                }
                if (ArtSource.apply(old)) changed = true;
            }
            if (changed) row.notifyArrayItemRangeChanged(0, row.size());
        }
        // 网格被拉黑清空的平台：整块（标题 + 网格）收起，与首页「更新推荐」同一口径。
        boolean shapeChanged = false;
        for (Iterator<Map.Entry<Integer, ArrayObjectAdapter>> it = mSiteRows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, ArrayObjectAdapter> entry = it.next();
            if (entry.getValue().size() > 0) continue;
            removeSiteBlock(entry.getKey());
            it.remove();
            shapeChanged = true;
        }
        syncHeroAndInset();
        // 有平台被收起时补一次空态提示：主视觉也一起没了才显示「暂无热榜数据」。
        if (shapeChanged) syncEmptyTip();
    }

    /** 挂一个平台容器：「标题 + 一行海报」，两者永远同进同出。 */
    private void addSiteBlock(VodPresenter presenter, int header, List<String> titles) {
        mAdapter.add(new RowHeader(header, getString(header)));
        ArrayObjectAdapter row = new ArrayObjectAdapter(presenter);
        List<Vod> cards = new ArrayList<>();
        for (String title : titles) cards.add(ArtSource.card(title));
        row.addAll(0, cards);
        mSiteRows.put(header, row);
        mAdapter.add(new ListRow(row));
    }

    /** 收起一个平台容器：标题与紧跟其后的海报行一起移除。 */
    private void removeSiteBlock(int header) {
        int index = indexOfHeader(header);
        if (index < 0) return;
        boolean hasRow = index + 1 < mAdapter.size() && mAdapter.get(index + 1) instanceof ListRow;
        mAdapter.removeItems(index, hasRow ? 2 : 1);
    }

    /** 主视觉与所有平台容器都为空时才给一条提示；只要有一处有内容就不显示。 */
    private void syncEmptyTip() {
        boolean empty = mSiteRows.isEmpty() && indexOfHero() < 0;
        int index = indexOfEmptyTip();
        if (empty && index < 0) mAdapter.add(R.string.hot_rank_empty);
        else if (!empty && index >= 0) mAdapter.removeItems(index, 1);
    }

    private int indexOfEmptyTip() {
        for (int i = 0; i < mAdapter.size(); i++) {
            Object item = mAdapter.get(i);
            if (item instanceof Integer && (Integer) item == R.string.hot_rank_empty) return i;
        }
        return -1;
    }

    /** 按资源 id 找栏目标题：标题统一是 {@link RowHeader}，只能按 key 比对。 */
    private int indexOfHeader(int key) {
        for (int i = 0; i < mAdapter.size(); i++) {
            Object item = mAdapter.get(i);
            if (item instanceof RowHeader && ((RowHeader) item).getKey() == key) return i;
        }
        return -1;
    }

    /** 主视觉行 + 顶部内边距一起收敛，两个调用点（数据构建、素材落库）共用。 */
    private void syncHeroAndInset() {
        boolean hasHero = syncHero();
        // 主视觉为空时列表顶部改贴工具栏下沿：下方平台容器自动上移补齐，不留空档、也不被顶栏压住。
        HeroInset.apply(mBinding.recycler, mToolbar.getRoot(), hasHero);
    }

    /**
     * 主视觉与剧照落库同步：有图挂行（没有就插入），没图收起。返回主视觉是否已挂出。
     *
     * <p>与首页 {@code applyHero} 同一套幂等逻辑：内容签名没变直接返回，重复广播不重建行；
     * 插入 / 收起时先记可视锚点，用户已在浏览内容就保持原行屏幕位置，只在冷启动未进内容区时钉回顶部。</p>
     */
    private boolean syncHero() {
        if (mBinding == null || mAdapter == null || isFinishing() || isDestroyed()) return false;
        List<DoubanHotItem> shown = heroItems();
        int index = indexOfHero();
        if (shown.isEmpty()) {
            collapseHero(index);
            return false;
        }
        String signature = heroSignature(shown);
        if (index >= 0) {
            // 内容没变直接返回：素材广播会来很多次，重复 replace 会让整行重建、闪一下。
            if (signature.equals(mHeroSignature)) return true;
            mHeroSignature = signature;
            mAdapter.replace(index, DoubanHotRow.create(shown));
            return true;
        }
        int anchorPosition = firstVisiblePosition();
        int anchorTop = firstVisibleTop();
        mAdapter.add(getDoubanHotIndex(), DoubanHotRow.create(shown));
        mHeroSignature = signature;
        restoreAnchor(anchorPosition, anchorTop, 1);
        if (!mHasHero) {
            mHasHero = true;
            // 从无到有：行整体下移一个主视觉的高度，用一次短淡入盖住重排的瞬间。
            HeroTransition.fade(mBinding.recycler);
        }
        pinToHeroIfContentUntouched();
        return true;
    }

    private int indexOfHero() {
        for (int i = 0; i < mAdapter.size(); i++) if (mAdapter.get(i) instanceof DoubanHotRow) return i;
        return -1;
    }

    /** 内容签名：片名序列一致就认为主视觉没变，避免同一批数据重复重建行。 */
    private static String heroSignature(List<DoubanHotItem> items) {
        StringBuilder sb = new StringBuilder();
        for (DoubanHotItem item : items) sb.append(item.getTitle()).append('\u0001');
        return sb.toString();
    }

    /** 收起主视觉：焦点原本停在它上面时先把焦点交给下方，避免移除后焦点丢失。 */
    private void collapseHero(int index) {
        mHeroSignature = "";
        if (index < 0) return;
        boolean focused = mBinding.recycler.getSelectedPosition() == index;
        int anchorPosition = firstVisiblePosition();
        int anchorTop = firstVisibleTop();
        mAdapter.removeItems(index, 1);
        if (mHasHero) {
            mHasHero = false;
            // 行整体上移填补空出的高度，同样用短淡入过渡，不留白也不跳。
            HeroTransition.fade(mBinding.recycler);
        }
        restoreAnchor(anchorPosition, anchorTop, -1);
        if (focused) mBinding.recycler.post(this::focusFirstContent);
    }

    /** 主视觉被收起且焦点原本停在它上面：交给下方第一行（列表已空则回到顶栏）。 */
    private void focusFirstContent() {
        if (mBinding == null || isFinishing() || isDestroyed()) return;
        if (mAdapter.size() == 0) {
            focusNav();
            return;
        }
        mBinding.recycler.scrollToPosition(0);
        mBinding.recycler.requestFocus();
    }

    private int firstVisiblePosition() {
        RecyclerView recycler = mBinding.recycler;
        if (recycler.getChildCount() == 0) return RecyclerView.NO_POSITION;
        return recycler.getChildAdapterPosition(recycler.getChildAt(0));
    }

    private int firstVisibleTop() {
        RecyclerView recycler = mBinding.recycler;
        return recycler.getChildCount() == 0 ? 0 : recycler.getChildAt(0).getTop();
    }

    /**
     * 行增删后的可视锚点复位：{@code delta} 是目标行相对原锚点的位移（前面插入 +1、删除 -1）。
     * 用户已在浏览内容时保持原行的屏幕位置不变；还没进入内容区则钉回顶部。
     */
    private void restoreAnchor(int position, int top, int delta) {
        mBinding.recycler.post(() -> {
            if (mBinding == null || isFinishing() || isDestroyed()) return;
            if (!mContentVisited && !isContentFocused()) {
                mBinding.recycler.scrollToPosition(getDoubanHotIndex());
                return;
            }
            if (position == RecyclerView.NO_POSITION) return;
            if (position + delta < 0) {
                // 被删掉的就是锚点行本身（主视觉正好在最上方）：把新的第一行对齐到
                // 补过内边距之后的顶部，既不留白，也不会钻到顶栏下面。
                mBinding.recycler.scrollToPosition(0);
                return;
            }
            int target = position + delta;
            RecyclerView recycler = mBinding.recycler;
            for (int i = 0; i < recycler.getChildCount(); i++) {
                View child = recycler.getChildAt(i);
                if (recycler.getChildAdapterPosition(child) != target) continue;
                recycler.scrollBy(0, child.getTop() - top);
                return;
            }
        });
    }

    /** 主视觉固定占列表第 0 行：平台容器与提示行都在它后面，会随它增删整体前后移动。 */
    private int getDoubanHotIndex() {
        return 0;
    }

    // ---------------------------------------------------------------- 顶栏与页面跳转

    private View nav() {
        return mToolbar.getRoot().findViewById(navId());
    }

    private void setToolbar() {
        mToolbar.navHome.setOnClickListener(v -> goHome());
        mToolbar.navMovie.setOnClickListener(v -> goCategory(MovieActivity.class));
        mToolbar.navSeries.setOnClickListener(v -> goCategory(SeriesActivity.class));
        mToolbar.navVariety.setOnClickListener(v -> goCategory(VarietyActivity.class));
        mToolbar.navVod.setOnClickListener(v -> VodActivity.start(this, mResult));
        mToolbar.navMine.setOnClickListener(v -> SettingActivity.start(this));
        mToolbar.brandEntry.setOnClickListener(v -> SearchActivity.start(this));
        mToolbar.siteEntry.setOnClickListener(v -> showSiteSheet());
        mSheet.siteScrim.setOnClickListener(v -> hideSiteSheet());
        mSheet.siteList.setLayoutManager(new LinearLayoutManager(this));
        mSheet.siteList.setItemAnimator(null);
        mSheet.siteList.setMaxHeight(ResUtil.dp2px(SITE_ROW_DP * SITE_ROWS));
        nav().setSelected(true);
        View[] navs = {mToolbar.navHome, mToolbar.navMovie, mToolbar.navSeries,
                mToolbar.navVariety, mToolbar.navVod, mToolbar.navMine};
        for (View item : navs) item.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) mLastNav = view;
        });
        // 与首页同一套判定：影片刮削关闭 / 未配置时，三个频道入口一并隐藏。
        ToolbarEntries.apply(mToolbar);
    }

    private void goHome() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void goCategory(Class<? extends HotCategoryActivity> page) {
        if (getClass() == page) {
            clearPendingPoster();
            scrollBackToHero();
            return;
        }
        start(this, page, mResult);
    }

    private void applyTopScrim() {
        mToolbar.getRoot().post(() -> {
            if (mBinding == null || isFinishing() || isDestroyed()) return;
            ViewGroup.LayoutParams lp = mBinding.topScrim.getLayoutParams();
            lp.height = mToolbar.getRoot().getBottom() + ResUtil.dp2px(5);
            mBinding.topScrim.setLayoutParams(lp);
        });
    }

    private void setTitle() {
        List<String> items = Arrays.asList(VodConfig.get().getHome().getName(),
                VodConfig.get().getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mToolbar.siteName.setText(clampSiteName(s)));
    }

    private static String clampSiteName(String name) {
        if (TextUtils.isEmpty(name)) return "";
        List<String> clusters = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(name);
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            clusters.add(name.substring(start, end));
        }
        if (clusters.size() <= SITE_NAME_LIMIT) return name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SITE_NAME_LIMIT - 1; i++) sb.append(clusters.get(i));
        return sb.append("...").toString();
    }

    // ---------------------------------------------------------------- 顶栏随焦点显隐

    private int focusedRow(View focus) {
        int fallback = mBinding.recycler.getSelectedPosition();
        View child = focus;
        while (child != null && child.getParent() != mBinding.recycler) {
            ViewParent parent = child.getParent();
            if (!(parent instanceof View)) return fallback;
            child = (View) parent;
        }
        if (child == null) return fallback;
        int position = mBinding.recycler.getChildAdapterPosition(child);
        return position == RecyclerView.NO_POSITION ? fallback : position;
    }

    private void updateToolbar(View focus) {
        if (mBinding == null || focus == null) return;
        if (isVisible(mSheet.getRoot())) {
            animateToolbar(true);
            return;
        }
        boolean belowHero = isDescendant(focus, mBinding.recycler) && focusedRow(focus) > 0;
        animateToolbar(!(belowHero && !isHeroAtTop()));
    }

    private boolean isHeroAtTop() {
        RecyclerView recycler = mBinding.recycler;
        int tolerance = ResUtil.dp2px(2);
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View child = recycler.getChildAt(i);
            if (recycler.getChildAdapterPosition(child) != 0) continue;
            return child.getTop() >= -tolerance;
        }
        return false;
    }

    private void animateToolbar(boolean visible) {
        if (mToolbarVisible == visible) return;
        mToolbarVisible = visible;
        animateTopBar(mToolbar.getRoot(), visible);
        animateTopBar(mBinding.topScrim, visible);
    }

    private void animateTopBar(View bar, boolean visible) {
        bar.animate().cancel();
        int shift = bar.getBottom() > 0 ? bar.getBottom() : bar.getHeight() + ResUtil.dp2px(16);
        bar.animate()
                .alpha(visible ? 1f : 0f)
                .translationY(visible ? 0f : -shift)
                .setDuration(TOOLBAR_ANIM)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void showToolbarImmediate() {
        mToolbarVisible = true;
        for (View bar : new View[]{mToolbar.getRoot(), mBinding.topScrim}) {
            bar.animate().cancel();
            bar.setAlpha(1f);
            bar.setTranslationY(0f);
        }
    }

    private void scrollBackToHero() {
        mBinding.recycler.scrollToPosition(0);
        animateToolbar(true);
    }

    private void pinToHeroIfContentUntouched() {
        mBinding.recycler.post(() -> {
            if (mBinding == null || isFinishing() || isDestroyed()) return;
            if (mContentVisited || isDescendant(getCurrentFocus(), mBinding.recycler)) return;
            mBinding.recycler.scrollToPosition(0);
        });
    }

    // ---------------------------------------------------------------- 站源下拉（与首页同款）

    private void showSiteSheet() {
        if (isVisible(mSheet.getRoot())) return;
        showToolbarImmediate();
        List<Site> sites = VodConfig.get().getSites().stream().filter(site -> !site.isHide()).toList();
        if (sites.isEmpty()) return;
        mSiteMenuAdapter = new SiteMenuAdapter(sites, VodConfig.get().getHome(), new SiteMenuAdapter.OnClickListener() {
            @Override
            public void onItemClick(Site item) {
                setSite(item);
            }

            @Override
            public void onSwitchClick() {
                showConfigSheet();
            }
        });
        mSheet.siteList.setAdapter(mSiteMenuAdapter);
        mSheet.configPanel.animate().cancel();
        mSheet.configPanel.setVisibility(View.GONE);
        mSheet.getRoot().setVisibility(View.VISIBLE);
        mSheet.sitePanel.setAlpha(0f);
        mSheet.siteScrim.setAlpha(0f);
        mSheet.getRoot().bringToFront();
        mToolbar.siteArrow.setRotation(180f);
        mToolbar.siteEntry.post(() -> {
            if (!isVisible(mSheet.getRoot())) return;
            positionSheet();
            mSheet.getRoot().requestFocus();
            mSheet.siteList.scrollToPosition(mSiteMenuAdapter.indexOfCurrent());
            animatePanelOpen(mSheet.sitePanel, mSheet.siteScrim);
        });
    }

    private void animatePanelOpen(View panel, View scrim) {
        panel.animate().cancel();
        if (scrim != null) {
            scrim.animate().cancel();
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(SHEET_ANIM).start();
        }
        panel.setPivotY(0f);
        panel.setAlpha(0f);
        panel.setTranslationY(-ResUtil.dp2px(12));
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).translationY(0f).scaleY(1f)
                .setDuration(SHEET_ANIM).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void positionSheet() {
        int[] entry = new int[2];
        int[] root = new int[2];
        mToolbar.siteEntry.getLocationOnScreen(entry);
        mBinding.getRoot().getLocationOnScreen(root);
        int right = entry[0] - root[0] + mToolbar.siteEntry.getWidth();
        int marginEnd = Math.max(0, mBinding.getRoot().getWidth() - right);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mSheet.sitePanel.getLayoutParams();
        lp.topMargin = entry[1] - root[1] + mToolbar.siteEntry.getHeight() + ResUtil.dp2px(6);
        lp.rightMargin = marginEnd;
        float textWidth = mSiteMenuAdapter.maxTextWidth(mToolbar.siteName.getPaint());
        textWidth = Math.max(textWidth, mToolbar.siteName.getPaint().measureText(getString(R.string.site_switch_config)));
        int width = (int) Math.ceil(textWidth) + ResUtil.dp2px(44);
        int maxWidth = Math.max(ResUtil.dp2px(140), mBinding.getRoot().getWidth() - marginEnd - ResUtil.dp2px(48));
        lp.width = Math.min(Math.max(width, ResUtil.dp2px(140)), maxWidth);
        mSheet.sitePanel.setLayoutParams(lp);
    }

    private void positionConfigSheet() {
        FrameLayout.LayoutParams site = (FrameLayout.LayoutParams) mSheet.sitePanel.getLayoutParams();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mSheet.configPanel.getLayoutParams();
        lp.topMargin = site.topMargin;
        lp.rightMargin = site.rightMargin + site.width + ResUtil.dp2px(8);
        int maxWidth = Math.max(ResUtil.dp2px(140), mBinding.getRoot().getWidth() - lp.rightMargin - ResUtil.dp2px(24));
        lp.width = Math.min(ResUtil.dp2px(240), maxWidth);
        mSheet.configPanel.setLayoutParams(lp);
    }

    private void showConfigSheet() {
        if (isVisible(mSheet.configPanel)) {
            hideConfigSheet();
            return;
        }
        List<Config> configs = Config.getAll(Config.VOD);
        if (configs.isEmpty()) {
            Notify.show(R.string.site_switch_empty);
            return;
        }
        mConfigMenuAdapter = new ConfigMenuAdapter(configs, VodConfig.get().getConfig(), this::setConfig);
        mSheet.configList.setLayoutManager(new LinearLayoutManager(this));
        mSheet.configList.setItemAnimator(null);
        mSheet.configList.setAdapter(mConfigMenuAdapter);
        mSheet.configPanel.setVisibility(View.VISIBLE);
        mSheet.configPanel.setAlpha(0f);
        mSheet.configList.post(() -> {
            if (!isVisible(mSheet.configPanel)) return;
            positionConfigSheet();
            mSheet.configList.scrollToPosition(mConfigMenuAdapter.indexOfCurrent());
            RecyclerView.ViewHolder holder = mSheet.configList.findViewHolderForAdapterPosition(mConfigMenuAdapter.indexOfCurrent());
            if (holder != null) holder.itemView.requestFocus();
            else mSheet.configPanel.requestFocus();
            animatePanelOpen(mSheet.configPanel, null);
        });
    }

    private void hideConfigSheet() {
        if (!isVisible(mSheet.configPanel)) return;
        mSheet.configPanel.animate().cancel();
        mSheet.configPanel.setVisibility(View.GONE);
        RecyclerView.ViewHolder holder = mSheet.siteList.findViewHolderForAdapterPosition(0);
        if (holder != null) holder.itemView.requestFocus();
        else mSheet.sitePanel.requestFocus();
    }

    private void setConfig(Config item) {
        VodConfig.load(item, new Callback() {
            @Override
            public void start() {
                Notify.progress(HotCategoryActivity.this);
            }

            @Override
            public void success() {
                Notify.dismiss();
                hideSiteSheet();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                if (!TextUtils.isEmpty(msg)) Notify.show(msg);
                hideConfigSheet();
            }
        });
    }

    private void hideSiteSheet() {
        if (!isVisible(mSheet.getRoot())) return;
        mSheet.sitePanel.animate().cancel();
        mSheet.siteScrim.animate().cancel();
        mSheet.configPanel.animate().cancel();
        mSheet.configPanel.setVisibility(View.GONE);
        mSheet.getRoot().setVisibility(View.GONE);
        mToolbar.siteArrow.setRotation(0f);
        mToolbar.siteEntry.requestFocus();
    }

    private boolean isInside(View view, View container) {
        if (view == null || container == null) return false;
        if (view == container) return true;
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent == container) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private void focusSheetCurrent() {
        mSheet.siteList.post(() -> {
            if (!isVisible(mSheet.getRoot())) return;
            RecyclerView.ViewHolder holder = mSiteMenuAdapter == null ? null
                    : mSheet.siteList.findViewHolderForAdapterPosition(mSiteMenuAdapter.indexOfCurrent());
            if (holder != null) holder.itemView.requestFocus();
            else mSheet.getRoot().requestFocus();
        });
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
        clearPendingPoster();
        setTitle();
        hideSiteSheet();
    }

    // ---------------------------------------------------------------- 交互与事件

    @Override
    public void onItemClick(Vod item) {
        if (!TextUtils.isEmpty(item.getName())) CollectActivity.start(this, item.getName());
    }

    @Override
    public boolean onLongClick(Vod item) {
        onItemClick(item);
        return true;
    }

    private void onDoubanHotClick(DoubanHotItem item) {
        if (item != null && !TextUtils.isEmpty(item.getTitle())) CollectActivity.start(this, item.getTitle());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        // 海报尺寸档位变化：卡片宽度来自 Product.getSpec()，需要按新尺寸重建行。
        if (event.getType() == RefreshEvent.Type.SIZE && !mSiteTitles.isEmpty()) buildRows();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.VOD) setTitle();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && !isVisible(mSheet.getRoot())) {
            if (KeyUtil.isDownKey(event) && isToolbarFocused() && restorePendingPoster()) return true;
            if (KeyUtil.isUpKey(event) && isFirstContentRow() && mBinding.recycler.hasFocus()) {
                focusNav();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * 焦点是否已经在内容区的第一行。
     *
     * <p>不能用 {@code getSelectedPosition() == 0}：主视觉收起后第 0 行是不可聚焦的栏目标题，
     * 真正的第一行是位置 1，那样按上键就回不到顶栏了。所以按「主视觉在不在」分别判断。</p>
     */
    private boolean isFirstContentRow() {
        int row = focusedRow(getCurrentFocus());
        if (row < 0) return false;
        return row == getDoubanHotIndex() || (indexOfHero() < 0 && row == getDoubanHotIndex() + 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页返回时刮削配置可能已变，频道入口的显隐要重新收敛。
        ToolbarEntries.apply(mToolbar);
        if (mClock != null) mClock.stop();
        mClock = Clock.create().view(mToolbar.clock).format("HH:mm");
        mClock.start();
        if (mInitialFocusDone && isNavFocused() && !nav().hasFocus()) nav().requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mClock != null) mClock.stop();
    }

    private void installInitialFocus() {
        View anchor = mToolbar.getRoot();
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                anchor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                requestInitialFocus();
            }
        });
    }

    private void requestInitialFocus() {
        if (mBinding == null || mInitialFocusDone) return;
        if (nav().requestFocus()) {
            mInitialFocusDone = true;
            return;
        }
        nav().post(() -> {
            if (mBinding == null) return;
            mInitialFocusDone = nav().requestFocus() || mBinding.recycler.requestFocus();
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || mBinding == null) return;
        if (!mInitialFocusDone) {
            requestInitialFocus();
            return;
        }
        if (isNavFocused() && !nav().hasFocus()) nav().requestFocus();
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mSheet.configPanel)) {
            hideConfigSheet();
        } else if (isVisible(mSheet.getRoot())) {
            hideSiteSheet();
        } else if (mBinding.progressLayout.isProgress()) {
            mBinding.progressLayout.showContent();
        } else if (isContentFocused()) {
            View poster = getCurrentFocus();
            if (focusedRow(poster) > 0) {
                clearPendingPoster();
                mLastNav = nav();
                nav().requestFocus();
                scrollBackToHero();
            } else {
                focusNav();
                mPendingPoster = poster;
            }
        } else if (isToolbarActionFocused()) {
            focusNav();
        } else if (nav().hasFocus()) {
            if (!isAtTop()) scrollBackToHero();
            else finish();
        } else if (isNavFocused()) {
            finish();
        } else if (!isAtTop()) {
            scrollBackToHero();
        } else {
            finish();
        }
    }

    /** 列表是否已滚到最顶部：第 0 行（主视觉，或主视觉收起后的第一个栏目标题）贴住列表上沿。 */
    private boolean isAtTop() {
        if (mBinding.recycler.getChildCount() == 0) return true;
        return isHeroAtTop();
    }

    private boolean isContentFocused() {
        return isDescendant(getCurrentFocus(), mBinding.recycler);
    }

    private boolean isToolbarActionFocused() {
        View focus = getCurrentFocus();
        return isDescendant(focus, mToolbar.brandEntry) || isDescendant(focus, mToolbar.siteEntry);
    }

    private boolean isNavFocused() {
        return isDescendant(getCurrentFocus(), mToolbar.navButtons);
    }

    private boolean isToolbarFocused() {
        return mToolbar.getRoot().hasFocus();
    }

    private static boolean isDescendant(View child, View parent) {
        if (child == null || parent == null) return false;
        View view = child;
        while (view != null) {
            if (view == parent) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    private void focusNav() {
        View target = mLastNav != null && mLastNav.isShown() ? mLastNav : nav();
        // 本页的导航项可能因为刮削关闭被隐藏：退回「首页」，别把焦点丢在不可见控件上。
        if (target == null || !target.isShown()) target = mToolbar.navHome;
        target.requestFocus();
    }

    private boolean restorePendingPoster() {
        View target = mPendingPoster;
        if (target == null) return false;
        mPendingPoster = null;
        return target.isAttachedToWindow() && target.isShown() && target.requestFocus();
    }

    private void clearPendingPoster() {
        mPendingPoster = null;
    }

    @Override
    protected void onDestroy() {
        ArtSource.unregister(mArtListener);
        ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
        observer.removeOnGlobalFocusChangeListener(focusGuard);
        observer.removeOnGlobalFocusChangeListener(toolbarFocusWatcher);
        mBinding.recycler.removeOnScrollListener(toolbarScrollWatcher);
        super.onDestroy();
    }
}

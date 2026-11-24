package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.Intent;
import android.icu.text.BreakIterator;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.splashscreen.SplashScreen;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.TmdbStore;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.node.NodeRuntime;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.ViewAllItem;
import com.fongmi.android.tv.bean.DoubanHotItem;
import com.fongmi.android.tv.bean.DoubanHotRow;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.TmdbArt;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.RowHeader;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.databinding.ViewHomeToolbarBinding;
import com.fongmi.android.tv.databinding.ViewSiteSheetBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.FocusGlowEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.playback.ExternalPlayback;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.TmdbSetting;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.adapter.ConfigMenuAdapter;
import com.fongmi.android.tv.ui.adapter.SiteMenuAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.HeroInset;
import com.fongmi.android.tv.ui.custom.HomeHeroBackdropView;
import com.fongmi.android.tv.ui.custom.HeroTransition;
import com.fongmi.android.tv.ui.custom.PageGlowComposeView;
import com.fongmi.android.tv.ui.custom.PosterStackView;
import com.fongmi.android.tv.ui.custom.StackRowSelector;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.presenter.PosterStackPresenter;
import com.fongmi.android.tv.ui.presenter.DoubanHotPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.ColdTrace;
import com.fongmi.android.tv.utils.Disclaimer;
import com.fongmi.android.tv.utils.ArtBlacklist;
import com.fongmi.android.tv.utils.DoubanHot;
import com.fongmi.android.tv.utils.HomeBoard;
import com.fongmi.android.tv.utils.ArtSource;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.RankLatest;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class HomeActivity extends BaseActivity implements SiteListener, VodPresenter.OnClickListener, HistoryPresenter.OnClickListener {

    private static final String STATE_ACTION_HANDLED = "action_handled";
    /** sheet 打开时的弹出渐变时长。 */
    private static final long SHEET_ANIM = 220L;
    /** 顶部导航栏（工具栏 + 渐变遮罩）显示 / 隐藏的过渡时长。 */
    private static final long TOOLBAR_ANIM = 220L;
    /** 站源下拉可完整显示的<b>站源行数</b>（不含「点播地址切换」「站点编辑」两个固定入口）。 */
    private static final int SITE_ROWS = 8;
    /** 列表项行高，对应 adapter_site_menu.xml 与 adapter_site_menu_switch.xml 的 30dp。 */
    private static final int SITE_ROW_DP = 30;
    /** 只有「站点编辑」那一行显示的分割线：1dp 线 + 上下各 2dp 外边距。 */
    private static final int SITE_DIVIDER_DP = 1;
    private static final int SITE_DIVIDER_MARGIN_DP = 2;
    /** 站源名字符预算：emoji 按 1 个字符计，截断「...」整体按 1 个字符计。 */
    private static final int SITE_NAME_LIMIT = 6;
    /** 「首页」按钮连按两次退出的时间窗口（毫秒），窗口内再按一次才真正退出。 */
    private static final long BACK_EXIT_WINDOW = 2000L;
    private static volatile int instanceCount;

    public static boolean isRunning() {
        return instanceCount > 0;
    }

    private ActivityHomeBinding mBinding;
    /** 整页背景漫反射绘制叶子：焦点卡主色经事件总线上报后驱动全屏柔光。 */
    private PageGlowComposeView mPageGlow;
    /**
     * 顶栏与站源面板已抽成可复用布局（view_home_toolbar / view_site_sheet），
     * 分类页 VodActivity 也 include 同一份 XML；这里通过 include 的 binding 访问子控件，
     * 保证两个页面只维护一份样式。
     */
    private ViewHomeToolbarBinding mToolbar;
    private ViewSiteSheetBinding mSheet;
    /** 收起动画进行中：焦点守卫让位，且允许被下一次展开抢占（此时面板仍是 VISIBLE）。 */
    private boolean sheetClosing;
    private boolean configClosing;
    private ArrayObjectAdapter mHistoryAdapter;
    private ArrayObjectAdapter mAdapter;
    private SiteMenuAdapter mSiteMenuAdapter;
    private ConfigMenuAdapter mConfigMenuAdapter;
    private HistoryPresenter mPresenter;
    private VodPresenter mVodPresenter;
    private SiteViewModel mViewModel;
    private Clock mClock;
    private Intent pendingAction;
    private boolean actionReady;
    private Result mResult;
    /**
     * 返回前聚焦的那张海报。焦点收回导航栏后，再按 ↓ 优先回到它，
     * 而不是列表的默认选中项（与 tv-latest 的 mPendingPoster 同一套语义）。
     */
    private View mPendingPoster;
    /** 最后一次聚焦的导航文字按钮：内容区 / 品牌行按返回时焦点回到它。 */
    private View mLastNav;
    /** 「首页」按钮连按两次退出的计时起点；任何一次中间层返回都会归零。 */
    private long mLastBackAt;
    /** 首次窗口焦点是否已纠正到「首页」：只生效一次，避免从其它页面返回时抢走用户焦点。 */
    private boolean mInitialFocusDone;
    /** 顶栏当前是否可见：只作动画去重，避免同一状态反复启动属性动画。 */
    private boolean mToolbarVisible = true;
    /**
     * 用户是否进入过内容区（列表）：主视觉异步插入后是否需要把视口钉回顶部，
     * 用「是否已经浏览过」区分冷启动与浏览中途刷新，避免把用户从下面拽回首页。
     */
    private boolean mContentVisited;
    /** 主视觉当前内容签名：数据没变就不重建行，避免「缓存回填 + 后台刷新」两次回调各插一次而闪烁。 */
    private String mHeroSignature = "";
    /** 最近一次热榜结果（已去黑名单）：剧照晚到时用它重新核算主视觉，不必重打榜单接口。 */
    private List<DoubanHotItem> mHeroItems = new ArrayList<>();
    /** 海报网格素材落库后就地刷新，与首页其它行共用同一套解析与展示逻辑。 */
    private final ArtSource.Listener mArtListener = this::refreshArt;
    /**
     * 首页四个分类模块（豆瓣热门电影 / 豆瓣热门剧集 / TMDB今日电影 / TMDB今日剧集）。
     *
     * <p>按数组下标对应 {@link HomeBoard} 的四个分类；每格保存标题对象与内容行对象，
     * 用对象身份定位，避免「推荐块重建 / 主视觉异步插入」导致下标错位。空列表时置 null 表示该行已收起。</p>
     */
    private final RowHeader[] mBoardHeaders = new RowHeader[HomeBoard.COUNT];
    private final ListRow[] mBoardRows = new ListRow[HomeBoard.COUNT];
    /** 四个分类共用一个 Presenter；与站源行分开，站源 Style 变化时不影响分类行的海报样式。 */
    private VodPresenter mBoardPresenter;
    /** 「来自 · 站源名」的完整结果（已去黑名单）：首页只展示前 10 部，其余给「更多影片」页。 */
    private List<Vod> mRecommendItems = new ArrayList<>();
    private Style mRecommendStyle = Style.rect();
    /** 列表样式下的「查看全部」入口：列表卡没有海报位，用一个哑条目代替类海报按钮。 */
    private Vod mRecommendMore;
    /**
     * 当前生效的海报尺寸档位（{@link PlayerSetting#getSize()}）。
     *
     * <p>卡片尺寸由 {@link Product#getSpec} 在 presenter 构造时算好、写进 holder 的
     * LayoutParams，已挂出的行不会自己重新测量。从设置页改档位返回时必须据此识别并重建，
     * 否则只有重启应用才生效。</p>
     */
    private int mPosterSize = -1;
    /** 免责声明同意后才会置位：未置位时首页不显示、不加载任何数据。 */
    private boolean mHomeStarted;
    /**
     * 分类数据的启动延迟：主视觉的榜单请求在 initView 里就已经发出，
     * 四个分类晚 800ms 再拉，避免首屏关键请求被这四份 JSON 抢带宽。
     */
    private static final long BOARD_DELAY_MS = 800;

    /**
     * 站源 sheet 显示期间的焦点守卫：一旦焦点落到面板之外（例如布局变化导致系统重新分配焦点），
     * 立刻拉回当前站源项。方向键的焦点搜索由 FocusTrapFrameLayout 截断，这里是第二道兜底。
     * 原品牌功能 sheet 已移除，现在只剩这一个可展开的浮层。
     */
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusGuard = (oldFocus, newFocus) -> {
        if (mBinding == null || sheetClosing || !isVisible(mSheet.getRoot())) return;
        if (isInside(newFocus, mSheet.getRoot())) return;
        focusSheetCurrent();
    };

    /**
     * 顶栏显隐守卫：焦点落到主视觉行<b>以下</b>且列表确实滚离主视觉时收起顶部导航栏，
     * 回到主视觉行 / 工具栏 / 列表顶部时恢复。
     *
     * <p>最终状态由 {@link #updateToolbar(View)} 单点计算：焦点所在行 + 列表滚动位置 + sheet
     * 三者共同决定。焦点变化与列表滚动都会触发重算，因此「焦点回到首页但列表还停在下面」
     * 或「列表滚回顶部但焦点还没挪走」这类中间态都会收敛到同一个正确结果。</p>
     */
    private final ViewTreeObserver.OnGlobalFocusChangeListener toolbarFocusWatcher =
            (oldFocus, newFocus) -> {
                // 焦点一旦进过列表就认为用户开始浏览：之后再插入主视觉不再回拽视口。
                boolean inContent = isDescendant(newFocus, mBinding.recycler);
                if (inContent) mContentVisited = true;
                /*
                 * 只在焦点「明确落到内容区之外」时熄灭。newFocus == null 是行末滚动重组的
                 * 中间态（焦点短暂被回收），此时若发 clear，随后到达的异步提色回调又会被
                 * onFocusGlowEvent 的守卫挡下，光就永久熄灭 —— 表现为只有最右那张卡不亮。
                 */
                if (newFocus != null && !inContent && mPageGlow != null) FocusGlowEvent.clear(System.identityHashCode(this));
                /*
                 * 焦点切到任意内容卡（含不发主色事件的「查看全部」堆叠按钮）都按当前焦点
                 * 校正一次几何：提色是异步的，单靠 Holder 上报会把光圈滞留在上一张卡。
                 * 「查看全部」本身不提色，若只靠滚动回调，快速横移到行末时光圈不会归位。
                 */
                if (inContent) scheduleGlowCenter();
                updateToolbar(newFocus);
            };

    /** 延后一帧按当前焦点重新取几何，去重后每帧至多一次。 */
    private void scheduleGlowCenter() {
        if (mBinding == null) return;
        mBinding.recycler.removeCallbacks(glowCenterTask);
        mBinding.recycler.post(glowCenterTask);
    }

    /**
     * 单一 post 任务：scrim 下沿 + 焦点卡圆心一帧内一起算完。
     *
     * <p>滚动 / 焦点 / 提色回调以前各自 post 一次几何校正（scrim 下沿还在 onScrolled 里
     * 同步再算一遍），同一次滚动被多条监听重复驱动。现在统一收敛到这个任务：
     * removeCallbacks + post 保证每帧至多跑一次，等值判断再挡掉坐标没变的无意义写入。</p>
     */
    private final Runnable glowCenterTask = () -> {
        updatePageGlowEdge();
        updatePageGlowCenter();
    };

    private final RecyclerView.OnScrollListener toolbarScrollWatcher = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            updateToolbar(getCurrentFocus());
            /*
             * 局部提亮的圆心与 scrim 下沿都延后一帧再取：滚动途中焦点往往还没切到新卡，
             * 立刻取会拿到旧卡位置，表现为光晕先跟着旧卡"漂"一段再跳回来。
             * 下一帧时焦点已稳定，取到的才是真正的焦点卡。
             */
            recyclerView.removeCallbacks(glowCenterTask);
            recyclerView.post(glowCenterTask);
        }

        @Override
        public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
            // 滚动彻底停下时再补一次：最后一帧的 post 可能早于焦点归位，
            // 少了这一下，光晕就会停在半路（实测下移一整行后 cy 卡在 0.86 不再更新）。
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                recyclerView.removeCallbacks(glowCenterTask);
                recyclerView.post(glowCenterTask);
            }
        }
    };

    /**
     * 滚动时按焦点卡当前真实位置校正局部提亮的圆心与范围。
     *
     * <p>主色只在提色成功那一刻上报一次，滚动过程中不会重发；若不在这里同步几何，
     * 上下切换行时背景光就会"漂"到上/下一层——这正是用户看到的现象。</p>
     */
    private void updatePageGlowCenter() {
        if (mPageGlow == null || mBinding == null) return;
        View focus = getCurrentFocus();
        if (focus == null || focus == mBinding.recycler || !isDescendant(focus, mBinding.recycler)) return;
        /*
         * 先收敛到「卡片 itemView」：父级是行内 HorizontalGridView（RecyclerView）的那一层。
         * 绝不能继续向上到行容器再 findViewById(R.id.poster) —— 首页的一级子是「行」，
         * 在行容器里搜 poster 会命中该行第一张卡。焦点落在行末「查看全部」堆叠按钮时
         * （它内部没有 R.id.poster），旧逻辑就是这样一路升到行容器、把光晕钉死在行首的。
         */
        View card = focus;
        ViewParent parent = null;
        while (card != null && card != mBinding.recycler) {
            parent = card.getParent();
            if (parent instanceof RecyclerView) break;
            card = parent instanceof View ? (View) parent : null;
        }
        // 父级是外层 recycler 说明升到了行容器（主视觉行没有行内 RecyclerView），
        // 这时保持上一帧几何，不去行里搜 poster，否则又会钉到行首。
        if (card == null || card == mBinding.recycler || parent == mBinding.recycler) return;
        /*
         * 只在这张卡片内部取画框。「查看全部」是 PosterStackView（id=stack），没有
         * R.id.poster，改用它的画框容器 layers —— 尺寸与同行海报画框完全一致，
         * 光晕就能正确落在按钮上而不是行首第一张海报。
         */
        View stack = card.findViewById(R.id.stack);
        View target = stack instanceof PosterStackView
                ? ((PosterStackView) stack).getGlowFrame()
                : card.findViewById(R.id.poster);
        if (target == null) return;
        if (target.getWidth() <= 0 || target.getHeight() <= 0) return;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        if (screenW <= 0 || screenH <= 0) return;
        int[] loc = new int[2];
        target.getLocationOnScreen(loc);
        mPageGlow.setCenter(
                (loc[0] + target.getWidth() / 2f) / screenW,
                (loc[1] + target.getHeight() / 2f) / screenH,
                target.getWidth() / (float) screenW,
                target.getHeight() / (float) screenH);
    }

    /**
     * 把主视觉羽化 scrim 的绘制下沿换算成整屏归一化 y，交给整页背景光层作为渐入起点。
     *
     * <p>scrim 末端是纯不透明 #101114，会挡住光层；光必须严格从这条边之下才渐入，
     * 交界处光强为 0、上下同为页面底色，才不会出现水平断层。主视觉行随列表滚动，
     * 所以每次滚动 / 焦点变化都要重算；行还没布局时退回 0（整屏可画，此时也无挡板）。</p>
     */
    private void updatePageGlowEdge() {
        if (mPageGlow == null || mBinding == null) return;
        int hero = indexOfHero();
        RecyclerView.ViewHolder holder = hero >= 0 ? mBinding.recycler.findViewHolderForAdapterPosition(hero) : null;
        View row = holder == null ? null : holder.itemView;
        HomeHeroBackdropView backdrop = row == null ? null : row.findViewById(R.id.backdrop);
        if (backdrop == null || backdrop.getHeight() <= 0) {
            mPageGlow.setScrimEdge(0f);
            return;
        }
        int screenH = ResUtil.getScreenHeight(this);
        if (screenH <= 0) return;
        mPageGlow.setScrimEdge((float) backdrop.getScrimBottomOnScreen() / screenH);
    }

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding() {
        mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
        mToolbar = mBinding.homeToolbar;
        mSheet = mBinding.siteSheet;
        return mBinding;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingAction = intent;
        consumeAction();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != ExternalPlayback.REQUEST_CODE) return;
        setResult(resultCode, data);
        finish();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ColdTrace.mark("home.onCreate.start");
        instanceCount++;
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        ColdTrace.mark("home.onCreate.end");
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        ColdTrace.mark("home.initView.start");
        if (savedInstanceState == null || !savedInstanceState.getBoolean(STATE_ACTION_HANDLED)) pendingAction = getIntent();
        mResult = Result.empty();
        // 整页背景漫反射：挂到根 FrameLayout 的 pageGlow 占位容器，画在所有内容之下。
        mPageGlow = new PageGlowComposeView(this);
        mBinding.pageGlow.addView(mPageGlow.getView(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mBinding.progressLayout.showProgress();
        ColdTrace.mark("home.updater.start");
        setRecyclerView();
        setToolbar();
        applyTopScrim();
        setViewModel();
        setAdapter();
        mPosterSize = PlayerSetting.getSize();
        ColdTrace.mark("home.views.built");
        if (Setting.isDiagAccepted()) {
            startHome();
        } else {
            // 声明未同意前整页不可见、数据不加载；弹窗在 onResume 里弹出（窗口已就绪）。
            mBinding.getRoot().setVisibility(View.INVISIBLE);
        }
        ColdTrace.mark("home.initView.end");
    }

    /**
     * 首页启动链路：检查更新、拉主视觉与四个分类、注册素材监听、放首帧焦点与启动画面。
     *
     * <p>单独成方法是为了让「免责声明未同意」时整条链路不执行 —— 声明弹窗关闭前
     * 主界面既不显示也不发请求；同意后从这里一次性补上，行为与冷启动完全一致。</p>
     */
    private void startHome() {
        if (mBinding == null || mHomeStarted || isFinishing() || isDestroyed()) return;
        mHomeStarted = true;
        mBinding.getRoot().setVisibility(View.VISIBLE);
        Updater.get().start(this, false);
        ArtSource.register(mArtListener);
        checkPermission();
        loadDoubanHot();
        // 四个分类模块跟在主视觉之后拉取，命中缓存时会立即挂行。
        App.post(this::loadBoards, BOARD_DELAY_MS);
        ColdTrace.mark("home.hero.requested");
        installInitialFocus();
        // 启动画面：静态品牌 banner（原始尺寸 50%、等比居中）显示片刻后自动淡出。
        mBinding.splashBrand.play();
        startClock();
    }

    @Override
    protected void initEvent() {
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mPresenter.isDelete()) setHistoryDelete(false);
            }
        });
        ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
        observer.addOnGlobalFocusChangeListener(focusGuard);
        observer.addOnGlobalFocusChangeListener(toolbarFocusWatcher);
        // 冷启动埋点：首页第一帧真正进入绘制（含启动画面）的时刻。
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver current = mBinding == null ? null : mBinding.getRoot().getViewTreeObserver();
                if (current != null && current.isAlive()) current.removeOnPreDrawListener(this);
                ColdTrace.mark("home.firstFrame");
                return true;
            }
        });
        mBinding.recycler.addOnScrollListener(toolbarScrollWatcher);
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_VIEW.equals(intent.getAction())) {
            // 直播已移除：外部推送的直播源不再处理，点播源不受影响。
            ExternalPlayback.open(this, intent);
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void consumeAction() {
        if (!actionReady || pendingAction == null) return;
        Intent intent = pendingAction;
        pendingAction = null;
        checkAction(intent);
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        HeaderPresenter header = new HeaderPresenter();
        selector.addPresenter(Integer.class, header);
        // 「来自 · 站源名」这类运行时拼接的标题走 RowHeader，其它标题仍是字符串资源 id。
        selector.addPresenter(RowHeader.class, header);
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        // 卡片自带 1.04 倍聚焦缩放，必须关掉 Leanback 的 ZOOM_FACTOR_*，否则两套缩放
        // 叠加会让焦点左右切换时抖动。行内 grid 左右 padding 显式归零（末参 0），
        // 页面级 48dp 安全区由 recycler 提供，卡片宽度也按双边 96dp 计算，正好铺满不溢出。
        // 「更新推荐」用 FOCUS_SCROLL_ITEM（只在贴边时滚动），「最近观看」保留 ALIGNED。
        selector.addPresenter(ListRow.class, new CustomRowPresenter(Product.POSTER_SPACING_DP, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ITEM, 0), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(Product.POSTER_SPACING_DP, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ALIGNED, 0), HistoryPresenter.class);
        // 首页主视觉：不是 ListRow，而是整行单条的自定义 Presenter，
        // 与参照实现（FeaturedVodRow / FeaturedVodPresenter）同构。
        selector.addPresenter(DoubanHotRow.class, new DoubanHotPresenter(this::onDoubanHotClick));
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        /*
         * 关闭 Leanback 的「焦点行置顶」绘制顺序，固定为按 adapter 顺序绘制。
         *
         * BaseGridView 默认 setChildrenDrawingOrderEnabled(true)，并由
         * GridLayoutManager.getChildDrawingOrder() 把 mFocusPosition 对应的行挪到
         * 最后一个绘制（最上层），目的是让焦点行的放大高亮不被相邻行压住。
         * 但首页 mFocusPosition 恒为 0（主视觉行），于是主视觉反而被提到所有行之上；
         * 它会向下溢出 HOME_BOTTOM_BLEED_DP 画一层不透明的 #101114 羽化 scrim，
         * 正好盖住 index 1「最近观看」标题（无背景的透明 TextView）和 index 2 海报顶部。
         *
         * 关掉后子 View 按 0,1,2… 顺序绘制：主视觉恒在最底层，溢出段只作为
         * 「标题与海报背后的底色」；标题文字、海报图片后画，天然压在它之上。
         * 该顺序由列表结构决定，与焦点位置、滚动位置、屏幕尺寸/density 无关。
         *
         * 行间距 20dp 远大于海报聚焦放大幅度（PosterFocusAnimator 的 1.04 倍），且 recycler
         * 已 clipChildren=false，关闭置顶不会造成焦点卡片边缘被相邻行裁掉。
         */
        mBinding.recycler.setFocusDrawingOrderEnabled(false);

        /*
         * 行内横向卷动补一次圆心校正。
         *
         * 横向滚动发生在每行的 HorizontalGridView 上，**不会触发 recycler 的 onScrolled**，
         * 只靠焦点回调上报的坐标会停在“按下方向键那一刻”，而卡片随后仍被行卷动带着走——
         * 这就是快速横移到最右、再折返左侧时看到的光晕脱节。
         * ViewTreeObserver 能兜住整棵树里任何 View 的滚动，统一延后一帧按焦点卡真实位置校正。
         */
        mBinding.recycler.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (mBinding == null) return;
            mBinding.recycler.removeCallbacks(glowCenterTask);
            mBinding.recycler.post(glowCenterTask);
        });
    }

    /**
     * 顶部工具栏：单行、行高 64dp；导航与站源名 16sp 加粗，时钟 18sp（全局统一字号）。
     * 自左至右为：搜索圆钮（放大镜）→ 3 个导航 → 站源入口 → 时钟。
     *
     * <p>导航文字按钮按序为「首页 / 片库 / 我的」共 <b>3</b> 项：
     * 「首页」刷新首页内容并回到列表顶部；「片库」进 VodActivity；「我的」进设置页。
     * 左右走位未写死 nextFocusLeft/Right，完全由布局顺序决定；「首页」仍是 index 0，
     * 启动 / setFocus() / 返回「回首页」都指向它。</p>
     *
     * <p>选中态：导航项统一使用 36dp 高胶囊（selector_nav_button）。聚焦 = 纯白底 + 黑字；
     * <b>当前所在页</b>未聚焦 = 50% 透明黑底 + 白字（{@link #mToolbar}.navHome.setSelected(true)）；
     * 其余 = 透明底 + 白字。左侧原本的品牌 logo 位改成<b>放大镜圆钮</b>
     * （{@code brandEntry}，点击进 SearchActivity），原搜索框按钮已移除。</p>
     */
    private void setToolbar() {
        mToolbar.navHome.setOnClickListener(v -> refreshHome());
        mToolbar.navVod.setOnClickListener(v -> VodActivity.start(this, mResult));
        mToolbar.navMine.setOnClickListener(v -> SettingActivity.start(this, mResult));
        mToolbar.siteEntry.setOnClickListener(v -> showSiteSheet());
        mSheet.siteScrim.setOnClickListener(v -> hideSiteSheet());
        mSheet.siteList.setLayoutManager(new LinearLayoutManager(this));
        mSheet.siteList.setItemAnimator(null);
        // 8 行站源 + 2 个固定入口，正好整行数，超出部分在面板内滚动。
        mSheet.siteList.setMaxHeight(siteListHeight());
        // 搜索圆钮（放大镜，占原品牌位）：点击 / 确认键进搜索页。
        mToolbar.brandEntry.setOnClickListener(v -> SearchActivity.start(this));
        // 当前所在页 = 首页：常亮 50% 黑胶囊 + 白字（焦点离开后仍保留）。
        mToolbar.navHome.setSelected(true);
        // 记住最后聚焦的导航按钮：内容区 / 右侧动作区按返回时焦点回到它。
        // 这里的顺序即导航索引顺序，必须与 view_home_toolbar.xml 的 navButtons 子项顺序一致（3 项）。
        // 注意 mLastNav 存的是 View 引用而非下标，增删项不会留下错位索引。
        View[] navs = {mToolbar.navHome, mToolbar.navVod, mToolbar.navMine};
        for (View nav : navs) nav.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) mLastNav = view;
        });
    }

    /**
     * 顶部渐变遮罩：下边缘 = 工具栏底边 + 5dp。
     * 工具栏本身是「marginTop 16dp + 单行 64dp」，时钟已回到行内（站源入口与设置之间），
     * 所以底边就是整条顶栏的下沿；再留 5dp 让遮罩完整包住文字又不拖到主视觉画面中部。
     */
    private void applyTopScrim() {
        mToolbar.getRoot().post(() -> {
            if (mBinding == null || isFinishing() || isDestroyed()) return;
            ViewGroup.LayoutParams lp = mBinding.topScrim.getLayoutParams();
            lp.height = mToolbar.getRoot().getBottom() + ResUtil.dp2px(5);
            mBinding.topScrim.setLayoutParams(lp);
        });
    }

    // ------------------------------------------------------------ 顶栏随焦点显隐

    /**
     * 焦点所在列表行（adapter position）。从焦点沿父链找到 recycler 的<b>直接子 View</b>，
     * 再用 {@code getChildAdapterPosition} 取行号；包装层 / 复用 / 焦点落空时退回 Leanback
     * 自己维护的 {@code getSelectedPosition()}，保证永远拿得到一个有效行号。
     */
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

    /**
     * 顶栏显隐的<b>唯一判定点</b>：焦点 + 滚动位置 + sheet 三者共同决定。
     *
     * <p>隐藏条件 = 焦点落在主视觉行以下 <b>且</b> 列表已滚离主视觉。加入滚动位置这一项后，
     * 「按返回键把列表程序化滚回顶部」时即使焦点还没同步挪走，顶栏也会立即恢复显示；
     * 反过来，焦点在工具栏 / sheet / 窗口失焦时一律显示，不会出现焦点停在不可见按钮上。</p>
     *
     * <p>{@code focus == null}（列表重建时焦点临时落空）保持当前状态，避免连续两次动画抖动。</p>
     */
    private void updateToolbar(View focus) {
        if (mBinding == null || focus == null) return;
        // 站源面板锚定在工具栏入口下方，顶栏隐藏时面板会悬空；sheet 期间强制显示。
        if (isVisible(mSheet.getRoot())) {
            animateToolbar(true);
            return;
        }
        boolean belowHero = isDescendant(focus, mBinding.recycler)
                && focusedRow(focus) > getDoubanHotIndex();
        animateToolbar(!(belowHero && !isHeroAtTop()));
    }

    /**
     * 列表是否还停在顶部（主视觉行贴着列表顶边）。
     *
     * <p>只看真实子 View 的几何位置：<b>不能</b>用 {@code computeVerticalScrollOffset()} ——
     * leanback 的 {@code GridLayoutManager} 没有覆写它，恒返回 0。主视觉行因
     * {@code applyBleed()} 带了等量的负 topMargin，未滚动时 {@code getTop()} 约为 0；
     * 一旦向下滚就变成负数。找不到主视觉行（已滚出可视区 / 尚未加载）一律视为不在顶部，
     * 此时退回到「只按焦点判定」的旧行为，因此这个方法只会让顶栏<b>更倾向于显示</b>，
     * 不会引入「该显示却隐藏」的回归。</p>
     */
    private boolean isHeroAtTop() {
        RecyclerView recycler = mBinding.recycler;
        int tolerance = ResUtil.dp2px(2);
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View child = recycler.getChildAt(i);
            if (recycler.getChildAdapterPosition(child) != getDoubanHotIndex()) continue;
            return child.getTop() >= -tolerance;
        }
        return false;
    }

    /**
     * 显示 / 隐藏整条顶部导航栏：{@code homeToolbar} 与它下方的 {@code topScrim} 同步动画。
     *
     * <p>只改 alpha 与 translationY，<b>不改 visibility</b>：工具栏里的按钮仍需保持可聚焦，
     * 这样「列表第 0 行按上键 → focusNav()」以及返回键收回导航的既有链路不会因 GONE 而失效；
     * 隐藏期间焦点也只会由用户主动按上键 / 返回键带回来，动画会随即把它恢复显示。</p>
     */
    private void animateToolbar(boolean visible) {
        if (mToolbarVisible == visible) return;
        mToolbarVisible = visible;
        animateTopBar(mToolbar.getRoot(), visible);
        animateTopBar(mBinding.topScrim, visible);
    }

    /** 向上平移到完全出屏（用 bottom 而非 height，才能把 marginTop 一起算进去）+ 淡出。 */
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

    /**
     * 展开 sheet 前把顶栏<b>瞬时</b>复位（不走动画）：{@code positionSheet()} 依赖
     * {@code siteEntry.getLocationOnScreen()}，若顶栏还在平移途中，面板会锚到错误坐标。
     * 正常遥控器走位下顶栏本来就是显示的，这里是兜底。
     */
    private void showToolbarImmediate() {
        mToolbarVisible = true;
        for (View bar : new View[]{mToolbar.getRoot(), mBinding.topScrim}) {
            bar.animate().cancel();
            bar.setAlpha(1f);
            bar.setTranslationY(0f);
        }
    }

    /**
     * 「首页」按钮：刷新推荐内容、最近观看与首页主视觉，并把列表滚回顶部。
     * 主视觉仍走 DoubanHot 的 12 小时缓存（命中即立即重绘，不重复打接口）。
     */
    private void refreshHome() {
        // 内容整块重建，之前记下的海报 View 即将失效。
        clearPendingPoster();
        mLastBackAt = 0;
        scrollBackToHero();
        getVideo();
        getHistory();
        loadDoubanHot();
    }

    /**
     * 把列表滚回主视觉（第 0 行），同时让顶栏恢复显示。
     *
     * <p>滚动本身会触发 {@link #toolbarScrollWatcher} 按「当前焦点 + 滚动位置」重新收敛状态；
     * 这里的显式 {@code animateToolbar(true)} 覆盖「焦点还没同步 / 滚动监听尚未派发」的中间帧，
     * 保证任意时刻都不会出现「已回到主视觉却还顶着隐藏的导航栏」。</p>
     */
    private void scrollBackToHero() {
        mBinding.recycler.scrollToPosition(0);
        animateToolbar(true);
    }

    /** 展开下拉 sheet：刷新站源列表、定位面板、把焦点落到当前站源项，并播放弹出渐变动画。 */
    private void showSiteSheet() {
        // 收起动画期间面板仍是 VISIBLE：先强制收尾，让这次展开能从干净状态起步。
        if (sheetClosing) finishSheetClose();
        if (isVisible(mSheet.getRoot())) return;
        showToolbarImmediate();
        List<Site> sites = VodConfig.get().getSites().stream().filter(site -> !site.isHide()).toList();
        /*
         * 站源为空也要弹面板：面板第 0 项「切换站点」与站源无关，用户正是靠它
         * 在没有任何站源时切到另一个点播地址。以前这里直接 return，站源一空就
         * 再也没有入口能换点播地址。
         */
        mSiteMenuAdapter = new SiteMenuAdapter(sites, getHome(), new SiteMenuAdapter.OnClickListener() {
            @Override
            public void onItemClick(Site item) {
                setSite(item);
            }

            @Override
            public void onSwitchClick() {
                showConfigSheet();
            }

            @Override
            public void onEditClick() {
                // 与设置页「点播地址」右侧 home 按钮完全同一个弹窗；先收面板再弹，避免两层焦点陷阱叠加。
                hideSiteSheet();
                SiteDialog.create().action().show(HomeActivity.this);
            }
        });
        mSheet.siteList.setAdapter(mSiteMenuAdapter);
        // 二级面板随新一次展开复位：上一轮留下的动画与可见性都清掉。
        mSheet.configPanel.animate().cancel();
        finishConfigClose();
        mSheet.getRoot().setVisibility(View.VISIBLE);
        // 先置透明，避免「定位那一帧」把静态画面闪出来；post 里定位完再启动动画。
        mSheet.sitePanel.setAlpha(0f);
        mSheet.siteScrim.setAlpha(0f);
        // 双重保证：XML 里已是最后一个子 View 且 elevation 最高，这里再显式提到最前，
        // 防止后续有人往根布局插入新控件时把浮层盖住。
        mSheet.getRoot().bringToFront();
        mToolbar.siteArrow.setRotation(180f);
        mToolbar.siteEntry.post(() -> {
            if (!isVisible(mSheet.getRoot()) || sheetClosing) return;
            positionSheet();
            // 先把焦点收进陷阱容器（afterDescendants 会优先给列表项），
            // 再由 scrollToPosition 落到当前站源项。
            mSheet.getRoot().requestFocus();
            mSheet.siteList.scrollToPosition(mSiteMenuAdapter.indexOfCurrent());
            animatePanelOpen(mSheet.sitePanel, mSheet.siteScrim);
        });
    }

    /**
     * 打开渐变：遮罩淡入 + 面板淡入、自上方轻微下滑、纵向展开，减速插值收尾。
     * 站源 sheet 与功能 sheet 共用同一套动效，保证两个下拉观感一致。
     * {@code scrim} 传 {@code null} 表示复用已展开的遮罩（二级面板不再重复淡入遮罩）。
     */
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
        panel.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleY(1f)
                .setDuration(SHEET_ANIM)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /**
     * 收起渐变：遮罩淡出 + 面板淡出、上移、纵向收拢，加速插值收尾。
     * -{@code onEnd} 在动画自然结束后把 View 置 GONE；被下一次展开抢占时由调用方直接收尾。
     */
    private void animatePanelClose(View panel, View scrim, Runnable onEnd) {
        panel.animate().cancel();
        if (scrim != null) {
            scrim.animate().cancel();
            scrim.animate().alpha(0f).setDuration(SHEET_ANIM).start();
        }
        panel.setPivotY(0f);
        panel.animate()
                .alpha(0f)
                .translationY(-ResUtil.dp2px(8))
                .scaleY(0.96f)
                .setDuration(SHEET_ANIM)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(onEnd)
                .start();
    }

    /** 主面板收起动画结束（或被展开抢占）时的统一收尾。 */
    private void finishSheetClose() {
        sheetClosing = false;
        mSheet.sitePanel.animate().cancel();
        mSheet.siteScrim.animate().cancel();
        mSheet.getRoot().setVisibility(View.GONE);
    }

    /** 二级面板收起动画结束（或被展开抢占）时的统一收尾。 */
    private void finishConfigClose() {
        configClosing = false;
        mSheet.configPanel.setVisibility(View.GONE);
    }

    /**
     * 站源下拉列表的精确高度：8 行站源 + 「点播地址切换」「站点编辑」两个固定入口，
     * 外加「站点编辑」那一行底部的一条分割线。
     *
     * <p><b>必须逐段按密度四舍五入，不能把总 dp 一次性换算</b>：XML 里的 {@code 30dp / 1dp / 2dp}
     * 走 {@code getDimensionPixelSize}（四舍五入），而 {@code ResUtil.dp2px} 是截断。
     * 在非整数密度（如 2.75，30dp=82.5、1dp=2.75、2dp=5.5）下两种算法能差出十来像素，
     * 最后一行的底部就会被切掉一条 —— 观感正是「半行 / 文字贴边」。</p>
     *
     * <p>分割线只在「站点编辑」行显示，「点播地址切换」行没有（两者之间不画线），
     * 所以只加一份。</p>
     */
    private int siteListHeight() {
        float density = getResources().getDisplayMetrics().density;
        int row = Math.round(SITE_ROW_DP * density);
        int divider = Math.round(SITE_DIVIDER_DP * density) + 2 * Math.round(SITE_DIVIDER_MARGIN_DP * density);
        return row * (SITE_ROWS + 2) + divider;
    }

    /**
     * 面板锚定：顶边贴在入口下方 6dp、右缘与入口右缘对齐；宽度按<b>最长站源名</b>自适应
     * （文字宽 + item 左右 padding + panel 左右 padding + 余量），不受入口 6 字符限宽约束。
     */
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
        // 两个固定入口也要算进面板宽度，别把它们挤成省略号
        for (int id : new int[]{R.string.site_switch_config, R.string.site_edit}) {
            textWidth = Math.max(textWidth, mToolbar.siteName.getPaint().measureText(getString(id)));
        }
        int width = (int) Math.ceil(textWidth) + ResUtil.dp2px(44);
        // 极长站源名不能把面板顶出屏幕左边，给一个不截断文字前提下的安全上限。
        int maxWidth = Math.max(ResUtil.dp2px(140), mBinding.getRoot().getWidth() - marginEnd - ResUtil.dp2px(48));
        lp.width = Math.min(Math.max(width, ResUtil.dp2px(140)), maxWidth);
        mSheet.sitePanel.setLayoutParams(lp);
    }

    /**
     * 二级面板锚定：顶边与站源面板对齐，右缘贴在站源面板左侧留 8dp 间隙；
     * 宽度固定 240dp（点播网址普遍很长，靠省略号截断，不随内容自适应撑爆屏幕）。
     */
    private void positionConfigSheet() {
        FrameLayout.LayoutParams site = (FrameLayout.LayoutParams) mSheet.sitePanel.getLayoutParams();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mSheet.configPanel.getLayoutParams();
        lp.topMargin = site.topMargin;
        lp.rightMargin = site.rightMargin + site.width + ResUtil.dp2px(8);
        int maxWidth = Math.max(ResUtil.dp2px(140), mBinding.getRoot().getWidth() - lp.rightMargin - ResUtil.dp2px(24));
        lp.width = Math.min(ResUtil.dp2px(240), maxWidth);
        mSheet.configPanel.setLayoutParams(lp);
    }

    /**
     * 「切换站点」二级面板：列出设置中保存过的点播地址（历史），确认即切换整个点播配置。
     * 显示期间焦点直接落进二级列表的当前配置项；再按返回先收回二级面板。
     */
    private void showConfigSheet() {
        // 收起动画期间面板仍是 VISIBLE：先收尾，避免把「重新打开」误判为二次收起。
        if (configClosing) finishConfigClose();
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
            if (!isVisible(mSheet.configPanel) || configClosing) return;
            positionConfigSheet();
            mSheet.configList.scrollToPosition(mConfigMenuAdapter.indexOfCurrent());
            // 焦点直接落进二级列表（当前配置项）：用户目的就是切地址，不用再按一次方向键
            RecyclerView.ViewHolder holder = mSheet.configList.findViewHolderForAdapterPosition(mConfigMenuAdapter.indexOfCurrent());
            if (holder != null) holder.itemView.requestFocus();
            else mSheet.configPanel.requestFocus();
            animatePanelOpen(mSheet.configPanel, null);
        });
    }

    /** 收回二级面板，焦点回到「切换站点」入口（站源列表第 0 项），再按返回才是关整个 sheet。 */
    private void hideConfigSheet() {
        if (!isVisible(mSheet.configPanel) || configClosing) return;
        configClosing = true;
        // 先把焦点交回站源列表第 0 项，动画帧里二级面板不再参与焦点守卫。
        RecyclerView.ViewHolder holder = mSheet.siteList.findViewHolderForAdapterPosition(0);
        if (holder != null) holder.itemView.requestFocus();
        else mSheet.sitePanel.requestFocus();
        animatePanelClose(mSheet.configPanel, null, this::finishConfigClose);
    }

    /**
     * 选中点播地址：切换整个点播配置并自动重载——加载期间显示进度；
     * 成功后收起整个 sheet，{@code onConfigEvent(VOD)} 会随后触发首页内容 / 历史 / 标题的自动刷新；
     * 失败则提示错误并退回站源列表，保留用户所在的操作现场。
     */
    private void setConfig(Config item) {
        VodConfig.load(item, new Callback() {
            @Override
            public void start() {
                Notify.progress(HomeActivity.this);
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

    /** 收起下拉 sheet：焦点回到触发它的入口，面板播放退出动画结束后再 GONE。 */
    private void hideSiteSheet() {
        if (!isVisible(mSheet.getRoot()) || sheetClosing) return;
        sheetClosing = true;
        // 先把焦点和箭头撤出浮层：focusGuard 在收起期间让位，动画帧里不再抢焦点。
        mToolbar.siteArrow.setRotation(0f);
        mToolbar.siteEntry.requestFocus();
        // 二级面板直接收尾，不与主面板叠加两级退出动画。
        mSheet.configPanel.animate().cancel();
        finishConfigClose();
        animatePanelClose(mSheet.sitePanel, mSheet.siteScrim, this::finishSheetClose);
    }

    /** 判断 view 是否仍在 container 内部（含容器本身与任意层级的后代）。 */
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

    /** 焦点守卫的目标位置：当前站源项；拿不到具体项时退回容器本身。 */
    private void focusSheetCurrent() {
        mSheet.siteList.post(() -> {
            if (!isVisible(mSheet.getRoot())) return;
            RecyclerView.ViewHolder holder = mSiteMenuAdapter == null ? null
                    : mSheet.siteList.findViewHolderForAdapterPosition(mSiteMenuAdapter.indexOfCurrent());
            if (holder != null) holder.itemView.requestFocus();
            else mSheet.getRoot().requestFocus();
        });
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getAction().observe(this, result -> WebActivity.handleAction(this, result));
        mViewModel.getResult().observe(this, this::onResult);
    }

    private void onResult(Result result) {
        mAdapter.remove("progress");
        addVideo(mResult = result);
        Cache.clear().put(result);
    }

    private void setAdapter() {
        mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        // 自上而下：最近观看 → 更新推荐 → 各站源分组行。
        // 原「点播/搜索/收藏/连接/设置」功能行整行删除：入口搬到顶部工具栏与图标按钮，
        // 列表里不再保留它们的独立容器。工具栏现为 首页 / 四个站点 / 芒果TV / 片库，
        // 右侧再是 4 个功能图标 → 站源下拉 → 时间。
        // 主视觉由 loadDoubanHot() 插到列表最前面（index 0）。
        // 「最近观看」不再预置标题：标题与网格是同一个容器，列表为空时整块都要收起来，
        // 由 getHistory() 在有记录时一起插入，避免空列表还留一行标题占位。
        // 「来自 · 站源名」标题不再预置：它和网格是同一个容器，网格为空时整块都要收起来，
        // 由 addVideo() 在拿到内容后一起插入，避免只剩一个空标题。
    }

    private void checkPermission() {
        PermissionUtil.requestLocalNetwork(this, this::onNetworkPermissionResult);
    }

    private void onNetworkPermissionResult(boolean allGranted) {
        PermissionUtil.requestNotify(this);
        if (allGranted) DLNARendererService.start(this);
        initConfig();
        setTitle();
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mToolbar.siteName.setText(clampSiteName(s)));
        // 换站源后「来自 · 站源名」跟着变。
        updateRecommendHeader();
    }

    /**
     * 站源名限制为 6 个字符：用 ICU 的 character break 按<b>字形簇</b>切分，
     * emoji（含 ZWJ 组合序列、肤色修饰符、变体选择符）整体算 1 个字符，不会被
     * UTF-16 代理对拆成 2 个。超出时保留前 5 个簇再拼「...」——省略号整体按
     * 1 个字符计入预算，因此总逻辑长度恒为 6。
     */
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

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                onConfigReady();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                onConfigReady();
            }
        };
    }

    private void onConfigReady() {
        actionReady = true;
        showContent();
        consumeAction();
    }

    private void showContent() {
        mBinding.progressLayout.showContent();
        setFocus();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_ACTION_HANDLED, pendingAction == null);
        super.onSaveInstanceState(outState);
    }

    private void setFocus() {
        // 启动后默认焦点落在「首页」按钮：顶部工具栏是独立浮层，先让用户看到导航位置，
        // 按 ↓ 再进入内容区（工具栏各控件的 nextFocusDown 已指回 recycler）。
        if (mToolbar.navHome.requestFocus()) return;
        // 极端情况（导航尚未布局）：退回列表，至少保证焦点不丢。
        if (!mBinding.recycler.hasFocus()) mBinding.recycler.requestFocus();
    }

    private void getVideo() {
        mResult = Result.empty();
        int start = getRecommendStart();
        int progress = mAdapter.indexOf("progress");
        if (progress >= 0) {
            // 占位行已经在：只清掉它前面可能残留的旧标题 / 旧网格、以及它后面的旧内容，
            // 占位行本身保留（不重建就不会闪）。
            if (progress > start) {
                mAdapter.removeItems(start, progress - start);
                progress = start;
            }
            if (mAdapter.size() > progress + 1) mAdapter.removeItems(progress + 1, mAdapter.size() - progress - 1);
        } else {
            // 「来自 · 站源名」这一整块（标题 + 网格）从起始位置整体清掉，等新结果回来再重建。
            if (mAdapter.size() > start) mAdapter.removeItems(start, mAdapter.size() - start);
            mAdapter.add(start, "progress");
        }
        mViewModel.homeContent();
    }

    private void addVideo(Result result) {
        Style style = result.getStyle(getHome().getStyle());
        // 黑名单影片不再出现在首页：站源列表与主视觉用同一套判定，按归一化片名剔除。
        List<Vod> items = filterBlacklisted(result.getList());
        if (!items.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Vod vod : items) {
                if (vod == null) continue;
                // 站源卡片只回填海报：备注保持站源原文，不叠加 TMDB 评分（否则会和站源自带评分并存）。
                ArtSource.apply(vod);
                if (!vod.getName().isEmpty()) names.add(vod.getName());
            }
            // 首页混合电影 / 剧集；与最近观看共用同一套解析。
            ArtSource.enqueue(names, ArtSource.CAT_AUTO);
        }
        if (items.isEmpty()) {
            // 网格为空：整个容器一起收起（标题 + 网格），不留下一个孤立的空标题。
            removeRecommendBlock();
            return;
        }
        // 标题与网格同进同出：先补标题（站源名可能刚变过），再挂内容。
        ensureRecommendHeader();
        // 首页只展示前 10 部：超过的部分进「更多影片」页，避免整行无限拉长。
        mRecommendItems = items;
        mRecommendStyle = style;
        boolean more = items.size() >= HomeBoard.HOME_LIMIT;
        List<Vod> shown = items.size() > HomeBoard.HOME_LIMIT ? new ArrayList<>(items.subList(0, HomeBoard.HOME_LIMIT)) : items;
        if (style.isList()) {
            // 列表样式的卡片是整行宽条目，放不下类海报按钮；用一个哑条目承接同一个入口。
            mRecommendMore = more ? moreEntry() : null;
            List<Vod> cards = new ArrayList<>(shown);
            if (mRecommendMore != null) cards.add(mRecommendMore);
            mAdapter.addAll(mAdapter.size(), cards);
        } else {
            mRecommendMore = null;
            addGrid(shown, style, more);
        }
    }

    /** 列表样式下的「查看全部」条目：只有片名，点击由 {@link #onItemClick(Vod)} 分流。 */
    private Vod moreEntry() {
        Vod vod = new Vod();
        vod.setName(getString(R.string.home_board_more_title));
        return vod;
    }

    /** 剔除已拉黑的影片；返回新列表，避免改动站源结果缓存里的原始数据。 */
    private static List<Vod> filterBlacklisted(List<Vod> items) {
        List<Vod> out = new ArrayList<>();
        if (items == null) return out;
        for (Vod vod : items) {
            if (vod == null) continue;
            if (ArtBlacklist.contains(vod.getName())) continue;
            out.add(vod);
        }
        return out;
    }

    /**
     * 「更新推荐」只占<b>一行</b>：整段内容放进同一个 {@link ListRow}，横向左右滚动。
     *
     * <p>原来按 {@code Product.getColumn(style)} 把条目 {@code Lists.partition} 成多个
     * {@link ListRow}。leanback 里每个 ListRow 都是一个独立的 {@code HorizontalGridView}，
     * 多个 ListRow 由 VerticalGridView <b>纵向堆叠</b>——观感就是「一行满了换行」，与
     * 「最近观看」的单行横向滚动完全不是一回事。</p>
     *
     * <p>现在不再切分：一条 ListRow 承载全部条目，RecyclerView 只创建可见范围内的子 View，
     * 条目再多也只占一行高度，左右方向按 {@code FOCUS_SCROLL_ITEM} 走位（只在贴边时滚动，
     * 不会每次换焦点都把整行对齐重排而产生抖动）。</p>
     *
     * <p>条目数达到 {@link HomeBoard#HOME_LIMIT} 时，行末追加一张「查看全部」卡片
     * （与海报卡片同尺寸），点击进入「更多影片」页。</p>
     */
    private void addGrid(List<Vod> items, Style style, boolean more) {
        // 空结果不建行：旧实现用 Lists.partition，空列表天然不会产生任何 ListRow。
        if (items == null || items.isEmpty()) return;
        if (mVodPresenter == null || !mVodPresenter.matches(style)) mVodPresenter = new VodPresenter(this, style);
        List<Object> cards = new ArrayList<>(items);
        // 行末「查看全部」：三张海报取该模块第 11~13 张，顺序 [后景, 中景, 前景]。
        if (more) cards.add(new ViewAllItem(ViewAllItem.BOARD_SITE, ViewAllItem.postersFrom(mRecommendItems)));
        mAdapter.add(mAdapter.size(), createVideoRow(cards, style));
    }

    private ListRow createVideoRow(List<Object> items, Style style) {
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(
                new StackRowSelector(mVodPresenter, new PosterStackPresenter(this::openMore, style, this)));
        adapter.addAll(0, items);
        return new ListRow(adapter);
    }

    /**
     * 素材落库后就地刷新首页海报网格：既有直接挂在列表上的站源卡片，也有包在
     * 横向行里的卡片；只改海报，不重建行、不动焦点与滚动位置。
     * 备注一律不动（站源原文，含它自己的评分），避免一行出现两个评分。
     */
    private void refreshArt() {
        if (mBinding == null || mAdapter == null || isFinishing() || isDestroyed()) return;
        // 倒序遍历：刚被拉黑的卡片要直接从行里移除，正序删会跳过下一项。
        for (int i = mAdapter.size() - 1; i >= 0; i--) {
            Object item = mAdapter.get(i);
            if (item instanceof Vod) {
                Vod vod = (Vod) item;
                if (ArtBlacklist.contains(vod.getName())) {
                    mAdapter.removeItems(i, 1);
                    continue;
                }
                if (ArtSource.apply(vod)) mAdapter.notifyArrayItemRangeChanged(i, 1);
            } else if (item instanceof ListRow) {
                // 四个分类模块的片名/海报/评分都来自各自数据源，不参与站源素材回填与黑名单过滤。
                if (isBoardRow((ListRow) item)) continue;
                Object adapter = ((ListRow) item).getAdapter();
                if (!(adapter instanceof ArrayObjectAdapter)) continue;
                ArrayObjectAdapter row = (ArrayObjectAdapter) adapter;
                boolean changed = false;
                for (int j = row.size() - 1; j >= 0; j--) {
                    Object card = row.get(j);
                    if (!(card instanceof Vod)) continue;
                    Vod vod = (Vod) card;
                    if (ArtBlacklist.contains(vod.getName())) {
                        row.removeItems(j, 1);
                        changed = true;
                        continue;
                    }
                    if (ArtSource.apply(vod)) changed = true;
                }
                if (changed) row.notifyArrayItemRangeChanged(0, row.size());
            }
        }
        // 主视觉跟着一起重算：没剧照时它是收起的，图落库后要能自动挂回去；
        // 已挂出的行也会在这里按最新剧照 / 黑名单重新过滤，不用另设重建分支。
        if (!mHeroItems.isEmpty()) applyHero(mHeroItems);
    }

    private void getHistory() {
        getHistory(false);
    }

    private void getHistory(boolean renew) {
        List<History> items = History.get();
        int header = indexOfHeader(R.string.home_history);
        boolean hasBlock = header >= 0;
        // 必须先按「旧」adapter 判定行是否已挂载：isHistoryRow() 靠适配器身份识别，
        // 若提前换掉 mHistoryAdapter，旧行就再也匹配不上，下面会新增而不是替换，
        // 表现为设置里改海报尺寸后首页出现两行「最近观看」（旧行被挤到推荐区内）。
        boolean hasRow = hasBlock && isHistoryRow(header + 1);
        if (renew) mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        if (items.isEmpty()) {
            // 空列表：标题与网格作为一个整体收起，不留下空标题占位。
            if (hasRow) mAdapter.removeItems(header + 1, 1);
            if (hasBlock) mAdapter.removeItems(header, 1);
        } else if (!hasBlock) {
            // 有记录但整块还没挂出来：标题与行一起插入（位置在主视觉之后）。
            int at = historyInsertIndex();
            mAdapter.add(at, new RowHeader(R.string.home_history, getString(R.string.home_history)));
            mAdapter.add(at + 1, new ListRow(mHistoryAdapter));
        } else if (!hasRow) {
            mAdapter.add(header + 1, new ListRow(mHistoryAdapter));
        } else if (renew) {
            mAdapter.replace(header + 1, new ListRow(mHistoryAdapter));
        }
        mHistoryAdapter.setItems(items, new BaseDiffCallback<History>());
    }

    /** 某个位置是不是「最近观看」那行（用适配器身份判断，避免和站源行 / 网格行混淆）。 */
    private boolean isHistoryRow(int index) {
        if (index < 0 || index >= mAdapter.size()) return false;
        Object item = mAdapter.get(index);
        return item instanceof ListRow && ((ListRow) item).getAdapter() == mHistoryAdapter;
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        mHistoryAdapter.notifyArrayItemRangeChanged(0, mHistoryAdapter.size());
    }

    private void clearHistory() {
        removeHistoryBlock();
        // 最近观看已跨站点共用：清空要把所有配置的记录一起清掉，否则换站点后又冒出来。
        History.clearAll();
        mPresenter.setDelete(false);
        mHistoryAdapter.clear();
    }

    /** 「最近观看」区块的插入位置：主视觉之后；没有主视觉时就是列表顶部。 */
    private int historyInsertIndex() {
        int hero = indexOfHero();
        return hero >= 0 ? hero + 1 : 0;
    }

    /** 整块移除「最近观看」：先删网格行，再删标题；重复调用安全。 */
    private void removeHistoryBlock() {
        int header = indexOfHeader(R.string.home_history);
        if (header < 0) return;
        if (isHistoryRow(header + 1)) mAdapter.removeItems(header + 1, 1);
        mAdapter.removeItems(header, 1);
    }

    /* ------------------------------------------------------- 首页分类模块 */

    /**
     * 海报尺寸档位变化后重建所有受影响的卡片行。
     *
     * <p>尺寸由 {@link Product#getSpec} 在 presenter 构造时算出并写进 holder 的
     * LayoutParams，已挂出的 ViewHolder 不会重新测量，所以只能丢掉 presenter 与整行重挂。
     * 三类数据都已在内存里（分类命中 HomeBoard 的 12 小时缓存、站源与最近观看在字段里），
     * 重建不会发起新的网络请求。</p>
     */
    private void refreshPosterSize() {
        if (mBinding == null || mAdapter == null || isFinishing() || isDestroyed()) return;
        // 最近观看：卡片宽度 / 每屏列数与档位同源，换 presenter 后原地替换那一行。
        getHistory(true);
        // 「来自 · 站源名」：丢掉旧 presenter 与整块（标题 + 网格），按缓存结果重建。
        rebuildRecommend();
        // 四个分类模块：共用 presenter 先置空，摘掉旧标题与旧行后按新尺寸重挂。
        mBoardPresenter = null;
        for (int board = 0; board < HomeBoard.COUNT; board++) removeBoard(board);
        loadBoards();
    }

    /** 用当前缓存重建「来自 · 站源名」块（标题 + 网格 / 列表），尺寸档位变化后调用。 */
    private void rebuildRecommend() {
        if (mRecommendItems == null || mRecommendItems.isEmpty()) return;
        removeRecommendBlock();
        ensureRecommendHeader();
        boolean more = mRecommendItems.size() >= HomeBoard.HOME_LIMIT;
        List<Vod> shown = mRecommendItems.size() > HomeBoard.HOME_LIMIT ? new ArrayList<>(mRecommendItems.subList(0, HomeBoard.HOME_LIMIT)) : mRecommendItems;
        if (mRecommendStyle.isList()) {
            // 列表样式的卡片是整行宽条目，没有海报位：仍用一个哑条目承接「查看全部」。
            mRecommendMore = more ? moreEntry() : null;
            List<Vod> cards = new ArrayList<>(shown);
            if (mRecommendMore != null) cards.add(mRecommendMore);
            mAdapter.addAll(mAdapter.size(), cards);
        } else {
            mRecommendMore = null;
            addGrid(shown, mRecommendStyle, more);
        }
    }

    /** 拉取四个分类；命中 12 小时缓存会立即挂行，首次安装则走网络。 */
    private void loadBoards() {
        if (mBinding == null || mAdapter == null || isFinishing() || isDestroyed()) return;
        for (int board = 0; board < HomeBoard.COUNT; board++) {
            final int index = board;
            HomeBoard.load(index, items -> applyBoard(index, items));
        }
    }

    private static int boardTitleRes(int board) {
        switch (board) {
            case HomeBoard.DOUBAN_SERIES:
                return R.string.home_board_douban_series;
            case HomeBoard.TMDB_MOVIE:
                return R.string.home_board_tmdb_movie;
            case HomeBoard.TMDB_TV:
                return R.string.home_board_tmdb_tv;
            default:
                return R.string.home_board_douban_movie;
        }
    }

    /**
     * 一个分类的数据到位：建「标题 + 单行横滑海报」，插在「来自 · 站源名」上方。
     *
     * <p>卡片只承载片名、海报、评分三项，没有站点与影片 id，点击按片名进搜索结果
     * （见 {@link #onItemClick(Vod)}）。重复回调就地替换；空列表整行收起，不影响其它三行。</p>
     */
    private void applyBoard(int board, List<HomeBoard.Item> items) {
        if (mBinding == null || mAdapter == null || isFinishing() || isDestroyed()) return;
        if (items == null || items.isEmpty()) {
            removeBoard(board);
            return;
        }
        List<Vod> vods = new ArrayList<>(items.size());
        for (HomeBoard.Item item : items) {
            if (item == null || TextUtils.isEmpty(item.getTitle())) continue;
            Vod vod = new Vod();
            vod.setName(item.getTitle());
            vod.setArtPic(item.getPoster());
            // 评分不单独占字段：拼进备注，卡片按次标题统一渲染（与评分角标出现前一致）。
            // 无评分 / 评分为 0 时不能整行收掉，四个分类模块统一显示「暂无评分」占位。
            String remark = ArtSource.scoreRemark("", item.getScore());
            vod.setRemarks(remark.isEmpty() ? getString(R.string.douban_hot_no_rating) : remark);
            vods.add(vod);
        }
        if (vods.isEmpty()) {
            removeBoard(board);
            return;
        }
        if (mBoardPresenter == null) mBoardPresenter = new VodPresenter(this, Style.rect());
        Style style = Style.rect();
        List<Object> cards = new ArrayList<>(Math.min(vods.size(), HomeBoard.HOME_LIMIT) + 1);
        cards.addAll(vods.subList(0, Math.min(vods.size(), HomeBoard.HOME_LIMIT)));
        // 满 10 部才出现「查看全部」；不到 10 部不显示，全部影片已经在行内。
        if (cards.size() >= HomeBoard.HOME_LIMIT) {
            cards.add(new ViewAllItem(board, ViewAllItem.postersFrom(vods)));
        }
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(
                new StackRowSelector(mBoardPresenter, new PosterStackPresenter(this::openMore, style, this)));
        adapter.addAll(0, cards);
        RowHeader header = new RowHeader(boardTitleRes(board), getString(boardTitleRes(board)));
        ListRow row = new ListRow(adapter);
        RowHeader oldHeader = mBoardHeaders[board];
        ListRow oldRow = mBoardRows[board];
        int headerAt = oldHeader == null ? -1 : mAdapter.indexOf(oldHeader);
        if (headerAt >= 0) {
            mBoardHeaders[board] = header;
            mBoardRows[board] = row;
            mAdapter.replace(headerAt, header);
            int rowAt = oldRow == null ? -1 : mAdapter.indexOf(oldRow);
            if (rowAt >= 0) mAdapter.replace(rowAt, row);
            else mAdapter.add(headerAt + 1, row);
            return;
        }
        int at = boardInsertIndex(board);
        mBoardHeaders[board] = header;
        mBoardRows[board] = row;
        mAdapter.add(at, header);
        mAdapter.add(at + 1, row);
    }

    /** 该分类的插入位置：排在下标更大的分类之前，否则落在「来自 · 站源名」块的前面。 */
    private int boardInsertIndex(int board) {
        for (int i = board + 1; i < HomeBoard.COUNT; i++) {
            if (mBoardHeaders[i] == null) continue;
            int index = mAdapter.indexOf(mBoardHeaders[i]);
            if (index >= 0) return index;
        }
        return recommendAnchor();
    }

    /**
     * 「查看全部」入口：分类行带分类下标，进「更多影片」页后重新读该分类的完整榜单；
     * 「来自 · 站源名」带当前站源与完整结果，点击可直接起播。
     */
    private void openMore(ViewAllItem item) {
        if (item == null || mBinding == null) return;
        if (item.isSite()) {
            HomeMoreActivity.startSite(this, recommendHeader().getText().toString(), getHome().getKey(),
                    getHome().isIndex(), mRecommendItems, mRecommendStyle);
        } else {
            HomeMoreActivity.startBoard(this, getString(boardTitleRes(item.getBoard())), item.getBoard());
        }
    }

    /** 收起某个分类：先删内容行再删标题，重复调用安全。 */
    private void removeBoard(int board) {
        if (mAdapter == null) return;
        ListRow row = mBoardRows[board];
        RowHeader header = mBoardHeaders[board];
        mBoardHeaders[board] = null;
        mBoardRows[board] = null;
        int rowAt = row == null ? -1 : mAdapter.indexOf(row);
        if (rowAt >= 0) mAdapter.removeItems(rowAt, 1);
        int headerAt = header == null ? -1 : mAdapter.indexOf(header);
        if (headerAt >= 0) mAdapter.removeItems(headerAt, 1);
    }

    /**
     * 「来自 · 站源名」块可以落位的起点：标题在就用标题位置，占位行在就用占位行，
     * 都没有就接在「最近观看」之后、并跳过已挂出的分类行。
     */
    private int recommendAnchor() {
        int header = indexOfHeader(R.string.home_recommend);
        if (header >= 0) return header;
        int progress = mAdapter.indexOf("progress");
        if (progress >= 0) return progress;
        int end = afterHistory();
        while (isBoardSlot(end)) end++;
        return Math.min(end, mAdapter.size());
    }

    /** 「最近观看」区块之后的位置（没有该区块时就是主视觉之后 / 列表顶部）。 */
    private int afterHistory() {
        int history = indexOfHeader(R.string.home_history);
        int end = history < 0 ? historyInsertIndex() : history + 1;
        if (isHistoryRow(end)) end++;
        return end;
    }

    /** 这个位置是不是四个分类模块的标题或内容行（按对象身份判断）。 */
    private boolean isBoardSlot(int index) {
        if (index < 0 || index >= mAdapter.size()) return false;
        Object item = mAdapter.get(index);
        for (int i = 0; i < HomeBoard.COUNT; i++) {
            if (item == mBoardHeaders[i] || item == mBoardRows[i]) return true;
        }
        return false;
    }

    private boolean isBoardRow(ListRow row) {
        for (int i = 0; i < HomeBoard.COUNT; i++) if (mBoardRows[i] == row) return true;
        return false;
    }

    /**
     * 「来自 · 站源名」区块的起始位置：标题在就用标题的位置；标题被收起时退回
     * 「最近观看」区块之后——两者都不会因为另一块的增删而算错。
     */
    private int getRecommendStart() {
        int header = indexOfHeader(R.string.home_recommend);
        if (header >= 0) return header;
        int end = afterHistory();
        while (isBoardSlot(end)) end++;
        return Math.min(end, mAdapter.size());
    }

    /** 按资源 id 找栏目标题；标题可能是动态文字，所以只能按 key 比对，不能用 indexOf(Integer)。 */
    private int indexOfHeader(int key) {
        for (int i = 0; i < mAdapter.size(); i++) {
            Object item = mAdapter.get(i);
            if (item instanceof RowHeader && ((RowHeader) item).getKey() == key) return i;
        }
        return -1;
    }

    /** 当前站源名：与顶栏、站源下拉的「当前行」取同一份数据。 */
    private String currentSiteName() {
        String name = getHome().getName();
        if (!TextUtils.isEmpty(name)) return name;
        name = getConfig().getName();
        return TextUtils.isEmpty(name) ? "" : name;
    }

    /** 「来自 · 站源名」标题：站源名取不到时退回原来的「更新推荐」，不至于出现一个空的「来自 ·」。 */
    private RowHeader recommendHeader() {
        String site = currentSiteName();
        CharSequence text = TextUtils.isEmpty(site)
                ? getString(R.string.home_recommend)
                : getString(R.string.home_recommend_source, site);
        return new RowHeader(R.string.home_recommend, text);
    }

    /** 标题不存在就插到正确位置；已存在则只刷新文字（换站源后站源名会变）。 */
    private void ensureRecommendHeader() {
        int index = indexOfHeader(R.string.home_recommend);
        if (index >= 0) {
            mAdapter.replace(index, recommendHeader());
            return;
        }
        mAdapter.add(getRecommendStart(), recommendHeader());
    }

    /** 换站源 / 配置变化后只更新标题文字，不动网格与焦点。 */
    private void updateRecommendHeader() {
        if (mAdapter == null) return;
        int index = indexOfHeader(R.string.home_recommend);
        if (index >= 0) mAdapter.replace(index, recommendHeader());
    }

    /** 网格为空时整块收起：标题 + 后方全部网格行。 */
    private void removeRecommendBlock() {
        if (indexOfHeader(R.string.home_recommend) < 0) return;
        int start = getRecommendStart();
        if (start < 0 || start >= mAdapter.size()) return;
        mAdapter.removeItems(start, mAdapter.size() - start);
    }

    /** 加载主视觉（rank/latest 富化榜单）：命中 12 小时缓存立即渲染，过期后台刷新。 */
    private void loadDoubanHot() {
        DoubanHot.load(this::applyHero);
    }

    /**
     * 主视觉的唯一出口（幂等 + 自适配）。
     *
     * <p><b>「有内容」的判据是「榜单给了横版剧照路径」</b>，不是「榜单有片名」：
     * 轮播 Presenter 只接受能加载出剧照的条目，只有片名没有图时它会把整块文字层隐藏，
     * 留下一块 440dp 的同色底——那正是首页顶部的空档。所以这里先按
     * {@link DoubanHotItem#hasBackdrop()} 过滤，一张图都没有就整行收起。</p>
     *
     * <pre>
     *   无可渲染条目 → 收掉整行，下方栏目上移（数据源一次给全，不需要另起刮削补图）
     *   行已存在     → 内容变了就地 replace；内容没变直接返回，重复回调不闪
     *   从无到有     → 先记可视锚点再插入，已进入内容区时保持原行不动
     * </pre>
     */
    private void applyHero(List<DoubanHotItem> items) {
        if (mBinding == null || mAdapter == null || isFinishing() || isDestroyed()) return;
        ColdTrace.mark("hero.apply", "n=" + (items == null ? 0 : items.size()));
        List<DoubanHotItem> shown = new ArrayList<>();
        if (items != null) for (DoubanHotItem item : items) {
            if (item == null || ArtBlacklist.contains(item.getTitle())) continue;
            shown.add(item);
        }
        mHeroItems = shown;
        List<DoubanHotItem> renderable = new ArrayList<>();
        for (DoubanHotItem item : shown) {
            // 榜单已带横版剧照相对路径：有路径就能直接加载，不再查本地素材、也不触发刮削。
            if (item.hasBackdrop()) renderable.add(item);
        }
        int index = indexOfHero();
        if (renderable.isEmpty()) {
            collapseHero(index);
            return;
        }
        String signature = heroSignature(renderable);
        if (index >= 0) {
            if (signature.equals(mHeroSignature)) return;
            mHeroSignature = signature;
            mAdapter.replace(index, DoubanHotRow.create(renderable));
            return;
        }
        int anchorPosition = firstVisiblePosition();
        int anchorTop = firstVisibleTop();
        ColdTrace.mark("hero.row.insert", "renderable=" + renderable.size() + ", at=" + getDoubanHotIndex());
        mAdapter.add(getDoubanHotIndex(), DoubanHotRow.create(renderable));
        mHeroSignature = signature;
        applyContentInset(true);
        restoreAnchor(anchorPosition, anchorTop, 1);
        // 从无到有：行整体下移一个主视觉的高度，用一次短淡入盖住重排的瞬间。
        HeroTransition.fade(mBinding.recycler);
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
        if (index < 0) {
            applyContentInset(false);
            return;
        }
        boolean focused = mBinding.recycler.getSelectedPosition() == index;
        int anchorPosition = firstVisiblePosition();
        int anchorTop = firstVisibleTop();
        mAdapter.removeItems(index, 1);
        applyContentInset(false);
        restoreAnchor(anchorPosition, anchorTop, -1);
        // 行整体上移填补空出的高度，同样用短淡入过渡，不留白也不跳。
        HeroTransition.fade(mBinding.recycler);
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

    /**
     * 列表顶部内边距：主视觉存在时保持原有 8dp（画框通栏铺到顶部）；
     * 主视觉收起后内容直接顶到工具栏下方，用实测的工具栏底边补足，避免首行被顶栏压住。
     */
    private void applyContentInset(boolean hero) {
        if (mBinding == null) return;
        HeroInset.apply(mBinding.recycler, mToolbar.getRoot(), hero);
    }

    /** 主视觉固定占列表第 0 行：站源/时间栏移除后，它前面不再有任何行。 */
    private int getDoubanHotIndex() {
        return 0;
    }

    /**
     * 按片名直接打开搜索结果页：更新推荐海报与主视觉「查看详情」共用。
     *
     * <p>与 {@code SearchActivity.start(activity, keyword)} 的区别：后者先把关键词填进
     * 搜索页、再由搜索页立刻跳到结果页，栈是「首页 → 搜索页 → 结果页」，按返回必然
     * 经过搜索页。这里直接调 {@code CollectActivity.start}，栈只有「首页 → 结果页」，
     * 返回直接回首页。搜索页自己的 {@code onSearch()} 不走这里，保持原有返回链。</p>
     */
    private void openCollect(String keyword) {
        if (!TextUtils.isEmpty(keyword)) CollectActivity.start(this, keyword);
    }

    /** 点击主视觉：榜单热词不承载播放地址，按片名直接进搜索结果页（返回不经搜索页）。 */
    private void onDoubanHotClick(DoubanHotItem item) {
        if (item != null) openCollect(item.getTitle());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.history();
                RefreshEvent.home();
                setTitle();
                break;
            case COMMON:
                // 功能按钮行已移到顶部浮层，配置项变化不再需要重建列表行。
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (!mHomeStarted) return;
        switch (event.getType()) {
            case HOME:
                getVideo();
                setTitle();
                break;
            case HISTORY:
                getHistory();
                break;
            case SIZE:
                getVideo();
                getHistory(true);
                break;
        }
    }

    /** 事件是否来自本页（sourceHash 为 0 表示不限定页面）。 */
    private boolean isForThisPage(FocusGlowEvent event) {
        return event.isFor(System.identityHashCode(this));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFocusGlowEvent(FocusGlowEvent event) {
        // 定向分发 + 仅前台页面响应：页面栈里的其它 Activity 被广播唤醒后直接返回，
        // 不做几何重算、不写 state、不触发重组（事件风暴时的主要放大器）。
        if (mPageGlow == null || !isForThisPage(event) || getLifecycle().getCurrentState() != Lifecycle.State.RESUMED) return;
        if (event.isClear()) {
            mPageGlow.clear();
            return;
        }
        /*
         * 焦点「明确」离开内容区时，晚到的异步提色回调不能再把熄灭的光点亮。
         * 但 getCurrentFocus() == null 只是滚动重组的中间态，此时要放行：
         * 提色是异步的且不会重发，一旦在这里丢掉，这张卡就再也不会亮。
         */
        if (mBinding == null) return;
        View focus = getCurrentFocus();
        if (focus != null && !isDescendant(focus, mBinding.recycler)) return;
        // scrim 下沿与圆心统一交给 scheduleGlowCenter 的单一 post 任务计算。
        if (event.isCenterOnly()) {
            // 焦点刚落到新卡、主色还在后台提：先把光圈挪过去，颜色沿用上一张，等回调补齐。
            mPageGlow.setCenter(event.getCx(), event.getCy(), event.getCardW(), event.getCardH());
        } else {
            /*
             * 只换颜色，不用事件里的几何。提色（Palette）在后台线程跑，快速连按方向键时
             * 上一张卡的回调会晚到，若带着旧坐标 setGlow 就会把光晕拽回旧卡甚至行首。
             * 几何统一由 reportPageGlowCenter / updatePageGlowCenter 按当前焦点实时给出。
             */
            mPageGlow.setColor(event.getColor());
        }
        // 焦点切换常伴随一次滚动，下一帧按焦点卡真实位置再校正一次几何。
        scheduleGlowCenter();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                SearchActivity.start(this, event.text());
                break;
            case PUSH:
                VideoActivity.push(this, event.text());
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) VideoActivity.cast(this, event.history());
        else VodConfig.load(event.config(), getCallback(event));
    }

    private Callback getCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    @Override
    public void onItemClick(Vod item) {
        // 列表样式下的「查看全部」哑条目：不进搜索，直接开更多页。
        if (item == mRecommendMore) openMore(ViewAllItem.site());
        else if (item.isAction()) mViewModel.action(getHome().getKey(), item.getAction());
        // 分类模块的卡片没有站点与影片 id（只有片名/海报/评分），按片名进搜索结果。
        else if (item.getSite() == null || getHome().isIndex()) openCollect(item.getName());
        else VideoActivity.start(this, getHome().getKey(), item, null);
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item == mRecommendMore || item.isAction()) return false;
        openCollect(item.getName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        if (belongsToCurrentConfig(item)) {
            VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            return;
        }
        /*
         * 换过点播地址：这条记录是上一个站点的，它的 site key / vod id 在当前配置里
         * 已经不存在，直接进播放页只会拿不到详情。改走搜索结果页按片名重找，
         * 并把记录一起带过去 —— 在结果页选中同一部片时按它跳回原集数。
         */
        CollectActivity.start(this, item.getVodName(), item);
    }

    /**
     * 这条观看记录是否属于当前点播地址。
     *
     * <p>判据两条：① {@code siteUrl} 与当前配置地址一致（老数据没有该字段，视为一致）；
     * ② 记录里的站源 key 在当前配置里确实存在。任一不满足都说明是换站前的记录。</p>
     */
    private boolean belongsToCurrentConfig(History item) {
        String url = item.getSiteUrl();
        boolean sameConfig = TextUtils.isEmpty(url) || TextUtils.equals(url, VodConfig.getUrl());
        return sameConfig && !TextUtils.isEmpty(VodConfig.get().getSite(item.getSiteKey()).getKey());
    }

    @Override
    public void onItemDelete(History item) {
        mHistoryAdapter.remove(item.delete());
        if (mHistoryAdapter.size() > 0) return;
        // 删到一条不剩时整块收起（标题 + 网格），不留空标题占位。
        removeHistoryBlock();
        mPresenter.setDelete(false);
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) clearHistory();
        else setHistoryDelete(true);
        return true;
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
        // 换站源后内容全部刷新，旧海报 View 不再有效。
        clearPendingPoster();
        mLastBackAt = 0;
        setTitle();
        hideSiteSheet();
    }

    /**
     * 主视觉是通栏 item（y=0 → 880px），顶部工具栏浮在它的画框内部上方。
     * 系统默认「向上找焦点」要求候选 View 的底边在当前焦点顶边之上，几何上永远不成立，
     * 因此在列表首行按上键时显式把焦点交给工具栏最左的「首页」按钮；
     * 工具栏里所有可聚焦控件的 nextFocusDown 都在 XML 指回 recycler，形成闭环。
     *
     * <p>这里只做焦点接线，不拦截左右键 / 菜单键：旧的左右键切站源、上键刷新、
     * 菜单键弹站点选择已按本次需求全部移除。</p>
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && !isVisible(mSheet.getRoot())) {
            // 工具栏按 ↓：优先回到返回前记住的那张海报，而不是列表的默认选中项。
            if (KeyUtil.isDownKey(event) && isToolbarFocused()) {
                if (restorePendingPoster()) return true;
                // 首页为空（无主视觉 / 无最近观看 / 无推荐 / 站源也没有内容）时，
                // 列表里没有任何可聚焦项：放行会让焦点掉出界面，这里直接吞掉，焦点留在顶栏。
                if (!hasFocusableContent()) return true;
            }
            // 主视觉是通栏 item，几何上「向上找焦点」永不成立，这里显式把焦点交给导航按钮。
            if (KeyUtil.isUpKey(event) && mBinding.recycler.getSelectedPosition() == 0 && mBinding.recycler.hasFocus()) {
                focusNav();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** 列表里是否有可聚焦的内容行：影片卡、非空主视觉、非空横向行才算法。 */
    private boolean hasFocusableContent() {
        if (mAdapter == null) return false;
        for (int i = 0; i < mAdapter.size(); i++) {
            Object item = mAdapter.get(i);
            if (item instanceof Vod) return true;
            if (item instanceof DoubanHotRow) {
                if (((DoubanHotRow) item).size() > 0) return true;
            } else if (item instanceof ListRow) {
                Object adapter = ((ListRow) item).getAdapter();
                if (adapter instanceof ArrayObjectAdapter && ((ArrayObjectAdapter) adapter).size() > 0) return true;
            }
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ColdTrace.mark("home.onResume");
        // 免责声明未同意：页面不可见也不加载，先弹声明，同意后走 startHome 补全启动链路。
        if (!mHomeStarted) {
            Disclaimer.check(this, this::startHome);
            return;
        }
        // 设置页改过 TMDB 连接方式/站点：旧主视觉行内容没变但图片地址已失效，清签名强制重建。
        if (RankLatest.consumeConfigChanged()) {
            mHeroSignature = "";
            loadDoubanHot();
            // TMDB 分类的海报地址跟随全局连接方式：丢内存副本后按新地址重新解析磁盘缓存。
            HomeBoard.reset();
            loadBoards();
        }
        /*
         * 设置页改过「海报大小」：卡片尺寸在 presenter 构造时就写进 holder 的 LayoutParams，
         * 已挂出的行不会自己重新测量。返回首页时按新档位重建分类模块、站源行与最近观看，
         * 否则要重启应用才生效（「来自 · 站源名」之外的四行以前正是这个表现）。
         */
        if (mPosterSize >= 0 && PlayerSetting.getSize() != mPosterSize) {
            mPosterSize = PlayerSetting.getSize();
            refreshPosterSize();
        }
        startClock();
        // 从片库等页面返回：焦点若还停在上一页的导航项上（如「片库」），
        // 在首帧绘制之前就归位到「首页」，避免先看到旧高亮再跳过来。
        if (mInitialFocusDone && isNavFocused() && !mToolbar.navHome.hasFocus()) mToolbar.navHome.requestFocus();
    }

    /** 顶栏时钟：每次回到前台重挂一次，同意声明后启动的首次链路也会走到这里。 */
    private void startClock() {
        if (mClock != null) mClock.stop();
        mClock = Clock.create().view(mToolbar.clock).format("HH:mm");
        mClock.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mClock != null) mClock.stop();
    }

    /**
     * 首帧焦点：在第一次布局完成、尚未绘制时就把焦点投到「首页」。
     *
     * <p>布局里已去掉 {@code focusedByDefault}：否则新页面会先亮默认项、再跳到目标，
     * 观感就是焦点从旧位置快速闪一下再移过来。这里用一次性全局布局监听，保证
     * 首帧画出来时焦点已经在目标控件上。</p>
     */
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

    /** 投放首帧焦点：优先「首页」，失败下一帧重试，最后退回列表，焦点不悬空。 */
    private void requestInitialFocus() {
        if (mBinding == null || mInitialFocusDone) return;
        if (mToolbar.navHome.requestFocus()) {
            mInitialFocusDone = true;
            return;
        }
        mToolbar.navHome.post(() -> {
            if (mBinding == null) return;
            mInitialFocusDone = mToolbar.navHome.requestFocus() || mBinding.recycler.requestFocus();
        });
    }

    /**
     * 首帧焦点由 {@link #installInitialFocus()} 在布局阶段完成；这里只做兜底：
     * 万一布局监听没跑到（极端时序），窗口拿到焦点时再投一次。
     * 之后每次重新回到本页时，若焦点停在别的导航项上（例如刚从片库返回），
     * 归位到本页对应的「首页」。
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || mBinding == null) return;
        if (!mInitialFocusDone) {
            requestInitialFocus();
            return;
        }
        if (isNavFocused() && !mToolbar.navHome.hasFocus()) mToolbar.navHome.requestFocus();
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mSheet.configPanel)) {
            // 二级面板先于站源面板收回（分层返回）
            hideConfigSheet();
        } else if (isVisible(mSheet.getRoot())) {
            hideSiteSheet();
        } else if (mBinding.progressLayout.isProgress() && hasFocusableContent()) {
            // 已有内容时返回键先跳过进度条；空列表（未加载站点）不在这里消费，
            // 否则按键会被反复耗在「切内容」上，永远走不到退出分支。
            showContent();
        } else if (mPresenter.isDelete()) {
            setHistoryDelete(false);
        } else if (!hasFocusableContent()) {
            // 首页没有任何内容（未加载站点 / 站源列表为空）：焦点只可能在顶栏，
            // 返回键直接按「连按两次退出」处理，不落到下面按列表位置判断的分支。
            requestExitBack();
        } else if (isContentFocused()) {
            View poster = getCurrentFocus();
            boolean belowHero = focusedRow(poster) > getDoubanHotIndex();
            if (belowHero) {
                /*
                 * 焦点在主视觉以下（此状态下顶栏是隐藏的）：返回 = 完整「回首页」。
                 * ① 焦点明确落到「首页」按钮，不用 mLastNav（它可能停在别的导航项）；
                 * ② 画面同步滚回主视觉，顶栏随焦点 + 滚动位置恢复显示；
                 * ③ 旧海报在回滚过程中会被回收，mPendingPoster 必须清掉，
                 *    否则下次按 ↓ 会尝试聚焦一个已 detach 的 View。
                 */
                clearPendingPoster();
                mLastNav = mToolbar.navHome;
                mToolbar.navHome.requestFocus();
                scrollBackToHero();
            } else {
                // 焦点仍在主视觉行：沿用原逻辑，回上次那个导航按钮并记住当前项，再按 ↓ 原路返回。
                focusNav();
                mPendingPoster = poster;
            }
            mLastBackAt = 0;
        } else if (isToolbarActionFocused()) {
            // 顶栏右侧动作区（站源入口 / 设置 / 搜索框）：先回导航按钮，不直接触发退出。
            focusNav();
            mLastBackAt = 0;
        } else if (isHomeNavFocused()) {
            if (!isAtContentTop()) {
                // 已在「首页」按钮：先把列表滚回顶部，并让顶栏状态与滚动位置一致。
                mLastBackAt = 0;
                scrollBackToHero();
            } else {
                requestExitBack();
            }
        } else if (isNavFocused()) {
            // 其余导航按钮：统一回「首页」，旧海报随之失效。
            clearPendingPoster();
            mLastBackAt = 0;
            mToolbar.navHome.requestFocus();
        } else if (!isAtContentTop()) {
            // 兜底（焦点丢失等）：先把列表滚回顶部，并让顶栏状态与滚动位置一致。
            mLastBackAt = 0;
            scrollBackToHero();
        } else {
            requestExitBack();
        }
    }

    /**
     * 列表是否已经在顶部。
     *
     * <p>不能用 {@code getSelectedPosition() == 0}：没有任何内容行时（未加载站点、站源列表为空）
     * 列表没有选中项，位置是 {@code -1}，那样的判断会恒成立，返回键每次都只执行「滚回顶部」
     * 而永远退不出去。空列表与位置 {@code <= 0} 都按顶点处理。</p>
     */
    private boolean isAtContentTop() {
        return mBinding.recycler.getSelectedPosition() <= 0 || !hasFocusableContent();
    }

    /** 首页返回的最后一站：2 秒内连按两次才退出，避免误触。 */
    private void requestExitBack() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastBackAt <= BACK_EXIT_WINDOW) {
            exitHome();
        } else {
            mLastBackAt = now;
            Notify.show(R.string.back_exit_hint);
        }
    }

    /**
     * 焦点是否在列表内容区（主视觉与各行海报）。
     * 与 tv-latest 的 isContentFocused() 对应，只是那边看 pager / recycler，这里只有 recycler。
     */
    private boolean isContentFocused() {
        return isDescendant(getCurrentFocus(), mBinding.recycler);
    }

    /**
     * 焦点是否在顶栏的「动作区」：搜索圆钮（原品牌位）或站源下拉入口。
     * 设置已并入导航「我的」，搜索框按钮已移除，这里只保留这两个非导航控件。
     */
    private boolean isToolbarActionFocused() {
        View focus = getCurrentFocus();
        return isDescendant(focus, mToolbar.brandEntry)
                || isDescendant(focus, mToolbar.siteEntry);
    }

    /** 焦点是否在左侧导航文字按钮组。 */
    private boolean isNavFocused() {
        return isDescendant(getCurrentFocus(), mToolbar.navButtons);
    }

    /** 焦点是否正停在「首页」按钮本身。 */
    private boolean isHomeNavFocused() {
        return mToolbar.navHome.hasFocus();
    }

    /** 焦点是否还在顶部工具栏内（含任意后代）。 */
    private boolean isToolbarFocused() {
        // 焦点实际落在导航项 / 搜索钮等子控件上，父容器不一定 hasFocus。
        return isDescendant(getCurrentFocus(), mToolbar.getRoot());
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

    /** 焦点回到导航按钮：优先回离开前的那个，没有（或已不可见）则回「首页」。 */
    private void focusNav() {
        View target = mLastNav != null && mLastNav.isShown() ? mLastNav : mToolbar.navHome;
        target.requestFocus();
    }

    /**
     * ↓ 键补位：返回前记住的海报仍然挂载就回到它。
     * 校验 isAttachedToWindow + isShown，列表刷新或滚动回收后自动退化为系统默认落焦。
     */
    private boolean restorePendingPoster() {
        View target = mPendingPoster;
        if (target == null) return false;
        mPendingPoster = null;
        return target.isAttachedToWindow() && target.isShown() && target.requestFocus();
    }

    private void clearPendingPoster() {
        mPendingPoster = null;
    }

    /** 真正退出首页：后台还有播放则退到后台，否则交给基类 finish()。 */
    private void exitHome() {
        mLastBackAt = 0;
        if (PlaybackService.isRunning()) Util.moveToBackground(this);
        else super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        ArtSource.unregister(mArtListener);
        ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
        observer.removeOnGlobalFocusChangeListener(focusGuard);
        observer.removeOnGlobalFocusChangeListener(toolbarFocusWatcher);
        mBinding.recycler.removeOnScrollListener(toolbarScrollWatcher);
        if (--instanceCount == 0 && !isChangingConfigurations()) {
            BackupManager.backup();
            releaseResources();
        }
        super.onDestroy();
    }

    public static void releaseResources() {
        if (isRunning() || PlaybackService.isRunning()) return;
        DLNARendererService.stop(App.get());
        VodConfig.get().destroy();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        NodeRuntime.get().releaseIfUnused();
    }
}

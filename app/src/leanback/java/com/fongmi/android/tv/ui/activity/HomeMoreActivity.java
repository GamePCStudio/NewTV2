package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.ViewAllItem;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeMoreBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.PageGlowController;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ArtSource;
import com.fongmi.android.tv.utils.HomeBoard;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「更多影片」页：首页海报行末尾的「查看全部」卡片跳到这里。
 *
 * <p>两种入口共用一套渲染：</p>
 * <ul>
 *   <li>{@link #startBoard}：首页四个分类模块，数据回读 {@link HomeBoard}（内存 / 磁盘缓存，
 *       过期才联网）；</li>
 *   <li>{@link #startSite}：「来自 · 站源名」，条目直接取当前首页结果里被截断的那一段，
 *       带 site / vodId，所以点击能直接起播。</li>
 * </ul>
 *
 * <p>版式与交互跟分类页一致：5 列海报网格（列数由 {@link Product#getColumn(Style)} 决定）、
 * 左右 48dp 安全区、卡片点击进详情（站源条目）或按片名搜索（榜单条目）、长按按片名搜索。</p>
 */
public class HomeMoreActivity extends BaseActivity implements VodPresenter.OnClickListener {

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_BOARD = "board";
    private static final String EXTRA_STYLE = "style";
    private static final String EXTRA_SITE_KEY = "site_key";
    private static final String EXTRA_SITE_INDEX = "site_index";

    /**
     * 站源完整列表的进程内传递。
     *
     * <p>首页结果里的 {@link Vod} 带简介、播放列表等大字段，上百条走 Intent 有触发
     * {@code TransactionTooLargeException} 的风险；页面只会在点击卡片后立即打开，
     * 用静态引用既省内存拷贝也避免序列化开销。</p>
     */
    private static List<Vod> sSiteItems = Collections.emptyList();

    private ActivityHomeMoreBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private VodPresenter mPresenter;
    private Style mStyle;
    private String mSiteKey;
    private boolean mSiteIndex;
    /** 整页背景漫反射 + 焦点卡提亮（查看更多页）。 */
    private PageGlowController mPageGlow;

    /** 首页分类模块的「更多」入口。 */
    public static void startBoard(Activity activity, String title, int board) {
        open(activity, title, board, Style.rect(), null, false);
    }

    /** 「来自 · 站源名」的「更多」入口。 */
    public static void startSite(Activity activity, String title, String siteKey, boolean index, List<Vod> items, Style style) {
        sSiteItems = items == null ? Collections.emptyList() : new ArrayList<>(items);
        open(activity, title, ViewAllItem.BOARD_SITE, style == null ? Style.rect() : style, siteKey, index);
    }

    private static void open(Activity activity, String title, int board, Style style, String siteKey, boolean index) {
        Intent intent = new Intent(activity, HomeMoreActivity.class);
        intent.putExtra(EXTRA_TITLE, TextUtils.isEmpty(title) ? activity.getString(R.string.home_board_more_title) : title);
        intent.putExtra(EXTRA_BOARD, board);
        intent.putExtra(EXTRA_STYLE, style);
        intent.putExtra(EXTRA_SITE_KEY, siteKey);
        intent.putExtra(EXTRA_SITE_INDEX, index);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeMoreBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mStyle = getIntent().getParcelableExtra(EXTRA_STYLE);
        if (mStyle == null) mStyle = Style.rect();
        // 光层先挂：铺在布局最底层，网格才有背景可透。
        mPageGlow = new PageGlowController(this, mBinding.pageGlow);
        mPageGlow.attach();
        mSiteKey = getIntent().getStringExtra(EXTRA_SITE_KEY);
        mSiteIndex = getIntent().getBooleanExtra(EXTRA_SITE_INDEX, true);
        mBinding.title.setText(getIntent().getStringExtra(EXTRA_TITLE));
        mBinding.progressLayout.showProgress();
        setRecyclerView();
        loadItems(getIntent().getIntExtra(EXTRA_BOARD, ViewAllItem.BOARD_SITE));
    }

    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Vod.class, mPresenter = new VodPresenter(this, mStyle));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(Product.POSTER_SPACING_DP), VodPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        // 与分类页一致：行间距 8dp，行内列距走 CustomRowPresenter。
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(8));
    }

    private void loadItems(int board) {
        if (board == ViewAllItem.BOARD_SITE) {
            setItems(sSiteItems);
            return;
        }
        HomeBoard.load(board, items -> {
            if (isFinishing() || isDestroyed()) return;
            List<Vod> vods = new ArrayList<>();
            if (items != null) for (HomeBoard.Item item : items) {
                if (item == null || TextUtils.isEmpty(item.getTitle())) continue;
                Vod vod = new Vod();
                vod.setName(item.getTitle());
                vod.setArtPic(item.getPoster());
                // 评分不单独占字段：拼进备注，卡片按次标题统一渲染（与评分角标出现前一致）。
                // 无评分 / 评分为 0 时统一显示「暂无评分」，与首页四个分类模块一致。
                String remark = ArtSource.scoreRemark("", item.getScore());
                vod.setRemarks(remark.isEmpty() ? getString(R.string.douban_hot_no_rating) : remark);
                vods.add(vod);
            }
            setItems(vods);
        });
    }

    private void setItems(List<Vod> items) {
        List<Vod> vods = new ArrayList<>();
        if (items != null) for (Vod vod : items) if (vod != null) vods.add(vod);
        mBinding.progressLayout.showContent(true, vods.size());
        if (vods.isEmpty()) return;
        if (mStyle.isList()) {
            mAdapter.addAll(mAdapter.size(), vods);
        } else {
            for (List<Vod> part : Lists.partition(vods, Math.max(1, Product.getColumn(mStyle)))) {
                mAdapter.add(mAdapter.size(), createRow(part));
            }
        }
        // 进入页面即把焦点放到第一张海报：没有导航胶囊，不需要再按一次 ↓。
        mBinding.recycler.post(() -> {
            mBinding.recycler.setSelectedPosition(0);
            mBinding.recycler.requestFocus();
        });
    }

    private ListRow createRow(List<Vod> items) {
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(mPresenter);
        adapter.addAll(0, items);
        return new ListRow(adapter);
    }

    @Override
    public void onItemClick(Vod item) {
        if (item == null) return;
        if (TextUtils.isEmpty(mSiteKey) || mSiteIndex) CollectActivity.start(this, item.getName());
        else VideoActivity.start(this, mSiteKey, item, null);
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item == null || TextUtils.isEmpty(item.getName())) return false;
        CollectActivity.start(this, item.getName());
        return true;
    }

    @Override
    protected void onDestroy() {
        if (mPageGlow != null) mPageGlow.detach();
        super.onDestroy();
    }
}

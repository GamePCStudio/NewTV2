package com.fongmi.android.tv.ui.fragment;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentTypeBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.activity.CollectActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.activity.VodActivity;
import com.fongmi.android.tv.ui.activity.WebActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.presenter.FilterPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class TypeFragment extends BaseFragment implements CustomScroller.Callback, VodPresenter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private HashMap<String, String> mExtends;
    private FragmentTypeBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mLast;
    private VodPresenter mVodPresenter;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private List<Filter> mFilters;
    private boolean filterVisible;
    /** 筛选条最后一次获得焦点的控件：行下标（0 起）+ 行内列。 */
    private int mFilterRow;
    private int mFilterColumn;
    /** 离开海报区时记住的位置：相对海报区（不含筛选行）的行 + 行内列；-1 表示没有记录。 */
    private int mPosterRow = -1;
    private int mPosterColumn;

    public static TypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder) {
        Bundle args = new Bundle();
        args.putString("key", key);
        args.putString("typeId", typeId);
        args.putBoolean("folder", folder);
        args.putParcelable("style", style);
        args.putSerializable("extend", extend);
        TypeFragment fragment = new TypeFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    private String getTypeId() {
        return getArguments().getString("typeId");
    }

    private boolean isFolder() {
        return getArguments().getBoolean("folder");
    }

    private Style getStyle() {
        return isFolder() ? Style.list() : getSite().getStyle(getArguments().getParcelable("style"));
    }

    private HashMap<String, String> getExtend() {
        return (HashMap<String, String>) getArguments().getSerializable("extend");
    }

    private List<Filter> getFilter() {
        String typeId = getTypeId();
        if (!Cache.contains(typeId)) typeId = getParent().getType().getTypeId();
        return Cache.copy(typeId);
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private FolderFragment getParent() {
        return ((FolderFragment) getParentFragment());
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mScroller = new CustomScroller(this);
        mExtends = getExtend();
        mFilters = getFilter();
        setRecyclerView();
        setViewModel();
        setFilters();
        getVideo();
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.recycler.addOnScrollListener(mScroller);
        // 方向键先经过这里：记录筛选条内的工作位置，并在最后一行按下 ↓ 时回到离开前那张海报。
        mBinding.recycler.setOnKeyInterceptor(this::onContentKey);
    }

    private static boolean isDirectionKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /**
     * 内容网格的方向键预判（筛选条关闭时直接放行，不影响原有上下移动）。
     *
     * <p>筛选条的每一行都是一条独立的横向网格，方向键处理前先把当前行/列记下来，
     * 之后从海报按返回时才能精确回到「最后操作的那个筛选控件」，而不是每行都落回第一项。</p>
     *
     * <p>最后一行再按 ↓：回到离开前那张海报（没有记录时交给系统默认落焦）。</p>
     */
    private boolean onContentKey(KeyEvent event) {
        if (!filterVisible || mBinding == null) return false;
        int position = mBinding.recycler.getSelectedPosition();
        if (position < 0 || position >= mFilters.size()) return false;
        if (!isDirectionKey(event.getKeyCode())) return false;
        mFilterRow = position;
        mFilterColumn = getRowColumn(position);
        if (KeyUtil.isDownKey(event) && position == mFilters.size() - 1 && hasPoster()) return restorePoster();
        return false;
    }

    /**
     * 筛选条打开时的返回键（由 Activity 在返回链路上优先调用）。
     *
     * <ol>
     *   <li>焦点在海报：记住海报位置，焦点回到最后操作过的那个筛选控件；</li>
     *   <li>焦点在筛选条内部：收起筛选条，焦点交回顶部分类导航。</li>
     * </ol>
     */
    public boolean onFilterBack() {
        if (!filterVisible || mBinding == null) return false;
        int position = mBinding.recycler.getSelectedPosition();
        if (position < 0) return false;
        if (position < mFilters.size()) {
            // 筛选条内部：收起 + 焦点回分类导航（再按一次返回才轮到「片库」）。
            if (getActivity() instanceof VodActivity activity) activity.hideFilterAndFocusCapsule();
            else getParent().toggleFilter(false);
            return true;
        }
        // 海报：记住当前位置，回到最后操作过的筛选控件（无记录时落第一行第一项）。
        capturePoster();
        restoreFilter();
        return true;
    }

    /** 筛选条占用网格前 N 行；关闭时为 0。 */
    private int getFilterRows() {
        return filterVisible ? mFilters.size() : 0;
    }

    /** 可用的海报行数（不含筛选行）。 */
    private int getPosterRows() {
        return mAdapter == null ? 0 : Math.max(0, mAdapter.size() - getFilterRows());
    }

    private boolean hasPoster() {
        return getPosterRows() > 0;
    }

    /** 读某一行的行内选中列；优先按焦点子项算，行视图未绑定时返回 0。 */
    private int getRowColumn(int row) {
        if (mBinding == null) return 0;
        RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(row);
        if (holder == null || !(holder.itemView instanceof HorizontalGridView grid)) return 0;
        View focus = grid.findFocus();
        if (focus != null && focus != grid) {
            int position = grid.getChildAdapterPosition(focus);
            if (position != RecyclerView.NO_POSITION) return position;
        }
        return Math.max(0, grid.getSelectedPosition());
    }

    /**
     * 把焦点落到指定行，并在行视图绑定后补上行内列。
     *
     * <p>被回收过的行重新绑定时默认落在第 0 项，只按行定位会让「回到最后一个筛选控件」
     * 永远退化成行首，所以列要等布局完成再补一次。</p>
     */
    private void focusRow(int row, int column) {
        if (mBinding == null || row < 0) return;
        mBinding.recycler.setSelectedPosition(row);
        mBinding.recycler.requestFocus();
        mBinding.recycler.post(() -> applyColumn(row, column));
    }

    private void applyColumn(int row, int column) {
        if (mBinding == null || !mBinding.recycler.hasFocus()) return;
        if (mBinding.recycler.getSelectedPosition() != row) return;
        RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(row);
        if (holder == null || !(holder.itemView instanceof HorizontalGridView grid)) return;
        if (grid.getAdapter() == null || grid.getAdapter().getItemCount() == 0) return;
        int position = Math.max(0, Math.min(column, grid.getAdapter().getItemCount() - 1));
        grid.setSelectedPosition(position);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            if (grid.getChildAdapterPosition(child) == position) {
                if (!child.hasFocus()) child.requestFocus();
                return;
            }
        }
        if (!grid.hasFocus()) grid.requestFocus();
    }

    /** 记住当前海报位置（相对海报区的行 + 行内列）；焦点不在海报上时返回 false。 */
    public boolean capturePoster() {
        if (mBinding == null || !hasPoster()) return false;
        int row = mBinding.recycler.getSelectedPosition();
        int offset = getFilterRows();
        if (row < offset || row >= offset + getPosterRows()) return false;
        mPosterRow = row - offset;
        mPosterColumn = getRowColumn(row);
        return true;
    }

    /** 回到上次记住的海报；没有记录或列表为空时返回 false，由调用方决定兜底。 */
    public boolean restorePoster() {
        if (mBinding == null || mPosterRow < 0 || !hasPoster()) return false;
        focusRow(Math.min(mPosterRow, getPosterRows() - 1) + getFilterRows(), mPosterColumn);
        return true;
    }

    /** 分类切换 / 尺寸重建后清空海报记忆，避免回到已失效的位置。 */
    public void clearPoster() {
        mPosterRow = -1;
        mPosterColumn = 0;
    }

    /** 回到最后操作过的筛选控件；筛选条刚打开、还没操作过时落在第一行第一项。 */
    private boolean restoreFilter() {
        if (!filterVisible || mBinding == null || mFilters.isEmpty()) return false;
        focusRow(Math.max(0, Math.min(mFilterRow, mFilters.size() - 1)), mFilterColumn);
        return true;
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(Product.POSTER_SPACING_DP), VodPresenter.class);
        // 分类胶囊行保留 Leanback 原有左右 padding（-1），不适用海报行的归零规则。
        selector.addPresenter(ListRow.class, new CustomRowPresenter(8, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ALIGNED, -1), FilterPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        // 分类胶囊是固定导航，不随海报滚动隐藏：不注册 header，避免滚动时整块内容上移。
        // 行间距按原值的一半收紧（筛选条几行之间更紧凑）。
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(8));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
        mViewModel.getAction().observe(getViewLifecycleOwner(), result -> WebActivity.handleAction(requireActivity(), result));
    }

    private void setFilters() {
        for (Filter filter : mFilters) if (mExtends.containsKey(filter.getKey())) filter.setSelected(mExtends.get(filter.getKey()));
    }

    private void setClick(ArrayObjectAdapter adapter, String key, Value item) {
        for (int i = 0; i < adapter.size(); i++) ((Value) adapter.get(i)).setSelected(item);
        adapter.notifyArrayItemRangeChanged(0, adapter.size());
        if (item.isSelected()) mExtends.put(key, item.getV());
        else mExtends.remove(key);
        onRefresh();
    }

    private void getVideo() {
        mLast = null;
        checkFilter();
        mScroller.reset();
        getVideo(getTypeId(), "1");
    }

    private void getVideo(String typeId, String page) {
        mViewModel.categoryContent(getKey(), typeId, page, true, mExtends);
    }

    private void setAdapter(Result result) {
        boolean first = mScroller.first();
        boolean flag = mExtends.isEmpty();
        int size = result.getList().size();
        mBinding.progressLayout.showContent(first & flag, size);
        mBinding.swipeLayout.setRefreshing(false);
        mScroller.endLoading(result);
        if (size > 0) addVideo(result);
    }

    private void addVideo(Result result) {
        Style style = result.getStyle(getStyle());
        if (style.isList()) mAdapter.addAll(mAdapter.size(), result.getList());
        else addGrid(result.getList(), style);
        checkMore();
    }

    private void checkMore() {
        if (mScroller.isDisable() || mAdapter.size() >= 5) return;
        mScroller.checkMore();
    }

    private boolean checkLastSize(List<Vod> items, Style style) {
        if (mLast == null || items.isEmpty()) return false;
        int size = Product.getColumn(style) - mLast.size();
        if (size == 0) return false;
        size = Math.min(size, items.size());
        mLast.addAll(mLast.size(), items.subList(0, size));
        addGrid(items.subList(size, items.size()), style);
        return true;
    }

    private void addGrid(List<Vod> items, Style style) {
        if (mVodPresenter == null || !mVodPresenter.matches(style)) {
            mVodPresenter = new VodPresenter(this, style);
            mLast = null;
        }
        if (checkLastSize(items, style)) return;
        List<ListRow> rows = new ArrayList<>();
        for (List<Vod> part : Lists.partition(items, Product.getColumn(style))) rows.add(createVideoRow(part));
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private ListRow createVideoRow(List<Vod> items) {
        mLast = new ArrayObjectAdapter(mVodPresenter);
        mLast.addAll(0, items);
        return new ListRow(mLast);
    }

    private ListRow getRow(Filter filter) {
        FilterPresenter presenter = new FilterPresenter(filter.getKey());
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
        presenter.setOnClickListener((key, item) -> setClick(adapter, key, item));
        adapter.setItems(filter.getValue(), null);
        return new ListRow(adapter);
    }

    private void showFilter() {
        List<ListRow> rows = new ArrayList<>();
        for (Filter filter : mFilters) rows.add(getRow(filter));
        mBinding.recycler.postDelayed(() -> mBinding.recycler.scrollToPosition(0), 48);
        mAdapter.addAll(0, rows);
    }

    private void hideFilter() {
        mAdapter.removeItems(0, mFilters.size());
    }

    public void toggleFilter(boolean visible) {
        if (mFilters.isEmpty()) return;
        this.filterVisible = visible;
        if (visible) showFilter();
        else hideFilter();
    }

    private void checkFilter() {
        int adapterSize = mAdapter.size();
        int filterSize = filterVisible ? mFilters.size() : 0;
        if (adapterSize > filterSize) mAdapter.removeItems(filterSize, mAdapter.size() - filterSize);
        if (adapterSize == 0) mBinding.progressLayout.showProgress();
        else mBinding.swipeLayout.setRefreshing(true);
    }

    /**
     * 顶部分类胶囊按 ↓ 时把焦点交给内容网格。
     *
     * <p>筛选条打开后网格顶部全是筛选行，海报在屏幕外，系统按几何位置找不到焦点；
     * 这里显式把焦点落到网格的当前选中行，后续方向键由网格自己滚动。</p>
     */
    public boolean focusContent() {
        if (mBinding == null || mAdapter == null || mAdapter.size() == 0) return false;
        if (mBinding.recycler.getSelectedPosition() < 0) mBinding.recycler.setSelectedPosition(0);
        return mBinding.recycler.requestFocus();
    }

    public void onRefresh() {
        getVideo();
    }

    public boolean moveToTop() {
        return mBinding != null && mBinding.recycler.moveToTop();
    }

    /**
     * 把海报区复位到第一行第一个：只改内部选中位置与滚动，<b>不 requestFocus</b>。
     *
     * <p>切页时焦点仍在分类胶囊上，所以第一张海报不会显示焦点高亮，也不会被当作
     * 「已选中」；用户按下 ↓ 进入海报区后，才真正落在它上面并点亮。</p>
     */
    public void resetToFirstPoster() {
        if (mBinding == null) return;
        clearPoster();
        mBinding.recycler.scrollToPosition(0);
        mBinding.recycler.setSelectedPosition(0);
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) {
            mViewModel.action(getKey(), item.getAction());
        } else if (item.isFolder()) {
            getParent().openFolder(item.getId(), mExtends);
        } else {
            if (getSite().isIndex()) CollectActivity.start(requireActivity(), item.getName());
            else VideoActivity.start(requireActivity(), getKey(), item, isFolder() ? item.getName() : null);
        }
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction() || item.isFolder()) return false;
        CollectActivity.start(requireActivity(), item.getName());
        return true;
    }

    @Override
    public boolean onLoadMore(String page) {
        getVideo(getTypeId(), page);
        return true;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        // 分类胶囊不再随滚动隐藏：从子分类返回时只把焦点交回海报区即可。
        if (!hidden) mBinding.recycler.requestFocus();
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        // 切页 / 首次可见：只复位海报位置，不抢焦点；焦点始终由分类胶囊掌握。
        if (isVisibleToUser) resetToFirstPoster();
    }
}

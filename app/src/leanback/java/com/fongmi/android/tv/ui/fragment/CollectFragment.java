package com.fongmi.android.tv.ui.fragment;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentTypeBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.CollectActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.activity.VodActivity;
import com.fongmi.android.tv.ui.activity.WebActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;

public class CollectFragment extends BaseFragment implements CustomScroller.Callback, VodPresenter.OnClickListener {

    private FragmentTypeBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mLast;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private Collect mCollect;
    private String mKeyword;
    private int mColumns;

    public static CollectFragment newInstance(String keyword, Collect collect) {
        Bundle args = new Bundle();
        args.putString("keyword", keyword);
        CollectFragment fragment = new CollectFragment().setCollect(collect);
        fragment.setArguments(args);
        return fragment;
    }

    private String getKeyword() {
        return mKeyword = mKeyword == null ? getArguments().getString("keyword") : mKeyword;
    }

    private CollectFragment setCollect(Collect collect) {
        this.mCollect = collect;
        return this;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setRecyclerView();
        setViewModel();
        addVideo(mCollect);
    }

    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(ListRow.class, new CustomRowPresenter(Product.POSTER_SPACING_DP), VodPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.addOnScrollListener(mScroller = new CustomScroller(this));
        boolean vertical = Setting.getSearchLayout() == 1;
        mBinding.recycler.setHeader(getActivity(), vertical ? new int[]{R.id.result} : new int[]{R.id.result, R.id.recycler});
        mBinding.recycler.setReturnFocusView(requireActivity().findViewById(vertical ? R.id.verticalRecycler : R.id.recycler));
        mBinding.recycler.setOnKeyInterceptor(this::onGridKeyDown);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        if (vertical) mBinding.recycler.setPaddingRelative(ResUtil.dp2px(8), mBinding.recycler.getPaddingTop(), mBinding.recycler.getPaddingEnd(), mBinding.recycler.getPaddingBottom());
        mColumns = getResultColumns();
    }

    private int getResultColumns() {
        if (Setting.getSearchLayout() != 1) return Product.getColumn();
        int spacing = ResUtil.dp2px(Product.POSTER_SPACING_DP);
        int itemWidth = Product.getSpec(Style.rect())[0] + spacing;
        int width = mBinding.recycler.getWidth();
        if (width <= 0) {
            View pager = requireActivity().findViewById(R.id.pager);
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) pager.getLayoutParams();
            width = ResUtil.getScreenWidth() - params.leftMargin - mBinding.recycler.getPaddingStart() - mBinding.recycler.getPaddingEnd();
        }
        return Math.max(1, (Math.max(1, width) + spacing) / itemWidth);
    }

    private boolean onGridKeyDown(KeyEvent event) {
        if (Setting.getSearchLayout() != 1 || !KeyUtil.isLeftKey(event)) return false;
        View focused = requireActivity().getCurrentFocus();
        View next = focused == null ? null : focused.focusSearch(View.FOCUS_LEFT);
        if (next != null && !isInsideGrid(next)) return next.requestFocus();
        View source = requireActivity().findViewById(R.id.verticalRecycler);
        return source != null && source.requestFocus();
    }

    private boolean isInsideGrid(View view) {
        View current = view;
        while (current != null) {
            if (current == mBinding.recycler) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getAction().observe(getViewLifecycleOwner(), result -> WebActivity.handleAction(requireActivity(), result));
        mViewModel.getResult().observe(this, result -> {
            mScroller.endLoading(result);
            addVideo(result.getList());
        });
    }

    private boolean checkLastSize(List<Vod> items) {
        if (mLast == null || items.isEmpty()) return false;
        int size = mColumns - mLast.size();
        if (size == 0) return false;
        size = Math.min(size, items.size());
        mLast.addAll(mLast.size(), items.subList(0, size));
        addVideo(items.subList(size, items.size()));
        return true;
    }

    private void addVideo(Collect collect) {
        if (collect != null) addVideo(collect.getList());
    }

    public void addVideo(List<Vod> items) {
        if (checkLastSize(items) || getActivity() == null || getActivity().isFinishing()) return;
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this);
        for (List<Vod> part : Lists.partition(items, mColumns)) rows.add(createVideoRow(part, presenter));
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private ListRow createVideoRow(List<Vod> items, VodPresenter presenter) {
        mLast = new ArrayObjectAdapter(presenter);
        mLast.addAll(0, items);
        return new ListRow(mLast);
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) {
            mViewModel.action(item.getSiteKey(), item.getAction());
            return;
        }
        requireActivity().setResult(Activity.RESULT_OK);
        if (item.isFolder()) VodActivity.start(requireActivity(), item.getSiteKey(), Result.folder(item));
        else VideoActivity.collect(requireActivity(), item.getSiteKey(), item, takeResume(item));
    }

    /**
     * 跨站点观看记录带的「待续播」信息：只有点中的是同名影片才取到，取到即消费。
     * 拿到后作为 mark 传给播放页，进详情时按它在新站点选集。
     */
    private History takeResume(Vod item) {
        Activity activity = getActivity();
        return activity instanceof CollectActivity ? ((CollectActivity) activity).takeResume(item) : null;
    }

    @Override
    public boolean onLongClick(Vod item) {
        return false;
    }

    @Override
    public boolean onLoadMore(String page) {
        if (mCollect == null || "all".equals(mCollect.getSite().getKey())) return false;
        mViewModel.searchContent(mCollect.getSite(), getKeyword(), false, page);
        return true;
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (mBinding != null && !isVisibleToUser) mBinding.recycler.moveToTop();
    }
}

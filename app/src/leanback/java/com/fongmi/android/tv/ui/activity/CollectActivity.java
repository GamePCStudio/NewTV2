package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.CollectAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.PageGlowController;
import com.fongmi.android.tv.ui.fragment.CollectFragment;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;

public class CollectActivity extends BaseActivity {

    private ActivityCollectBinding mBinding;
    private CollectAdapter mAdapter;
    private CollectAdapter mVerticalAdapter;
    private SiteViewModel mViewModel;
    private List<Site> mSites;
    /** 整页背景漫反射 + 焦点卡提亮（搜索结果页）。 */
    private PageGlowController mPageGlow;
    /** 从「最近观看」跨站点记录进来的待续播信息（JSON），选中同一部片时消费掉。 */
    private static final String EXTRA_RESUME = "resume_history";

    /**
     * 直接按片名打开搜索结果页（首页主视觉、收藏、片库、搜索页都会走这里）。
     *
     * <p><b>本方法不参与搜索历史</b>：历史的唯一写入点是搜索页自己的搜索动作。
     * 结果页既不新增记录，也不清空记录 —— 从任何非搜索页入口进来都不会影响搜索页的历史列表。
     * 需要记录历史时由调用方（搜索页）在启动本页之前完成写入。</p>
     */
    public static void start(Activity activity, String keyword) {
        start(activity, keyword, null);
    }

    /**
     * 跨站点观看记录专用入口：带上原记录，选中同名影片进播放页时按它跳回原集数。
     *
     * @param history 原站点留下的记录；为 null 时与普通搜索结果页完全一致
     */
    public static void start(Activity activity, String keyword, History history) {
        Intent intent = new Intent(activity, CollectActivity.class);
        intent.putExtra("keyword", keyword);
        if (history != null) intent.putExtra(EXTRA_RESUME, history.toString());
        activity.startActivity(intent);
    }

    /**
     * 取出「待续播」记录 —— 仅当点中的影片与记录是同一部时才返回，取到即消费。
     *
     * <p>片名做一次归一化比较（去掉空白与标点、忽略大小写）：不同站点常带
     * 「（2024）」「HD」这类后缀，严格相等会漏；但不是同一部片时绝不复用，
     * 否则会把用户跳到毫无关系的集数上。</p>
     */
    public History takeResume(Vod item) {
        Intent intent = getIntent();
        String json = intent.getStringExtra(EXTRA_RESUME);
        if (item == null || TextUtils.isEmpty(json)) return null;
        History history = History.objectFrom(json);
        if (history == null || !sameTitle(history.getVodName(), item.getName())) return null;
        intent.removeExtra(EXTRA_RESUME);
        return history;
    }

    private static boolean sameTitle(String a, String b) {
        String left = normalizeTitle(a);
        String right = normalizeTitle(b);
        if (left.isEmpty() || right.isEmpty()) return false;
        if (left.equals(right)) return true;
        // 一方多出少量后缀（年份 / 清晰度 / 季集说明）时仍算同一部；短名不做包含匹配，
        // 否则「爱情」会把「爱情公寓」也算成同一部。
        String shortOne = left.length() <= right.length() ? left : right;
        String longOne = shortOne == left ? right : left;
        return shortOne.length() >= 4 && longOne.startsWith(shortOne) && longOne.length() - shortOne.length() <= 8;
    }

    private static String normalizeTitle(String name) {
        return TextUtils.isEmpty(name) ? "" : name.replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’（）【】《》·—…]", "").toLowerCase();
    }

    private CollectFragment getFragment() {
        return (CollectFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, 0);
    }

    private String getKeyword() {
        return getIntent().getStringExtra("keyword");
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityCollectBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        getIntent().putExtras(intent);
        // putExtras 是合并语义：这次没带待续播记录时必须显式清掉上一次的，
        // 否则从普通搜索进来还会沿用上一轮的旧记录去跳集数。
        if (!intent.hasExtra(EXTRA_RESUME)) getIntent().removeExtra(EXTRA_RESUME);
        mAdapter.clear();
        mVerticalAdapter.clear();
        setSearchLayout();
        setPager();
        search();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 光层先挂：铺在布局最底层，各站点分页里的网格才有背景可透。
        // 监听与事件订阅在 onStart/onStop 挂摘，后台页面不再耗资源。
        mPageGlow = new PageGlowController(this, mBinding.pageGlow);
        setRecyclerView();
        setSearchLayout();
        setViewModel();
        // 凡经 CollectActivity.start() 进入结果页的关键词一律不写搜索历史；
        // 搜索页自身的记录走 SearchActivity.onSearch() 的 mRecordAdapter.add()，不受影响。
        setSites();
        setPager();
        search();
    }

    @Override
    protected void initEvent() {
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                syncSelection(position);
                activeRecycler().requestFocus();
            }
        });
        mBinding.recycler.addOnChildViewHolderSelectedListener(new SelectionListener());
        mBinding.verticalRecycler.addOnChildViewHolderSelectedListener(new SelectionListener());
    }

    private final class SelectionListener extends OnChildViewHolderSelectedListener {
        @Override
        public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
            onChildSelected(child, position);
        }
    }

    private void setRecyclerView() {
        mBinding.recycler.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setRowHeight(ResUtil.dp2px(56));
        mBinding.recycler.setAdapter(mAdapter = new CollectAdapter());
        mBinding.verticalRecycler.setAdapter(mVerticalAdapter = new CollectAdapter());
    }

    private void setSearchLayout() {
        boolean vertical = Setting.getSearchLayout() == 1;
        mBinding.recycler.setVisibility(vertical ? View.GONE : View.VISIBLE);
        mBinding.verticalRecycler.setVisibility(vertical ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mBinding.pager.getLayoutParams();
        params.leftMargin = vertical ? ResUtil.dp2px(196) : 0;
        params.topMargin = vertical ? 0 : ResUtil.dp2px(56);
        mBinding.pager.setLayoutParams(params);
    }

    private RecyclerView activeRecycler() {
        return Setting.getSearchLayout() == 1 ? mBinding.verticalRecycler : mBinding.recycler;
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getSearch().observe(this, result -> {
            if (result.getList().isEmpty()) return;
            getFragment().addVideo(result.getList());
            Collect item = Collect.create(result.getList());
            mAdapter.add(item);
            mVerticalAdapter.add(item);
            int hit = Math.max(0, mAdapter.getItemCount() - 1);
            mAdapter.setCount(hit, mSites.size());
            mVerticalAdapter.setCount(hit, mSites.size());
            mBinding.pager.getAdapter().notifyDataSetChanged();
        });
    }

    private void setSites() {
        mSites = VodConfig.get().getSites().stream().filter(Site::isSearchable).toList();
    }

    private void setPager() {
        mBinding.pager.setAdapter(new PageAdapter(getSupportFragmentManager()));
    }

    private void search() {
        if (mSites.isEmpty()) return;
        mAdapter.add(Collect.all());
        mVerticalAdapter.add(Collect.all());
        mAdapter.setCount(0, mSites.size());
        mVerticalAdapter.setCount(0, mSites.size());
        mBinding.pager.getAdapter().notifyDataSetChanged();
        mBinding.result.setText(getString(R.string.collect_result, getKeyword()));
        syncSelection(0);
        activeRecycler().requestFocus();
        mViewModel.searchContent(mSites, getKeyword(), false);
    }

    private void syncSelection(int position) {
        mAdapter.select(position);
        mVerticalAdapter.select(position);
        mBinding.recycler.setSelectedPosition(position);
        mBinding.verticalRecycler.setSelectedPosition(position);
    }

    private void onChildSelected(@Nullable RecyclerView.ViewHolder child, int position) {
        if (child == null) return;
        syncSelection(position);
        App.post(() -> mBinding.pager.setCurrentItem(position), 100);
    }

    @Override
    protected void onBackInvoked() {
        mViewModel.stopSearch();
        super.onBackInvoked();
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            return CollectFragment.newInstance(getKeyword(), mAdapter.get(position));
        }

        @Override
        public int getCount() {
            return mAdapter.getItemCount();
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }

        @Nullable
        @Override
        public Parcelable saveState() {
            return null;
        }

        @Override
        public void restoreState(@Nullable Parcelable state, @Nullable ClassLoader loader) {
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mPageGlow != null) mPageGlow.attach();
    }

    @Override
    protected void onStop() {
        if (mPageGlow != null) mPageGlow.detach();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}

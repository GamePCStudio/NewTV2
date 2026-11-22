package com.fongmi.android.tv.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.databinding.FragmentFolderBinding;
import com.fongmi.android.tv.ui.activity.VodActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;

import java.util.HashMap;
import java.util.Optional;

public class FolderFragment extends BaseFragment {

    private FragmentFolderBinding mBinding;
    private Class mType;

    public static FolderFragment newInstance(String key, Class type) {
        Bundle args = new Bundle();
        args.putString("key", key);
        args.putParcelable("type", type);
        FolderFragment fragment = new FolderFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    public Class getType() {
        return getArguments().getParcelable("type");
    }

    /** 当前真正可见的分类页（进入子分类后新页叠在旧页上，取最后一个未隐藏的）。 */
    private TypeFragment getChild() {
        // 换站源时 VodActivity.onTypesChanged 会先把旧 FolderFragment 从
        // FragmentManager 移除；移除后 mHost 已置空（detach），此时再取
        // childFragmentManager 会抛 "Fragment ... has not been attached yet"。
        // 本类所有调用方都按 Optional 处理 null，这里直接放弃本次访问。
        if (getHost() == null) return null;
        for (Fragment fragment : getChildFragmentManager().getFragments()) {
            if (fragment instanceof TypeFragment && !fragment.isHidden()) return (TypeFragment) fragment;
        }
        return (TypeFragment) getChildFragmentManager().findFragmentById(R.id.container);
    }

    private VodActivity getParent() {
        return (VodActivity) getActivity();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentFolderBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mType = getType();
        getChildFragmentManager().beginTransaction().replace(R.id.container, TypeFragment.newInstance(getKey(), mType.getTypeId(), mType.getStyle(), getExtend(), mType.isFolder())).commit();
    }

    private HashMap<String, String> getExtend() {
        HashMap<String, String> extend = new HashMap<>();
        for (Filter filter : Cache.get(mType)) if (filter.getInit() != null) extend.put(filter.getKey(), filter.getInit());
        return extend;
    }

    public void openFolder(String typeId, HashMap<String, String> extend) {
        TypeFragment next = TypeFragment.newInstance(getKey(), typeId, mType.getStyle(), new HashMap<>(extend), mType.isFolder());
        FragmentTransaction ft = getChildFragmentManager().beginTransaction();
        Optional.ofNullable(getParent()).ifPresent(VodActivity::closeFilter);
        Optional.ofNullable(getChild()).ifPresent(ft::hide);
        ft.add(R.id.container, next);
        ft.addToBackStack(null);
        ft.commit();
    }

    public void toggleFilter(boolean visible) {
        Optional.ofNullable(getChild()).ifPresent(f -> f.toggleFilter(visible));
    }

    /** 筛选条打开时的返回：海报 → 最后操作的筛选控件；筛选条内 → 收起。关闭时返回 false。 */
    public boolean onFilterBack() {
        return Optional.ofNullable(getChild()).map(TypeFragment::onFilterBack).orElse(false);
    }

    /** 记住当前海报位置（相对海报区），供「返回分类导航后再按 ↓」回到原位。 */
    public boolean capturePoster() {
        return Optional.ofNullable(getChild()).map(TypeFragment::capturePoster).orElse(false);
    }

    public boolean restorePoster() {
        return Optional.ofNullable(getChild()).map(TypeFragment::restorePoster).orElse(false);
    }

    public void clearPoster() {
        Optional.ofNullable(getChild()).ifPresent(TypeFragment::clearPoster);
    }

    /** 顶部分类胶囊按 ↓：把焦点交给内容网格（筛选条或海报）。 */
    public boolean focusContent() {
        return Optional.ofNullable(getChild()).map(TypeFragment::focusContent).orElse(false);
    }

    public void onRefresh() {
        Optional.ofNullable(getChild()).ifPresent(TypeFragment::onRefresh);
    }

    public boolean moveToTop() {
        return Optional.ofNullable(getChild()).map(TypeFragment::moveToTop).orElse(false);
    }

    /** 切页时把海报区复位到第一张，但不抢焦点（焦点留在分类胶囊）。 */
    public void resetToFirstPoster() {
        Optional.ofNullable(getChild()).ifPresent(TypeFragment::resetToFirstPoster);
    }

    public boolean canBack() {
        return getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    public void goBack() {
        getChildFragmentManager().popBackStack();
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (mBinding != null && !isVisibleToUser) Optional.ofNullable(getChild()).ifPresent(f -> f.setUserVisibleHint(false));
    }
}

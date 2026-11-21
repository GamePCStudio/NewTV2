package com.fongmi.android.tv.ui.custom;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.PresenterSelector;

import com.fongmi.android.tv.bean.ViewAllItem;

/**
 * 海报行内条目选择器：普通影片走 VodPresenter，行末「查看全部」走 PosterStackPresenter。
 *
 * <p>不能用 ClassPresenterSelector：外层 {@link CustomSelector} 在行内存在多个
 * Presenter 时，会拿 ListRow 本身向行内 adapter 查询 Presenter，后者对未注册类型
 * 返回 null 会让外层在建 ViewHolder 时空指针。这里对任何对象都回一个非 null
 * Presenter（ListRow 自然落到 vodPresenter），保证查询链完整。</p>
 */
public class StackRowSelector extends PresenterSelector {

    private final Presenter vodPresenter;
    private final Presenter stackPresenter;
    private final Presenter[] all;

    public StackRowSelector(@NonNull Presenter vodPresenter, @NonNull Presenter stackPresenter) {
        this.vodPresenter = vodPresenter;
        this.stackPresenter = stackPresenter;
        this.all = new Presenter[]{vodPresenter, stackPresenter};
    }

    @Override
    public Presenter getPresenter(Object item) {
        return item instanceof ViewAllItem ? stackPresenter : vodPresenter;
    }

    @NonNull
    @Override
    public Presenter[] getPresenters() {
        return all;
    }
}

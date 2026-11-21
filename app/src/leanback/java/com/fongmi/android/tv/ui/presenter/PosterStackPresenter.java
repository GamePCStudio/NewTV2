package com.fongmi.android.tv.ui.presenter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.ViewAllItem;
import com.fongmi.android.tv.databinding.AdapterViewAllBinding;
import com.fongmi.android.tv.ui.custom.PosterFocusAnimator;

/**
 * 把 {@link com.fongmi.android.tv.ui.custom.PosterStackView} 挂进 Leanback 页面。
 *
 * <p><b>最小可运行接入说明</b>（规范第 7 / 8 项）：</p>
 * <ol>
 *   <li>在 RowsSupportFragment / BrowseSupportFragment 的页面里建
 *       {@code ArrayObjectAdapter}：行内 adapter 用
 *       {@code new StackRowSelector(vodPresenter, new PosterStackPresenter(callback, style, context))}，
 *       外层 selector 用 {@code new CustomSelector()} 注册
 *       {@code addPresenter(ListRow.class, customRowPresenter, VodPresenter.class)}；</li>
 *   <li>行末追加一个 {@code ViewAllItem}：分类模块用
 *       {@code new ViewAllItem(board, ViewAllItem.postersFrom(vods))}，
 *       「来自 · 站源名」用 {@code new ViewAllItem(ViewAllItem.BOARD_SITE, ViewAllItem.postersFrom(items))}；
 *       三张图即该分类第 11~13 张海报，顺序 [后景, 中景, 前景]；
 *       若改用本地直角素材，把 {@code res/drawable-nodpi} 的资源 id 交给
 *       {@code PosterStackView.setPosters(List&lt;Integer&gt;)} 即可；</li>
 *   <li>首次进入页面时在布局完成后 post 一次 {@code requestFocus()} 把首个焦点
 *       给到行内条目（TV 上不要依赖 focusedByDefault）；</li>
 *   <li>点击回调接在 {@link OnClickListener#onClick(ViewAllItem)}：本项目的实现是
 *       {@code HomeMoreActivity.startBoard / startSite}，只做页面跳转，
 *       不改变任何卡片状态与层级参数。</li>
 * </ol>
 *
 * <p>item 高度 = {@link Product#getSpec(Style)} 的 <b>画框高 + 片名区高</b>，
 * 与同行海报卡的整卡高度完全一致，行高、垂直对齐与海报档位切换都天然跟随；
 * 卡片本身只排在这段高度的顶部，堆叠视觉块的高度恰好等于周边画框高度。
 * 宽度取 {@code getSpec(style)[0]}，因此按钮尺寸随「海报大小」设置同步变化。</p>
 */
public class PosterStackPresenter extends Presenter {

    public interface OnClickListener {
        void onClick(ViewAllItem item);
    }

    private final OnClickListener listener;
    private final int[] size;
    /** 周边海报卡画框下方的片名 + 元数据区总高（与 adapter_vod_rect 等高）。 */
    private final int textBlock;

    public PosterStackPresenter(OnClickListener listener, Style style, Context context) {
        this.listener = listener;
        this.size = Product.getSpec(style);
        this.textBlock = context.getResources().getDimensionPixelOffset(R.dimen.stack_poster_text_block);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        AdapterViewAllBinding binding = AdapterViewAllBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        ViewGroup.LayoutParams lp = binding.getRoot().getLayoutParams();
        lp.width = size[0];
        // 与周边整卡等高（画框 + 片名区），保证行高与垂直对齐完全一致；
        // 卡片本身只排在这段高度的顶部，视觉块高度恰好等于周边画框。
        lp.height = size[1] + textBlock;
        binding.getRoot().setLayoutParams(lp);
        // 焦点反馈完全复用海报卡：整卡 1.04 缩放 + selector_vod 白描边 + 120ms。
        // 矩形卡 elevate=false（与 VodRectHolder 一致），不抬升只缩放。
        PosterFocusAnimator.bind(binding.getRoot(), false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, Object item) {
        Holder holder = (Holder) viewHolder;
        ViewAllItem entry = (ViewAllItem) item;
        holder.binding.getRoot().setPosterUrls(entry.getPosters());
        holder.binding.getRoot().setOnClickListener(v -> {
            if (listener != null) listener.onClick(entry);
        });
    }

    @Override
    public void onUnbindViewHolder(@NonNull ViewHolder viewHolder) {
        Holder holder = (Holder) viewHolder;
        holder.binding.getRoot().clearPosters();
        holder.binding.getRoot().setOnClickListener(null);
        PosterFocusAnimator.reset(holder.binding.getRoot());
    }

    private static final class Holder extends ViewHolder {

        private final AdapterViewAllBinding binding;

        private Holder(AdapterViewAllBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

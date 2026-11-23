package com.fongmi.android.tv.ui.adapter;

import android.text.TextPaint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.AdapterSiteMenuBinding;
import com.fongmi.android.tv.databinding.AdapterSiteMenuSwitchBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 右上角「站源下拉」专用列表适配器。
 *
 * <p>与 {@link SiteAdapter}（设置页 / 站点弹窗，带多选、可搜、可换源、网格模式）刻意分开：
 * 下拉菜单只需要「列出站源名 → 点一下切换」这一件事，不复用它的 18sp / 40dp 版式，
 * 也不引入 Setting 里的站点模式，避免互相牵连。数据源与旧标题栏一致：
 * {@code VodConfig.get().getSites()} 过滤掉 {@code isHide()} 的项。</p>
 *
 * <p><b>第 0 项固定为「切换站点」</b>（不占数据位）：确认键在站源列表左侧弹出点播地址
 * 历史二级面板。站源列表不为空时，紧跟其后插入第 1 项「站点编辑」（同样不占数据位），
 * 打开与设置页「点播地址」右侧 home 按钮同一个站点编辑弹窗；站源为空时该项整体不出现
 * ——此时面板只剩「切换站点」一项，用户仍然可以切换到别的点播地址。</p>
 *
 * <p>所以站源在数据里的下标 i 对应列表位置 i + 固定项数，{@link #indexOfCurrent()}
 * 已统一算入该偏移。</p>
 */
public class SiteMenuAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SWITCH = 0;
    private static final int TYPE_EDIT = 1;
    private static final int TYPE_SITE = 2;

    private final List<Site> items = new ArrayList<>();
    private final OnClickListener listener;
    private final Site current;
    /** 站源列表非空才插入「站点编辑」固定项。 */
    private final boolean hasSites;

    public interface OnClickListener {

        void onItemClick(Site item);

        /** 确认「切换站点」：弹出点播地址历史二级面板。 */
        void onSwitchClick();

        /** 确认「站点编辑」：弹出与设置页 home 按钮相同的站点编辑弹窗。 */
        void onEditClick();
    }

    /** 每次展开下拉时按最新站源列表重建，避免 notifyDataSetChanged 的全量失效重绘。 */
    public SiteMenuAdapter(List<Site> sites, Site current, OnClickListener listener) {
        items.addAll(sites);
        this.hasSites = !items.isEmpty();
        this.current = current;
        this.listener = listener;
    }

    /** 固定项数量：「切换站点」恒有，「站点编辑」只在站源非空时出现。 */
    private int fixedCount() {
        return hasSites ? 2 : 1;
    }

    /** 最长站源名的文本宽度（用与列表项同字号的 Paint 测量），供面板自适应宽度使用。 */
    public float maxTextWidth(TextPaint paint) {
        float max = 0;
        for (Site item : items) max = Math.max(max, paint.measureText(item.getName()));
        return max;
    }

    /** 当前站源在列表中的位置（含固定项偏移），供弹出时把焦点直接落到它上面。 */
    public int indexOfCurrent() {
        if (current == null) return 0;
        for (int i = 0; i < items.size(); i++) {
            if (TextUtils.equals(items.get(i).getKey(), current.getKey())) return i + fixedCount();
        }
        return 0;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) return TYPE_SWITCH;
        if (position == 1 && hasSites) return TYPE_EDIT;
        return TYPE_SITE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SWITCH || viewType == TYPE_EDIT) {
            // 两个固定项版式一致（箭头 + 文字 + 分割线），复用同一份布局；
            // 「站点编辑」在绑定期隐藏箭头（弹窗没有方向性）。
            return new ActionHolder(AdapterSiteMenuSwitchBinding.inflate(inflater, parent, false));
        }
        return new ViewHolder(AdapterSiteMenuBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        int type = getItemViewType(position);
        if (viewHolder instanceof ActionHolder) {
            ActionHolder holder = (ActionHolder) viewHolder;
            boolean edit = type == TYPE_EDIT;
            // 文字必须运行时写死：ViewHolder 复用 / 多语言切换后不能退化成只剩一个三角。
            holder.binding.text.setText(edit ? R.string.site_edit : R.string.site_switch_config);
            // 箭头用 INVISIBLE 而不是 GONE，保证两项文字左缘对齐。
            holder.binding.indicator.setVisibility(edit ? View.INVISIBLE : View.VISIBLE);
            /*
             * 分割线只由「站点编辑」显示：
             * - 它紧挨着「点播地址切换」，两者之间按设计不画线；
             * - 「站点编辑」与下方站源列表之间需要一条分隔线；
             * - 站源为空时只剩「点播地址切换」一行，也不该在面板底部留一条悬空的线。
             * 注意：divider 可见性会改变 item 高度（30dp ↔ 35dp），宿主按此推算列表高度，
             * 改这里的显隐规则要同步 HomeActivity / VodActivity 的 SITE_LIST_MAX。
             */
            holder.binding.divider.setVisibility(edit ? View.VISIBLE : View.GONE);
            holder.binding.switchRow.setOnClickListener(v -> {
                if (edit) listener.onEditClick();
                else listener.onSwitchClick();
            });
            return;
        }
        Site item = items.get(position - fixedCount());
        ViewHolder holder = (ViewHolder) viewHolder;
        boolean on = current != null && TextUtils.equals(item.getKey(), current.getKey());
        holder.binding.text.setText(item.getName());
        holder.binding.text.setTextColor(ContextCompat.getColor(holder.binding.text.getContext(), on ? R.color.white : R.color.white_70));
        holder.binding.getRoot().setSelected(on);
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    @Override
    public int getItemCount() {
        // 固定项（切换站点 / 站点编辑）+ 站源项
        return items.size() + fixedCount();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        final AdapterSiteMenuBinding binding;

        ViewHolder(@NonNull AdapterSiteMenuBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    static class ActionHolder extends RecyclerView.ViewHolder {

        private final AdapterSiteMenuSwitchBinding binding;

        ActionHolder(@NonNull AdapterSiteMenuSwitchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

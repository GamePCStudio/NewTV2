package com.fongmi.android.tv.ui.adapter;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.databinding.AdapterTypeBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class CollectAdapter extends RecyclerView.Adapter<CollectAdapter.ViewHolder> {

    private final List<Collect> mItems;
    private int mHit;
    private int mTotal;
    private int mSelected;

    public CollectAdapter() {
        mItems = new ArrayList<>();
    }

    public void add(Collect item) {
        mItems.add(item);
        notifyItemInserted(mItems.size() - 1);
    }

    public void setCount(int hit, int total) {
        mHit = hit;
        mTotal = total;
        if (!mItems.isEmpty()) notifyItemChanged(0);
    }

    public void select(int position) {
        if (position < 0 || position >= mItems.size() || mSelected == position) return;
        int previous = mSelected;
        mSelected = position;
        notifyItemChanged(previous);
        notifyItemChanged(position);
    }

    public void clear() {
        mItems.clear();
        mHit = 0;
        mSelected = 0;
        notifyDataSetChanged();
    }

    public Collect get(int position) {
        return mItems.get(position);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        AdapterTypeBinding binding = AdapterTypeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding, parent instanceof VerticalGridView);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Collect item = mItems.get(position);
        boolean vertical = holder.vertical;
        ViewGroup.LayoutParams params = holder.itemView.getLayoutParams();
        if (params == null) params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.width = vertical ? ResUtil.dp2px(160) : ViewGroup.LayoutParams.WRAP_CONTENT;
        params.height = vertical ? ResUtil.dp2px(48) : ViewGroup.LayoutParams.WRAP_CONTENT;
        holder.itemView.setLayoutParams(params);
        holder.binding.getRoot().setOnClickListener(null);
        holder.binding.text.setGravity(vertical ? Gravity.CENTER : Gravity.START | Gravity.CENTER_VERTICAL);
        holder.binding.text.setSingleLine(true);
        holder.binding.text.setText(position == 0
                ? holder.itemView.getContext().getString(R.string.collect_all_count, mHit, mTotal)
                : item.getSite().getName());
        holder.itemView.setSelected(position == mSelected);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterTypeBinding binding;
        private final boolean vertical;

        ViewHolder(@NonNull AdapterTypeBinding binding, boolean vertical) {
            super(binding.getRoot());
            this.binding = binding;
            this.vertical = vertical;
        }
    }
}

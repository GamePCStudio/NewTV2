package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.api.TmdbLog;
import com.fongmi.android.tv.databinding.ItemBlacklistBinding;
import com.fongmi.android.tv.utils.ArtBlacklist;

import java.util.ArrayList;
import java.util.List;

/**
 * 黑名单列表：一行一部影片，右侧是加入时间与移除提示。
 *
 * <p>选中整行按确认键即单条移除，用于纠正误封；清空整个名单仍可走「设置 → 清除缓存」。</p>
 */
public class BlacklistAdapter extends RecyclerView.Adapter<BlacklistAdapter.ViewHolder> {

    public interface OnRemoveListener {

        void onRemove(String title);
    }

    private final OnRemoveListener listener;
    private final List<ArtBlacklist.Entry> items = new ArrayList<>();

    public BlacklistAdapter(OnRemoveListener listener) {
        this.listener = listener;
    }

    public void submit(List<ArtBlacklist.Entry> entries) {
        items.clear();
        if (entries != null) items.addAll(entries);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemBlacklistBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position), listener);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemBlacklistBinding binding;

        ViewHolder(ItemBlacklistBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(ArtBlacklist.Entry entry, OnRemoveListener listener) {
            binding.itemTitle.setText(entry.getTitle());
            binding.itemTime.setText(TmdbLog.dateTime(entry.time));
            binding.getRoot().setOnClickListener(view -> listener.onRemove(entry.getTitle()));
        }
    }
}

package com.pigtv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.pigtv.R;
import com.pigtv.bean.MovieSort;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类页左侧分类列（v8）
 *
 * 只列站点 sort 的真实条目，不编数量——站点接口没有返回每类多少部，
 * 编一个「1,284 部」看着专业，实际是假数据，用户点进去数目对不上更糟。
 */
public class HomeCatAdapter extends RecyclerView.Adapter<HomeCatAdapter.VH> {

    public interface OnClick {
        /** @param sortIndex 在站点 sort 列表里的原始下标（不是列表里的位置） */
        void onClick(MovieSort.SortData sort, int sortIndex);
    }

    private final List<MovieSort.SortData> data = new ArrayList<>();
    private final List<Integer> indexMap = new ArrayList<>();
    private final OnClick click;
    /** 当前选中的是原始 sort 下标 */
    private int selected = 0;

    public HomeCatAdapter(OnClick click) {
        this.click = click;
    }

    /**
     * 「首页推荐」(my0) 不放进分类列——它是推荐流，不是分类，
     * 混在一堆分类里既占位置又让人以为点进去能看到分类内容。
     * 过滤掉之后列表位置和原始下标就对不上了，所以额外维护一份 indexMap。
     */
    public void setData(List<MovieSort.SortData> list) {
        data.clear();
        indexMap.clear();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                MovieSort.SortData s = list.get(i);
                if (s == null || "my0".equals(s.id)) continue;
                data.add(s);
                indexMap.add(i);
            }
        }
        notifyDataSetChanged();
    }

    public void setSelected(int sortIndex) {
        int old = selected;
        selected = sortIndex;
        int oldPos = positionOf(old);
        int newPos = positionOf(sortIndex);
        if (oldPos >= 0) notifyItemChanged(oldPos);
        if (newPos >= 0 && newPos != oldPos) notifyItemChanged(newPos);
    }

    private int positionOf(int sortIndex) {
        return indexMap.indexOf(sortIndex);
    }

    public MovieSort.SortData getItem(int position) {
        if (position < 0 || position >= data.size()) return null;
        return data.get(position);
    }

    public int sortIndexAt(int position) {
        if (position < 0 || position >= indexMap.size()) return 0;
        return indexMap.get(position);
    }

    public int positionOfSort(int sortIndex) {
        int p = positionOf(sortIndex);
        return p < 0 ? 0 : p;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_home_cat, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final VH h, int position) {
        final MovieSort.SortData sort = data.get(position);
        final int sortIndex = indexMap.get(position);
        h.tvCat.setText(sort.name);
        h.itemView.setSelected(sortIndex == selected);
        h.ivFilter.setVisibility(
                (sort.filters != null && !sort.filters.isEmpty()) ? View.VISIBLE : View.GONE);
        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (click != null) click.onClick(sort, sortIndex);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvCat;
        final ImageView ivFilter;

        VH(View v) {
            super(v);
            tvCat = v.findViewById(R.id.tvCat);
            ivFilter = v.findViewById(R.id.ivFilter);
        }
    }
}

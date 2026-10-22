package com.github.tvbox.osc.ui.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页入口行适配器（对应设计稿 .app）
 *
 * 四类入口混排：功能（历史/收藏/搜索）、分类（来自站点 sort 列表）、直播、问 AI。
 * 焦点态整卡变白由 drawable selector 负责；图标颜色每张卡不同，
 * 必须在焦点变化时手动切 tint，否则焦点态的深色规则只对文字生效。
 */
public class HomeAppAdapter extends RecyclerView.Adapter<HomeAppAdapter.VH> {

    public static final int KIND_FUNC = 0;
    public static final int KIND_SORT = 1;
    public static final int KIND_LIVE = 2;
    public static final int KIND_AI = 3;

    /** 焦点态图标色：设计稿 #0A0908 */
    private static final int COLOR_FOCUSED = 0xFF0A0908;

    public interface OnItemClick {
        void onItemClick(Entry entry, int position);
    }

    /** 焦点移到某张卡，用来驱动上方预览卡跟随 */
    public interface OnItemFocus {
        void onFocus(Entry entry, int position);
    }

    public static class Entry {
        public int kind;
        public String key;
        public String name;
        public String sub;
        public int icon;
        public int tint;
        /** 分类入口在 sortAdapter 中的下标，非分类为 -1 */
        public int sortIndex = -1;
        public MovieSort.SortData sort;
    }

    private final List<Entry> data = new ArrayList<>();
    private final OnItemClick clickListener;
    private OnItemFocus focusListener;

    public HomeAppAdapter(OnItemClick clickListener) {
        this.clickListener = clickListener;
    }

    public void setData(List<Entry> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public void setOnItemFocus(OnItemFocus listener) {
        this.focusListener = listener;
    }

    public Entry getItem(int position) {
        if (position < 0 || position >= data.size()) return null;
        return data.get(position);
    }

    public int size() {
        return data.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_home_app, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull final VH holder, int position) {
        final Entry e = data.get(position);
        final int pos = position;

        holder.ico.setImageResource(e.icon);
        holder.nm.setText(e.name);
        holder.sb.setText(e.sub);
        holder.itemView.setBackgroundResource(e.kind == KIND_AI ? R.drawable.h_app_ai_bg : R.drawable.h_app_bg);

        // 复用可能残留上一张卡的放大态，先归位
        holder.itemView.setScaleX(1f);
        holder.itemView.setScaleY(1f);
        holder.ico.setImageTintList(ColorStateList.valueOf(e.tint));

        holder.itemView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                holder.ico.setImageTintList(ColorStateList.valueOf(hasFocus ? COLOR_FOCUSED : e.tint));
                v.animate()
                        .scaleX(hasFocus ? 1.06f : 1f)
                        .scaleY(hasFocus ? 1.06f : 1f)
                        .setDuration(hasFocus ? 180 : 120)
                        .start();
                if (hasFocus && focusListener != null) {
                    focusListener.onFocus(e, pos);
                }
            }
        });

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (clickListener != null) clickListener.onItemClick(e, pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ico;
        TextView nm;
        TextView sb;

        VH(View v) {
            super(v);
            ico = v.findViewById(R.id.ico);
            nm = v.findViewById(R.id.nm);
            sb = v.findViewById(R.id.sb);
        }
    }
}

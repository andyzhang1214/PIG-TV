package com.pigtv.ui.adapter;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.pigtv.R;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class SelectDialogAdapter<T> extends ListAdapter<T, SelectDialogAdapter.SelectViewHolder> {

    private boolean muteCheck = false;

    static class SelectViewHolder extends RecyclerView.ViewHolder {

        public SelectViewHolder(@NonNull @NotNull View itemView) {
            super(itemView);
        }
    }

    public interface SelectDialogInterface<T> {
        void click(T value, int pos);

        String getDisplay(T val);
    }

    /**
     * 行尾徽章（可选）。站点选择弹窗用它显示体检结果，其它弹窗不设置就完全不显示，
     * 行为跟改动前一致。
     */
    public interface BadgeInterface<T> {
        /** 徽章文字，空串 = 不显示 */
        String badgeOf(T value);

        /** 徽章底色（ARGB），只在不显示时忽略 */
        int badgeColorOf(T value);
    }

    private BadgeInterface<T> badgeInterface = null;

    public void setBadgeInterface(BadgeInterface<T> badgeInterface) {
        this.badgeInterface = badgeInterface;
    }


    public static DiffUtil.ItemCallback<String> stringDiff = new DiffUtil.ItemCallback<String>() {

        @Override
        public boolean areItemsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        public boolean areContentsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
            return oldItem.equals(newItem);
        }
    };


    private ArrayList<T> data = new ArrayList<>();

    private int select = 0;

    private SelectDialogInterface dialogInterface = null;

    public SelectDialogAdapter(SelectDialogInterface dialogInterface, DiffUtil.ItemCallback diffCallback) {
        this(dialogInterface, diffCallback, false);
    }

    public SelectDialogAdapter(SelectDialogInterface dialogInterface, DiffUtil.ItemCallback diffCallback, boolean muteCheck) {
        super(diffCallback);
        this.dialogInterface = dialogInterface;
        this.muteCheck = muteCheck;
    }

    public void setData(List<T> newData, int defaultSelect) {
        data.clear();
        data.addAll(newData);
        select = defaultSelect;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return data.size();
    }


    @Override
    public SelectDialogAdapter.SelectViewHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        return new SelectViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_select, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull @NotNull SelectDialogAdapter.SelectViewHolder holder, @SuppressLint("RecyclerView") final int position) {
        final T value = data.get(position);
        String name = dialogInterface.getDisplay(value);
        boolean isSelected = !muteCheck && position == select;

        TextView tv = holder.itemView.findViewById(R.id.tvName);
        tv.setText(name);

        // 当前项不再靠「√ 」前缀，改成整块的 selected 态：背景金描边 + 右下金点
        holder.itemView.setSelected(isSelected);
        View dot = holder.itemView.findViewById(R.id.dot);
        if (dot != null) dot.setVisibility(isSelected ? View.VISIBLE : View.GONE);

        // 行尾徽章：没接体检的弹窗一律 GONE，行为和以前完全一样
        TextView badge = holder.itemView.findViewById(R.id.tvBadge);
        if (badge != null) {
            String badgeText = badgeInterface == null ? "" : badgeInterface.badgeOf(value);
            if (badgeText == null || badgeText.isEmpty()) {
                badge.setVisibility(View.GONE);
            } else {
                badge.setVisibility(View.VISIBLE);
                badge.setText(badgeText);
                // 同一个 shape 被所有行共用，先 mutate 再染色，否则染一行全变
                android.graphics.drawable.Drawable bg = badge.getBackground();
                if (bg != null) bg.mutate();
                badge.setBackgroundTintList(
                        ColorStateList.valueOf(badgeInterface.badgeColorOf(value)));
            }
        }

        final boolean current = isSelected;
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current) return;
                int old = select;
                select = position;
                notifyItemChanged(old);
                notifyItemChanged(select);
                dialogInterface.click(value, position);
            }
        });
    }
}
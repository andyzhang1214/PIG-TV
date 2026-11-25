package com.pigtv.ui.adapter;

import android.view.View;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.pigtv.R;
import com.pigtv.util.LiveSourcePresets;
import com.pigtv.util.LiveSourceStore;

import java.util.ArrayList;

/**
 * 推荐源弹窗的列表。
 *
 * <p>整行只有一个焦点点（不像源管理页那行还有「删除」）：推荐源不需要管理，
 * 点一下就是添加并切换，用户按方向键不用挑落点。
 *
 * <p>右侧那个小标签两种含义：已经在用的那条显示「当前」，其余带 EPG 的显示「带 EPG」。
 * 复用同一个 TextView 是因为两者不会同时出现 —— 当前那条的 EPG 信息已经不重要了。
 */
public class LiveSourcePresetAdapter extends BaseQuickAdapter<LiveSourcePresets.Preset, BaseViewHolder> {

    public interface OnPickListener {
        void onPick(LiveSourcePresets.Preset preset);
    }

    private OnPickListener listener;

    public LiveSourcePresetAdapter() {
        super(R.layout.item_live_source_preset, new ArrayList<>());
    }

    public void setOnPickListener(OnPickListener l) {
        this.listener = l;
    }

    @Override
    protected void convert(BaseViewHolder holder, final LiveSourcePresets.Preset item) {
        if (item == null) return;

        holder.setText(R.id.tvName, item.name);
        holder.setText(R.id.tvSub, item.desc);

        boolean current = item.url.equalsIgnoreCase(LiveSourceStore.currentUrl());
        TextView tag = holder.getView(R.id.tvEpg);
        if (current) {
            tag.setText(R.string.v8_src_current);
            tag.setVisibility(View.VISIBLE);
        } else if (item.nameStandard) {
            tag.setText(R.string.v8_src_preset_epg);
            tag.setVisibility(View.VISIBLE);
        } else {
            tag.setVisibility(View.GONE);
        }
        // selected 打在整个 itemView 上：背景和文字 selector 都靠它翻色
        holder.itemView.setSelected(current);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onPick(item);
            }
        });
    }
}

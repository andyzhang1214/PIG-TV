package com.pigtv.ui.adapter;

import android.graphics.Typeface;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.pigtv.R;
import com.pigtv.bean.LiveChannelGroup;

import java.util.ArrayList;


/**
 * 播放页切换面板左列：分组 + 台数。
 *
 * <p>文字颜色不再走主题色：面板压在视频上，主题色（浅色主题是粉色系）落在深底上
 * 会发灰，看起来像「未激活」。这里只用白的两档透明度区分「当前分组」和「其余」，
 * 焦点态交给 {@code bg_live_group_row} 的底色。
 */
public class LiveChannelGroupAdapter extends BaseQuickAdapter<LiveChannelGroup, BaseViewHolder> {

    /** 未选中的分组文字：比当前分组暗一档，扫视时不会和它抢注意力 */
    private static final int TX_IDLE = 0xFFC7CCD8;

    private int selectedGroupIndex = -1;
    private int focusedGroupIndex = -1;

    public LiveChannelGroupAdapter() {
        super(R.layout.item_live_group_row, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelGroup item) {
        TextView tvGroupName = holder.getView(R.id.tvGroupName);
        tvGroupName.setText(item.getGroupName());
        // 当前分组：白字 + 加粗。只靠 #C7CCD8 → 白 的色差在电视上几乎看不出来，
        // 加粗之后不用凑近看也知道自己在哪一组。
        boolean current = item.getGroupIndex() == selectedGroupIndex;
        tvGroupName.setTextColor(current ? 0xFFFFFFFF : TX_IDLE);
        tvGroupName.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);

        TextView tvCount = holder.getView(R.id.tvGroupCount);
        int count = item.getLiveChannels() == null ? 0 : item.getLiveChannels().size();
        tvCount.setText(String.valueOf(count));
    }

    public void setSelectedGroupIndex(int selectedGroupIndex) {
        if (selectedGroupIndex == this.selectedGroupIndex) return;
        int preSelectedGroupIndex = this.selectedGroupIndex;
        this.selectedGroupIndex = selectedGroupIndex;
        if (preSelectedGroupIndex != -1)
            notifyItemChanged(preSelectedGroupIndex);
        if (this.selectedGroupIndex != -1)
            notifyItemChanged(this.selectedGroupIndex);
    }

    public int getSelectedGroupIndex() {
        return selectedGroupIndex;
    }

    public void setFocusedGroupIndex(int focusedGroupIndex) {
        this.focusedGroupIndex = focusedGroupIndex;
        if (this.focusedGroupIndex != -1)
            notifyItemChanged(this.focusedGroupIndex);
        else if (this.selectedGroupIndex != -1)
            notifyItemChanged(this.selectedGroupIndex);
    }
}

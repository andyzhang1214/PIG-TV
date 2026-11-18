package com.pigtv.ui.adapter;

import android.graphics.Color;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.pigtv.R;
import com.pigtv.base.BaseActivity;
import com.pigtv.bean.LiveChannelItem;
import com.pigtv.util.FavoriteStore;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LiveChannelItemAdapter extends BaseQuickAdapter<LiveChannelItem, BaseViewHolder> {
    private int selectedChannelIndex = -1;
    private int focusedChannelIndex = -1;

    public LiveChannelItemAdapter() {
        super(R.layout.item_live_channel, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelItem item) {
        TextView tvChannelNum = holder.getView(R.id.tvChannelNum);
        TextView tvChannel = holder.getView(R.id.tvChannelName);
        tvChannelNum.setText(String.format("%s", item.getChannelNum()));
        tvChannel.setText(item.getChannelName());
        // 收藏的台挂一颗金星：追台时在几百条列表里靠它认，比记住频道号快。
        // 这里不走 setGone —— 本项目这份库的 setGone(id, true) 实际是「显示」，语义是反的。
        holder.getView(R.id.tvFav).setVisibility(
                FavoriteStore.isFav(item) ? android.view.View.VISIBLE : android.view.View.GONE);
        int channelIndex = item.getChannelIndex();
        if (channelIndex == selectedChannelIndex && channelIndex != focusedChannelIndex) {
            // takagen99: Added Theme Color
//            tvChannelNum.setTextColor(mContext.getResources().getColor(R.color.color_theme));
//            tvChannel.setTextColor(mContext.getResources().getColor(R.color.color_theme));
            tvChannelNum.setTextColor(((BaseActivity) mContext).getThemeColor());
            tvChannel.setTextColor(((BaseActivity) mContext).getThemeColor());
        }
        else{
            tvChannelNum.setTextColor(Color.WHITE);
            tvChannel.setTextColor(Color.WHITE);
        }
    }

    public void setSelectedChannelIndex(int selectedChannelIndex) {
        if (selectedChannelIndex == this.selectedChannelIndex) return;
        int preSelectedChannelIndex = this.selectedChannelIndex;
        this.selectedChannelIndex = selectedChannelIndex;
        if (preSelectedChannelIndex != -1)
            notifyItemChanged(preSelectedChannelIndex);
        if (this.selectedChannelIndex != -1)
            notifyItemChanged(this.selectedChannelIndex);
    }

    public void setFocusedChannelIndex(int focusedChannelIndex) {
        int preFocusedChannelIndex = this.focusedChannelIndex;
        this.focusedChannelIndex = focusedChannelIndex;
        if (preFocusedChannelIndex != -1)
            notifyItemChanged(preFocusedChannelIndex);
        if (this.focusedChannelIndex != -1)
            notifyItemChanged(this.focusedChannelIndex);
        else if (this.selectedChannelIndex != -1)
            notifyItemChanged(this.selectedChannelIndex);
    }
}
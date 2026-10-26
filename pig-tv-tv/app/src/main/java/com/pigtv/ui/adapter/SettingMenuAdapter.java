package com.pigtv.ui.adapter;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.pigtv.R;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class SettingMenuAdapter extends BaseQuickAdapter<String, BaseViewHolder> {
    private int selected = 0;

    public SettingMenuAdapter() {
        super(R.layout.item_setting_menu, new ArrayList<>());
    }

    /** 当前所在分组：焦点移走以后还得看得出「我在哪一页」 */
    public void setSelected(int index) {
        int old = selected;
        selected = index;
        if (old >= 0 && old < getItemCount()) notifyItemChanged(old);
        if (index >= 0 && index < getItemCount()) notifyItemChanged(index);
    }

    @Override
    protected void convert(BaseViewHolder helper, String item) {
        helper.setText(R.id.tvName, item);
        helper.addOnClickListener(R.id.tvName);
        helper.itemView.setSelected(helper.getAdapterPosition() == selected);
    }
}
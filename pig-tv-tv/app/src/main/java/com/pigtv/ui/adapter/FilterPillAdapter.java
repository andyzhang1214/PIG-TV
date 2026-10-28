package com.pigtv.ui.adapter;

import android.text.TextUtils;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.pigtv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 筛选弹层里一行胶囊的适配器（年份 / 地区 / 类型 / 排序 …）。
 *
 * 一个值两项：显示名 + 提交值。源站给的键值经常不一样 —— 显示「2024」，
 * 提交的却是 "2024" 或者 "year_2024"；直接拿显示名当参数发出去，
 * 十有八九筛不出东西。
 *
 * 选中状态不走 {@code setSelected} 之外的第二套逻辑：
 * item 根的 selector 只看 focused / selected 两个 state，
 * 所以这里只负责把 selected 打开或关掉，颜色和描边全交给 drawable。
 */
public class FilterPillAdapter extends BaseQuickAdapter<FilterPillAdapter.Opt, BaseViewHolder> {

    /** 一个筛选值 */
    public static class Opt {
        public final String name;
        public final String value;

        public Opt(String name, String value) {
            this.name = name == null ? "" : name;
            this.value = value == null ? "" : value;
        }
    }

    /** 当前选中值（"" = 全部） */
    private String selected = "";

    public FilterPillAdapter() {
        super(R.layout.item_filter_pill, new ArrayList<>());
    }

    public void setSelected(String value) {
        selected = value == null ? "" : value;
        notifyDataSetChanged();
    }

    public String getSelected() {
        return selected;
    }

    /**
     * 只改视觉、不 notify 的选中切换。
     *
     * 为什么不能用 notifyDataSetChanged：电视上焦点是「一个 View 的事」，
     * 一 notify 整个列表重绑，焦点当场丢掉 —— 实测按 OK 选完「华语」，
     * 焦点会跳到右上角「清空」上，用户得重新走回来才能选下一组。
     * 这里直接遍历可见子 View 改 selected，焦点原地不动。
     */
    public void setSelectedQuiet(RecyclerView rv, String value) {
        selected = value == null ? "" : value;
        if (rv == null) {
            notifyDataSetChanged();
            return;
        }
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            int pos = rv.getChildAdapterPosition(child);
            if (pos < 0 || pos >= getData().size()) continue;
            child.setSelected(TextUtils.equals(selected, getData().get(pos).value));
        }
    }

    /** 已选项在这行里的下标，没有就 0（第一项永远是「全部」） */
    public int selectedIndex() {
        List<Opt> data = getData();
        for (int i = 0; i < data.size(); i++) {
            if (TextUtils.equals(data.get(i).value, selected)) return i;
        }
        return 0;
    }

    @Override
    protected void convert(BaseViewHolder helper, Opt item) {
        helper.setText(R.id.filterPill, item.name);
        helper.itemView.setSelected(TextUtils.equals(selected, item.value));
    }
}

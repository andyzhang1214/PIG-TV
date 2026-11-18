package com.pigtv.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.pigtv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播页左栏（分组导航）的适配器。
 *
 * <p>一行一个入口，三种形态共用一套逻辑：
 * <ul>
 *   <li>「全部」「收藏」带一枚矢量图标；</li>
 *   <li>「超清专区」的图标位换成一枚金色 4K 小牌；</li>
 *   <li>「央视频道」这类分组没有图标，靠图标位的固定宽度保持缩进对齐。</li>
 * </ul>
 * 中间还夹一行不可聚焦的「分组」小标题（{@link #TYPE_LABEL}），把「全部 / 收藏 / 超清」
 * 和真实分组分开 —— 设计稿里就是这么分的，前三个是视图，后面是源里的分组。
 *
 * <p>选中态只改视觉、不 notify（{@link #setSelectedQuiet}）：电视上一 notify
 * 整个列表重绑，焦点当场丢掉，用户按完左右还得重新找焦点。
 */
public class LiveRailAdapter extends BaseQuickAdapter<LiveRailAdapter.Row, BaseViewHolder> {

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_LABEL = 1;

    /** 左栏的一行 */
    public static class Row {
        public final String name;
        /** 提交值："" = 全部，FAV/K4 是聚合视图，其余是分组下标 */
        public final String value;
        /** 右侧数量，空串表示不显示；收藏后会变，不能只改屏幕上的 TextView */
        public String num;
        /** 图标资源，0 表示没有 */
        public final int icon;
        /** 「超清专区」那行：图标位画 4K 小牌 */
        public final boolean is4k;
        /** 「分组」这种小标题：不可聚焦、不参与选中 */
        public final boolean label;

        public Row(String name, String value, String num, int icon, boolean is4k, boolean label) {
            this.name = name == null ? "" : name;
            this.value = value == null ? "" : value;
            this.num = num == null ? "" : num;
            this.icon = icon;
            this.is4k = is4k;
            this.label = label;
        }

        public static Row entry(String name, String value, String num, int icon, boolean is4k) {
            return new Row(name, value, num, icon, is4k, false);
        }

        public static Row label(String text) {
            return new Row(text, "", "", 0, false, true);
        }
    }

    /** 当前选中值（"" = 全部） */
    private String selected = "";

    public LiveRailAdapter() {
        super(new ArrayList<Row>());
    }

    @Override
    protected int getDefItemViewType(int position) {
        Row row = getData().get(position);
        return row != null && row.label ? TYPE_LABEL : TYPE_ITEM;
    }

    @Override
    protected BaseViewHolder onCreateDefViewHolder(ViewGroup parent, int viewType) {
        return createBaseViewHolder(parent,
                viewType == TYPE_LABEL ? R.layout.item_rail_label : R.layout.item_live_rail);
    }

    public void setSelected(String value) {
        selected = value == null ? "" : value;
        notifyDataSetChanged();
    }

    public String getSelected() {
        return selected;
    }

    /** 只改视觉、不 notify 的选中切换（原因见类注释） */
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
            Row row = getData().get(pos);
            if (row != null && row.label) continue;
            child.setSelected(TextUtils.equals(selected, row == null ? "" : row.value));
        }
    }

    /**
     * 只改某一项的右侧数量，不动选中态、不 notify。
     *
     * <p>收藏 / 取消收藏之后「收藏」那行的数字要跟着走，否则用户得切一次分组
     * 才看到数字更新（而切分组要重铺整表，焦点又会丢）。
     */
    public void setNumQuiet(RecyclerView rv, String value, String num) {
        String next = num == null ? "" : num;
        // 先改数据，再改可见 View。否则目标行滚出屏幕后，convert() 会拿旧 num 绑回去。
        for (Row row : getData()) {
            if (row != null && TextUtils.equals(row.value, value)) {
                row.num = next;
                break;
            }
        }
        if (rv == null) return;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            int pos = rv.getChildAdapterPosition(child);
            if (pos < 0 || pos >= getData().size()) continue;
            Row row = getData().get(pos);
            if (row == null || !TextUtils.equals(row.value, value)) continue;
            View numView = child.findViewById(R.id.tvRailNum);
            if (numView instanceof android.widget.TextView) {
                ((android.widget.TextView) numView).setText(next);
            }
            return;
        }
    }

    /** 已选项在列表里的下标，没有就 0（第一项永远是「全部」） */
    public int selectedIndex() {
        List<Row> data = getData();
        for (int i = 0; i < data.size(); i++) {
            if (TextUtils.equals(data.get(i).value, selected)) return i;
        }
        return 0;
    }

    @Override
    protected void convert(BaseViewHolder helper, Row item) {
        if (item.label) {
            helper.setText(R.id.tvRailLabel, item.name);
            return;
        }

        helper.setText(R.id.tvRailName, item.name);
        helper.setText(R.id.tvRailNum, item.num);

        View icon = helper.getView(R.id.ivRailIcon);
        View badge = helper.getView(R.id.tvRail4k);
        // 三个可见性都直接设：holder 是复用的，少设一个就会带着上一行的图标
        if (item.icon != 0) {
            ((android.widget.ImageView) icon).setImageResource(item.icon);
            icon.setVisibility(View.VISIBLE);
            badge.setVisibility(View.GONE);
        } else if (item.is4k) {
            icon.setVisibility(View.GONE);
            badge.setVisibility(View.VISIBLE);
        } else {
            icon.setVisibility(View.GONE);
            badge.setVisibility(View.GONE);
        }

        helper.itemView.setSelected(TextUtils.equals(selected, item.value));
    }
}

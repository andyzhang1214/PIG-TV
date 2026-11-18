package com.pigtv.ui.adapter;

import android.view.View;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.pigtv.R;
import com.pigtv.util.LiveSourceStore;

import java.util.ArrayList;

/**
 * 直播源管理页的列表：内置源打头，后面是用户自己加的，最后不在这里 ——
 * 「添加」是页面底部的独立按钮，不走列表，省得为一行按钮搞多类型 adapter。
 *
 * <p>整行 OK = 切到这条源；右侧「删除」= 删（带二次确认）。两个可聚焦点，
 * 内置源那行的「删除」直接 GONE，用户按 → 也到不了，不会出现「删了内置源」这种事。
 */
public class LiveSourceAdapter extends BaseQuickAdapter<LiveSourceStore.Source, BaseViewHolder> {

    public interface OnActionListener {
        /** 整行被点：切到这条源 */
        void onPick(LiveSourceStore.Source source);

        /** 行内「删除」被点 */
        void onDelete(LiveSourceStore.Source source);
    }

    private OnActionListener listener;

    public LiveSourceAdapter() {
        super(R.layout.item_live_source, new ArrayList<>());
    }

    public void setOnActionListener(OnActionListener l) {
        this.listener = l;
    }

    @Override
    protected void convert(BaseViewHolder holder, final LiveSourceStore.Source item) {
        if (item == null) return;

        holder.setText(R.id.tvName, item.displayName());
        holder.setText(R.id.tvSub, item.displaySub());
        holder.setText(R.id.tvTag, item.isBuiltin()
                ? holder.itemView.getContext().getString(R.string.v8_src_tag_builtin)
                : holder.itemView.getContext().getString(R.string.v8_src_tag_custom));

        // 当前生效：金描边 + 右侧「当前」标记。selected 直接打在整个 itemView 上，
        // 背景和文字 selector 都靠它翻色 —— 打到子 View 上不会往上传。
        holder.itemView.setSelected(LiveSourceStore.isCurrent(item));
        holder.setVisible(R.id.tvCurrent, LiveSourceStore.isCurrent(item));

        // 内置源不可删：按钮直接消失，焦点也搜不到它
        View del = holder.getView(R.id.btnDel);
        del.setVisibility(item.isBuiltin() ? View.GONE : View.VISIBLE);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onPick(item);
            }
        });
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onDelete(item);
            }
        });
    }
}

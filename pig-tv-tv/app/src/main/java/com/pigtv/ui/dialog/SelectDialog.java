package com.pigtv.ui.dialog;

import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pigtv.R;
import com.pigtv.ui.adapter.SelectDialogAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class SelectDialog<T> extends BaseDialog {

    private boolean muteCheck = false;

    /** 交给调用方拿来做增量刷新（例如站点体检出结果后只刷某一行） */
    private SelectDialogAdapter<T> adapter = null;

    /** 底部「重新检测」：只有站点体检弹窗会打开它，其它弹窗一直是 GONE */
    private TextView refreshBtn = null;

    /** 最近一次停留在列表里的那一行，从按钮按 ↑ 时回到这里 */
    private View lastRowFocus = null;

    private TvRecyclerView listView = null;

    /** 底部提示的原始文案，体检结束要还原 */
    private CharSequence hintDefault = null;

    public SelectDialogAdapter<T> getAdapter() {
        return adapter;
    }

    public SelectDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_select);
    }

    public SelectDialog(@NonNull @NotNull Context context, int resId) {
        super(context);
        setContentView(resId);
    }

    public void setItemCheckDisplay(boolean shouldShowCheck) {
        muteCheck = !shouldShowCheck;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public void setTip(String tip) {
        ((TextView) findViewById(R.id.title)).setText(tip);
    }

    /**
     * 改底部提示。传 null / 空串 = 还原成默认那行按键说明。
     */
    public void setHint(CharSequence hint) {
        TextView tv = findViewById(R.id.dlgHint);
        if (tv == null) return;
        if (hintDefault == null) hintDefault = tv.getText();
        tv.setText(hint == null || hint.length() == 0 ? hintDefault : hint);
    }

    /**
     * 打开底部「重新检测」按钮。不调用就一直是 GONE，其它弹窗的布局和焦点跟以前一样。
     */
    public void setRefreshAction(final Runnable action) {
        if (action == null) return;
        refreshBtn = findViewById(R.id.btnRefresh);
        if (refreshBtn == null) return;
        refreshBtn.setVisibility(View.VISIBLE);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
    }

    public void setAdapter(TvRecyclerView tvRecyclerView, SelectDialogAdapter.SelectDialogInterface<T> sourceBeanSelectDialogInterface, DiffUtil.ItemCallback<T> sourceBeanItemCallback, List<T> data, int select) {
        if (select >= data.size() || select < 0) select = 0;//if source update, data item count maybe smaller than before
        final int selectIdx = select;
        SelectDialogAdapter<T> adapter = new SelectDialogAdapter<>(sourceBeanSelectDialogInterface, sourceBeanItemCallback, muteCheck);
        adapter.setData(data, select);
        this.adapter = adapter;
        if(tvRecyclerView == null){
            tvRecyclerView = findViewById(R.id.list);
        }
        this.listView = tvRecyclerView;
        tvRecyclerView.setAdapter(adapter);
        tvRecyclerView.setSelectedPosition(select);
        if (select<10){
            tvRecyclerView.setSelection(select);
        }
        TvRecyclerView finalTvRecyclerView = tvRecyclerView;
        tvRecyclerView.post(new Runnable() {
            @Override
            public void run() {//不清楚会不会存在什么问题
                if (selectIdx >= 10) {
                    finalTvRecyclerView.smoothScrollToPosition(selectIdx);
                    finalTvRecyclerView.setSelectionWithSmooth(selectIdx);
                }
            }
        });
    }

    // =====================================================================
    //  遥控器：列表最后一行的 ↓ 落到「重新检测」，按钮上的 ↑ 回到列表
    // =====================================================================

    /**
     * 不用系统的 focusSearch 是有原因的：TvRecyclerView 在网格最后一行按 ↓
     * 经常直接把自己滚动到边界然后吞掉按键，焦点压根走不到下面那个按钮。
     * 这里只在「按钮可见 + 当前行确实是最后一行」时接管，其它情况全部放行。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (refreshBtn == null || refreshBtn.getVisibility() != View.VISIBLE
                || event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        View focus = getCurrentFocus();
        int code = event.getKeyCode();

        if (code == KeyEvent.KEYCODE_DPAD_DOWN && focus != refreshBtn && isOnLastRow(focus)) {
            return refreshBtn.requestFocus();
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP && focus == refreshBtn) {
            View target = lastRowFocus;
            if (target == null || target.getParent() == null) {
                target = listView == null || listView.getChildCount() == 0
                        ? null : listView.getChildAt(0);
            }
            if (target != null && target.isShown() && target.requestFocus()) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** 当前焦点所在行是不是网格最后一行（凑不满一行时，最后那一行也算） */
    private boolean isOnLastRow(View focus) {
        if (listView == null || focus == null) return false;
        View child = focus;
        while (child != null && child.getParent() != listView) {
            if (!(child.getParent() instanceof View)) return false;
            child = (View) child.getParent();
        }
        if (child == null) return false;
        lastRowFocus = child;

        int pos;
        try {
            pos = listView.getChildViewHolder(child).getLayoutPosition();
        } catch (Throwable e) {
            return false;
        }
        int count = listView.getAdapter() == null ? 0 : listView.getAdapter().getItemCount();
        if (pos < 0 || count <= 0) return false;

        int span = 1;
        RecyclerView.LayoutManager lm = listView.getLayoutManager();
        if (lm instanceof GridLayoutManager) span = ((GridLayoutManager) lm).getSpanCount();
        return pos + span >= count;
    }
}

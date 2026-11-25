package com.pigtv.ui.dialog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.pigtv.R;
import com.pigtv.ui.adapter.LiveSourcePresetAdapter;
import com.pigtv.util.LiveSourcePresets;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

/**
 * 推荐源弹窗：列几条公开聚合源，点一条就回调。
 *
 * <p>只做「选哪条」，落盘、切换、重启都不在这里 —— 调用方走的是和手动添加
 * 完全相同的一条路径（{@link com.pigtv.util.LiveSourceStore#add} → apply →
 * 重启），这样推荐源不需要有自己的一套代码，出问题也只可能出在那条公共路径上。
 *
 * <p>焦点要重试：Dialog 的窗口拿到焦点比 onCreate 晚，直接 requestFocus 会安静地
 * 返回 false，用户打开弹窗后方向键毫无反应。和源管理页的 focusInitial 同一个招式。
 */
public class LiveSourcePresetDialog extends BaseDialog {

    private static final int MAX_FOCUS_TRY = 8;

    public interface OnPickListener {
        void onPick(LiveSourcePresets.Preset preset);
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TvRecyclerView mList;
    private OnPickListener listener;

    public LiveSourcePresetDialog(@NonNull Context context) {
        super(context);
        setContentView(R.layout.dialog_live_source_preset);

        mList = findViewById(R.id.mPresetList);
        LiveSourcePresetAdapter adapter = new LiveSourcePresetAdapter();
        mList.setAdapter(adapter);
        adapter.setOnPickListener(new LiveSourcePresetAdapter.OnPickListener() {
            @Override
            public void onPick(LiveSourcePresets.Preset preset) {
                if (listener != null) listener.onPick(preset);
                dismiss();
            }
        });
        adapter.setNewData(LiveSourcePresets.all());

        findViewById(R.id.btnCancel).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                dismiss();
            }
        });

        focusFirst(0);
    }

    public void setOnPickListener(OnPickListener l) {
        this.listener = l;
    }

    private void focusFirst(final int attempt) {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mList == null) return;
                // 窗口还没拿到焦点时必须「继续排下一次」，不能直接 return：
                // 构造里就发起了第一次探测，那一刻 isShowing() 必然是 false，
                // 一 return 就再也不会重试，焦点永远停在 RecyclerView 本身 ——
                // 表现就是弹窗打开后三个条目全是暗的，要按一下方向键才亮。
                if (!isShowing()) {
                    if (attempt < MAX_FOCUS_TRY) focusFirst(attempt + 1);
                    return;
                }
                RecyclerView.ViewHolder holder = mList.findViewHolderForAdapterPosition(0);
                if (holder != null && holder.itemView.requestFocus()) return;
                if (attempt < MAX_FOCUS_TRY) {
                    focusFirst(attempt + 1);
                    return;
                }
                mList.requestFocus();
            }
        }, 120);
    }
}

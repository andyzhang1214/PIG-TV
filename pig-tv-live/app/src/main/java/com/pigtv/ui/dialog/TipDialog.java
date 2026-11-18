package com.pigtv.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.pigtv.R;

import org.jetbrains.annotations.NotNull;

public class TipDialog extends BaseDialog {

    public TipDialog(@NonNull @NotNull Context context, String tip, String left, String right, OnListener listener) {
        this(context, R.layout.dialog_tip, tip, left, right, listener);
    }

    /**
     * 指定布局的版本：深色电视页（直播源管理）用 {@code R.layout.dialog_tip_v8}。
     *
     * <p>两份布局的 id 完全一样（tipInfo / leftBtn / rightBtn），所以这里不用改逻辑，
     * 只是换皮 —— 老页面继续走原来的浅色卡片，不会跟着变样。
     */
    public TipDialog(@NonNull @NotNull Context context, int layoutRes, String tip,
                     String left, String right, OnListener listener) {
        super(context);
        setContentView(layoutRes);
        setCanceledOnTouchOutside(false);
        TextView tipInfo = findViewById(R.id.tipInfo);
        TextView leftBtn = findViewById(R.id.leftBtn);
        TextView rightBtn = findViewById(R.id.rightBtn);
        tipInfo.setText(tip);
        leftBtn.setText(left);
        rightBtn.setText(right);
        leftBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.left();
            }
        });
        rightBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.right();
            }
        });
        setOnCancelListener(new OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                listener.cancel();
            }
        });
    }

    public interface OnListener {
        void left();

        void right();

        void cancel();
    }
}

package com.pigtv.ui.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.pigtv.R;
import com.pigtv.util.LiveSourceStore;

import org.jetbrains.annotations.NotNull;

/**
 * 添加直播源的输入弹窗。
 *
 * <p>只负责「收两个字符串」，不落盘也不切换 —— 校验和保存都交给调用页，
 * 这样同一个弹窗以后想改成「编辑」也能复用。
 *
 * <p>焦点直接给地址行：名字留空会自动取文件名，真正必填的只有地址。
 * 用户按 ↑ 才回名字那行，省下一次方向键。
 */
public class LiveSourceAddDialog extends BaseDialog {

    public interface OnSubmitListener {
        /** 用户点了「保存并切换」。{@code name} 可能为空串，{@code url} 已 trim */
        void onSubmit(String name, String url);
    }

    private EditText etName;
    private EditText etUrl;
    private OnSubmitListener listener;

    public LiveSourceAddDialog(@NonNull @NotNull Context context, String webUrl) {
        super(context);
        setContentView(R.layout.dialog_live_source_add);

        // 输入法要顶起来，不能盖住输入框：以前这些弹窗都是固定居中的，
        // 系统键盘一冒出来就把下半截吃掉了。
        if (getWindow() != null) {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        etName = findViewById(R.id.etName);
        etUrl = findViewById(R.id.etUrl);

        TextView tip = findViewById(R.id.tvTip);
        tip.setText(context.getString(R.string.v8_src_dlg_tip,
                TextUtils.isEmpty(webUrl) ? "9978 端口" : webUrl));

        findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        findViewById(R.id.btnOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });
    }

    public void setOnSubmitListener(OnSubmitListener l) {
        this.listener = l;
    }

    private void submit() {
        String url = etUrl.getText() == null ? "" : etUrl.getText().toString().trim();
        String name = etName.getText() == null ? "" : etName.getText().toString().trim();
        if (!LiveSourceStore.isValidUrl(url)) {
            Toast.makeText(getContext(), R.string.v8_src_bad_url, Toast.LENGTH_SHORT).show();
            etUrl.requestFocus();
            return;
        }
        if (listener != null) listener.onSubmit(name, url);
    }

    @Override
    public void show() {
        super.show();
        if (etUrl != null) etUrl.requestFocus();
    }
}

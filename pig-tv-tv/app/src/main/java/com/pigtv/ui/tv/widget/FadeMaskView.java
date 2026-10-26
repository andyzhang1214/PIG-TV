package com.pigtv.ui.tv.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 顶部 / 底部渐隐遮罩。
 *
 * 为什么不用 drawable 的 &lt;gradient&gt;：同样一份 shape（angle + 透明端 + 黑端）
 * 在 TV 模拟器上渲染出来是全透明的 —— View 占着位置（dump 得到 bounds），
 * 但一个像素都不画，内容照样压在顶栏文字上。用 Canvas 手画 shader 没有这个问题。
 *
 * app:fadeEdge="top"（默认）顶部不透明、往下渐透明；"bottom" 反过来。
 */
public class FadeMaskView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int peakColor = 0xFF000000;
    private boolean fromTop = true;

    public FadeMaskView(Context context) {
        this(context, null);
    }

    public FadeMaskView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FadeMaskView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        paint.setStyle(Paint.Style.FILL);
        if (attrs != null) {
            String edge = attrs.getAttributeValue(
                    "http://schemas.android.com/apk/res-auto", "fadeEdge");
            if (edge == null) {
                // 没有 app 命名空间时也认 android:tag 写死的 "bottom"
                edge = attrs.getAttributeValue(null, "tag");
            }
            fromTop = !"bottom".equalsIgnoreCase(edge);
        }
        setWillNotDraw(false);
    }

    /** 顶部不透明的程度（0-255），默认全黑 */
    public void setPeakAlpha(int alpha) {
        peakColor = (alpha & 0xFF) << 24;
        rebuild();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    private void rebuild() {
        int h = getHeight();
        if (h <= 0) return;
        int solid = peakColor;
        int clear = peakColor & 0x00FFFFFF;
        // 不用「从一端直线到另一端」：顶栏那 50 多毫米必须实心黑，
        // 否则压不下去；但整条又要够短，不能把下面第一行内容也蒙住。
        // 所以前 40% 保持不透明，后 60% 才渐变 —— 视觉上就是「顶部一堵黑墙 + 一段过渡」。
        int[] colors;
        float[] pos;
        if (fromTop) {
            colors = new int[]{solid, solid, clear};
            pos = new float[]{0f, 0.40f, 1f};
        } else {
            colors = new int[]{clear, clear, solid};
            pos = new float[]{0f, 0.60f, 1f};
        }
        paint.setShader(new LinearGradient(0, 0, 0, h, colors, pos, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (paint.getShader() == null) rebuild();
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }
}

package com.pigtv.ui.tv.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;

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
    /** XML 有没有显式指定过方向；没指定就在 layout 后按位置自己判 */
    private boolean edgeFixed = false;

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
            // 1) 首选 app:fadeEdge="bottom"（res-auto）
            String edge = attrs.getAttributeValue(
                    "http://schemas.android.com/apk/res-auto", "fadeEdge");
            if (edge == null) {
                // 2) 退而认 android:tag="bottom"。
                //    注意必须带 android 命名空间：android:tag 编译后挂在
                //    http://schemas.android.com/apk/res/android 下，
                //    用 getAttributeValue(null, "tag") 只会拿到 null ——
                //    2026-11 电视上那条「黑线」就是这里读不到导致的：
                //    bottomFade 退化成默认的「顶部实黑、往下渐透明」，
                //    于是屏幕底部 948~1000 被蒙成一条 53px 的纯黑横带。
                edge = attrs.getAttributeValue(
                        "http://schemas.android.com/apk/res/android", "tag");
            }
            if (edge == null && getTag() instanceof String) {
                // 3) 最后兜到运行时 tag（super() 里已从 android:tag 写好）
                edge = (String) getTag();
            }
            // 4) 兼容老写法：若某个版本把 fadeEdge 声明成 enum，
            //    AttributeSet 里拿到的是序号 "1" 而不是 "bottom"
            if (edge != null && edge.matches("[01]")) {
                edge = "1".equals(edge) ? "bottom" : "top";
            }
            if (edge != null) {
                fromTop = !"bottom".equalsIgnoreCase(edge);
                edgeFixed = true;
            }
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
        inferEdgeIfNeeded();
        rebuild();
    }

    /**
     * 没显式写方向时按位置判断：底边贴父容器底边 → 当作底部遮罩。
     * 这样即使以后有人新加一个遮罩忘了写 app:fadeEdge，
     * 也不会再出现「底部被蒙成一条纯黑横带」。
     */
    private void inferEdgeIfNeeded() {
        if (edgeFixed) return;
        ViewParent p = getParent();
        if (!(p instanceof View)) return;
        int parentH = ((View) p).getHeight();
        if (parentH <= 0) return;
        // 底边与父底对齐（容差 2px）就认定为底部
        boolean atBottom = Math.abs(getBottom() - parentH) <= 2;
        if (atBottom) {
            fromTop = false;
            rebuild();
        }
    }

    private void rebuild() {
        int h = getHeight();
        if (h <= 0) return;
        int solid = peakColor;
        int clear = peakColor & 0x00FFFFFF;
        // 不用「从一端直线到另一端」：顶栏那 50 多毫米必须实心黑，
        // 否则压不下去；但整条又要够短，不能把下面第一行内容也蒙住。
        // 所以靠边的一段保持不透明，往里才渐变 —— 视觉上就是「一堵黑墙 + 一段过渡」。
        //
        // 底部（fromTop=false）的实黑点定在 0.72：首页海报墙最后一行在屏底前约
        // 45px 处被裁（recGrid 的 marginBottom），实黑点要盖到那条裁切线上，
        // 否则仍然看得见一道硬边。渐变段留 (0.72-0.30)*h，足够柔。
        int[] colors;
        float[] pos;
        if (fromTop) {
            colors = new int[]{solid, solid, clear};
            pos = new float[]{0f, 0.40f, 1f};
        } else {
            colors = new int[]{clear, clear, solid};
            pos = new float[]{0f, 0.30f, 0.72f};
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

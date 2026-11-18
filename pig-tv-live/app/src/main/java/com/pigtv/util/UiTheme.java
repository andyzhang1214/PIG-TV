package com.pigtv.util;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

import com.pigtv.R;

/**
 * 界面明暗的运行时判定。
 *
 * <p>「界面明暗」在设置里是 a 主题（{@code Theme.PigTV.Light} / 深色那一套），
 * 代码里没有统一开关，能拿到 Context 的地方就解析一次 {@code ?attr/uiBg} 的亮度 ——
 * 深色 #0B0A0C、浅色 #F4F4F7，差得很开，靠亮度判断不会错。
 * 台标的黑版、占位图配色都要靠它决定。
 */
public final class UiTheme {

    private UiTheme() {
    }

    public static boolean isLight(Context ctx) {
        if (ctx == null) return false;
        Resources.Theme theme = ctx.getTheme();
        if (theme == null) return false;
        TypedValue tv = new TypedValue();
        if (!theme.resolveAttribute(R.attr.uiBg, tv, true)) return false;
        int c = tv.data;
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 128;
    }
}

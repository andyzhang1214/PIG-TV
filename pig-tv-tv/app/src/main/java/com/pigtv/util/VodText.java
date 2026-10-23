package com.pigtv.util;

import android.text.TextUtils;

/**
 * 角标文案清洗：豆瓣/站点返回的 remarks 里混着大量无信息量的值
 * （"0"、"0.0"、纯空白），原样贴到海报上就是一排脏角标。
 * 统一在这里判定"要不要显示"，两个适配器共用。
 */
public class VodText {

    /** 返回可展示的备注文案；无信息量时返回 null（调用方据此隐藏角标） */
    public static String showableNote(String note) {
        if (TextUtils.isEmpty(note)) return null;
        String s = note.trim();
        if (s.isEmpty()) return null;
        // "0" / "0.0" / "0.00"…：没有评分就是没有，别拿占位符糊海报
        if (s.matches("0+(\\.0+)?")) return null;
        return s;
    }
}

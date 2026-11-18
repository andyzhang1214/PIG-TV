package com.pigtv.util;

import android.text.TextUtils;

/**
 * 封面文案清洗：豆瓣/站点返回的 remarks 里混着大量无信息量的值
 * （"0"、"0.0"、"未知"、"无"、纯空白），原样贴到海报上就是一排脏角标。
 * 统一在这里判定"要不要显示"，两个适配器共用。
 */
public class VodText {

    /** 封面左下角那行最多留几个字，再长就截（手机上也是这个口径） */
    private static final int COVER_STAT_MAX = 26;

    /** 返回可展示的备注文案；无信息量时返回 null（调用方据此隐藏角标） */
    public static String showableNote(String note) {
        if (TextUtils.isEmpty(note)) return null;
        String s = note.trim();
        if (s.isEmpty()) return null;
        // "0" / "0.0" / "0.00"…：没有评分就是没有，别拿占位符糊海报
        if (s.matches("0+(\\.0+)?")) return null;
        return s;
    }

    /**
     * 封面左下角那一行（和手机端 .pcB 完全一致的取值口径）：
     * <pre>
     *   有可用备注（「更新至 12 集」「评分 7.5」）→ 用备注
     *   没有备注                                → 「类型 · 年份 · 地区」
     *   两样都没有                              → 空串（调用方隐藏这行）
     * </pre>
     * 备注比元信息更有用：它说的是「这部片现在能不能看、看到第几集」。
     *
     * @param note 站点备注（Movie.Video.note）
     * @param type 类型，如「动作片」
     * @param year 年份，&lt;= 0 表示没有
     * @param area 地区，如「大陆」
     */
    public static String coverStat(String note, String type, int year, String area) {
        String n = showableNote(note);
        if (n != null && !isBlankWord(n)) return cut(n);

        StringBuilder sb = new StringBuilder();
        if (!isBlankWord(type)) sb.append(type.trim());
        if (year > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(year);
        }
        if (!isBlankWord(area)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(area.trim());
        }
        return cut(sb.toString());
    }

    /** 站点用这些字面值表示"没有"，跟空串一样对待 */
    private static boolean isBlankWord(String s) {
        if (TextUtils.isEmpty(s)) return true;
        String t = s.trim();
        return t.isEmpty()
                || "未知".equals(t)
                || "无".equals(t)
                || "null".equalsIgnoreCase(t)
                || "undefined".equalsIgnoreCase(t);
    }

    private static String cut(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= COVER_STAT_MAX) return t;
        return t.substring(0, COVER_STAT_MAX);
    }
}

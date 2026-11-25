package com.pigtv.util.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.StringReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TxtSubscribe {

    private static final Pattern NAME_PATTERN = Pattern.compile(".*,(.+?)$");
    private static final Pattern GROUP_PATTERN = Pattern.compile("group-title=\"(.*?)\"");
    private static final Pattern LOGO_PATTERN = Pattern.compile("tvg-logo=\"([^\"]*)\"");

    /**
     * 一个频道：线路表 + 源自带的台标地址。
     *
     * <p>为什么要把台标一起带出来：{@link com.pigtv.util.ChannelLogo} 只能靠台名去猜
     * 公共台标库（fanmingming 那套 930 个文件），央视、卫视、省台猜得中，
     * 县级台（「象山综合」「苍南新闻综合」）库里压根没有 —— 实测 589 个频道只对上 193 个。
     * 而聚合源的 m3u 里本来就写着 {@code tvg-logo="…"}（Guovin 那份 589 条一条不缺），
     * 解析层以前直接把它丢了，等于把现成的台标扔掉再去猜。
     */
    public static class Channel {
        public final ArrayList<String> urls = new ArrayList<>();
        /** 源自带的台标地址，可能为空 */
        public String logo;
    }

    public static void parse(LinkedHashMap<String, LinkedHashMap<String, Channel>> linkedHashMap, String str) {
        if (str.startsWith("#EXTM3U")) {
            parseM3u(linkedHashMap, str);
        } else {
            parseTxt(linkedHashMap, str);
        }
    }

    private static void parseM3u(LinkedHashMap<String, LinkedHashMap<String, Channel>> linkedHashMap, String str) {
        try {
            BufferedReader bufferedReader = new BufferedReader(new StringReader(str));
            LinkedHashMap<String, Channel> channel = new LinkedHashMap<>();
            LinkedHashMap<String, Channel> channelTemp = channel;
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if (line.equals("")) continue;
                if (line.startsWith("#EXTM3U")) continue;
                if (line.startsWith("#EXTINF")) {
                    String name = getStrByRegex(NAME_PATTERN, line);
                    String group = cleanGroupName(getStrByRegex(GROUP_PATTERN, line));
                    String logo = cleanLogo(getStrOrNull(LOGO_PATTERN, line));
                    // 此时再读取一行，就是对应的 url 链接了
                    String next = bufferedReader.readLine();
                    String url = next == null ? "" : next.trim();
                    // 有的源会把失效线路用 # 注释掉、或者 EXTINF 后面跟的是 #EXTVLCOPT
                    // 这类参数行：这种「地址」不是能播的 URL，照收只会在播放页多一条
                    // 必挂的线路，换源时还要在它身上白等一次超时。
                    if (!isPlayableUrl(url)) continue;
                    if (linkedHashMap.containsKey(group)) {
                        channelTemp = linkedHashMap.get(group);
                    } else {
                        channelTemp = new LinkedHashMap<>();
                        linkedHashMap.put(group, channelTemp);
                    }
                    Channel ch = channelTemp.get(name);
                    if (ch == null) {
                        ch = new Channel();
                        channelTemp.put(name, ch);
                    }
                    // 同名台的第一条**带台标的**说了算：同一个台的几条线路通常指向
                    // 同一张图，不必覆盖；但首条偶尔是别人手写的、没写 tvg-logo，
                    // 那就等后面那条补上（以前只在新建时赋值，这种台就永远没图）
                    if ((ch.logo == null || ch.logo.isEmpty()) && !logo.isEmpty()) ch.logo = logo;
                    if (!ch.urls.contains(url)) ch.urls.add(url);
                }
            }
            bufferedReader.close();
            dropTimestampGroups(linkedHashMap);
            if (channel.isEmpty()) return;
            linkedHashMap.put("未分组", channel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * tvg-logo 的清洗：只收 http(s) 绝对地址。
     *
     * <p>源的写法不统一 —— 空的（{@code tvg-logo=""}）、只写文件名的、
     * 甚至写成 {@code #} 占位的都有。这类值交给图片加载器只会多发一次必挂的请求，
     * 卡面还得等它失败，不如当没有。
     *
     * <p>{@code &amp;} 是 m3u 里常见的转义（签名参数里带 {@code &}），
     * 不解开的话请求会被截断在第一个参数上。
     */
    private static String cleanLogo(String logo) {
        if (logo == null) return "";
        String s = logo.trim().replace("&amp;", "&");
        if (s.isEmpty()) return "";
        String lower = s.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return "";
        // 离谱长度直接毙掉，免得把整行地址塞进 JSON
        return s.length() > 512 ? "" : s;
    }

    /** 能交给播放器的地址：http(s)、rtsp、rtmp。其它一律不进线路表 */
    private static boolean isPlayableUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String u = url.toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://")
                || u.startsWith("rtsp://") || u.startsWith("rtmp://");
    }

    /**
     * 分组名的清洗。
     *
     * <p>公开聚合源几乎都在 group-title 前面挂一个 emoji 当分类图标
     * （{@code 📺央视频道}、{@code ☘️浙江频道}、{@code •咪咕「移动」}）。
     * 电视上的 emoji 字体参差不齐：有的机型能渲染成彩色小图，有的直接是一个方框，
     * 而且宽度不可控，分组列本来就只有 128mm，前面多一个方框就吃掉两个字的位置。
     * 这里只砍开头的非字母数字字符，中间的点（{@code 港·澳·台}）不动。
     */
    private static String cleanGroupName(String group) {
        if (group == null) return "未分组";
        String g = group.trim();
        if (g.isEmpty()) return "未分组";
        int i = 0;
        while (i < g.length()) {
            int cp = g.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) break;
            i += Character.charCount(cp);
        }
        String out = g.substring(i).trim();
        return out.isEmpty() ? "未分组" : out;
    }

    /** 只有日期的频道名：{@code 2026-11-25} / {@code 2026-11-25 14:06:22} */
    private static final Pattern TIME_ONLY =
            Pattern.compile("^\\d{4}-\\d{1,2}-\\d{1,2}([ T]\\d{1,2}:\\d{1,2}(:\\d{1,2})?)?$");

    /**
     * 丢掉「更新时间」这类伪分组。
     *
     * <p>聚合源（Guovin 的 result.m3u 就是）会在文件开头塞一个分组，
     * 里面只有一条叫 {@code 2026-11-25 14:06:22} 的「频道」，那是这份列表的更新时间，
     * 不是真的台 —— 点进去必然黑屏。判据是「分组里所有频道名都是日期时间」，
     * 这样不需要认分组名（各家写法不同），也不会误伤正常分组。
     *
     * <p>顺带把解析出来的空分组一并清掉，否则左栏会出现一排点了没反应的条目。
     */
    private static void dropTimestampGroups(LinkedHashMap<String, LinkedHashMap<String, Channel>> map) {
        try {
            java.util.Iterator<String> it = map.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                LinkedHashMap<String, Channel> group = map.get(key);
                if (group == null || group.isEmpty()) {
                    it.remove();
                    continue;
                }
                boolean allTime = true;
                for (String name : group.keySet()) {
                    if (name == null || !TIME_ONLY.matcher(name.trim()).matches()) {
                        allTime = false;
                        break;
                    }
                }
                if (allTime) it.remove();
            }
        } catch (Throwable th) {
            // 清洗失败不影响已经解析好的数据，照常用
            th.printStackTrace();
        }
    }

    private static String getStrByRegex(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) return matcher.group(1);
        return pattern.pattern().equals(GROUP_PATTERN.pattern()) ? "未分组" : "未命名";
    }

    /** 取不到就返回 null —— 用来区分「源里没写这个属性」和「写了个空值」，两者都不用兜底文案 */
    private static String getStrOrNull(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void parseTxt(LinkedHashMap<String, LinkedHashMap<String, Channel>> linkedHashMap, String str) {
        try {
            BufferedReader bufferedReader = new BufferedReader(new StringReader(str));
            String readLine = bufferedReader.readLine();
            LinkedHashMap<String, Channel> linkedHashMap2 = new LinkedHashMap<>();
            LinkedHashMap<String, Channel> linkedHashMap3 = linkedHashMap2;
            while (readLine != null) {
                if (readLine.trim().isEmpty()) {
                    readLine = bufferedReader.readLine();
                } else {
                    String[] split = readLine.split(",", 2);
                    if (split.length < 2) {
                        readLine = bufferedReader.readLine();
                    } else {
                        if (readLine.contains("#genre#")) {
                            String trim = split[0].trim();
                            if (!linkedHashMap.containsKey(trim)) {
                                linkedHashMap3 = new LinkedHashMap<>();
                                linkedHashMap.put(trim, linkedHashMap3);
                            } else {
                                linkedHashMap3 = linkedHashMap.get(trim);
                            }
                        } else {
                            String trim2 = split[0].trim();
                            for (String str2 : split[1].trim().split("#")) {
                                String trim3 = str2.trim();
                                if (!trim3.isEmpty() && (trim3.startsWith("http") || trim3.startsWith("rtsp") || trim3.startsWith("rtmp"))) {
                                    Channel ch = linkedHashMap3.get(trim2);
                                    if (ch == null) {
                                        ch = new Channel();
                                        linkedHashMap3.put(trim2, ch);
                                    }
                                    if (!ch.urls.contains(trim3)) {
                                        ch.urls.add(trim3);
                                    }
                                }
                            }
                        }
                        readLine = bufferedReader.readLine();
                    }
                }
            }
            bufferedReader.close();
            if (linkedHashMap2.isEmpty()) {
                return;
            }
            linkedHashMap.put("未分组", linkedHashMap2);
        } catch (Throwable unused) {
        }
    }

    public static JsonArray live2JsonArray(LinkedHashMap<String, LinkedHashMap<String, Channel>> linkedHashMap) {
        JsonArray jsonarr = new JsonArray();
        for (String str : linkedHashMap.keySet()) {
            JsonArray jsonarr2 = new JsonArray();
            LinkedHashMap<String, Channel> linkedHashMap2 = linkedHashMap.get(str);
            if (!linkedHashMap2.isEmpty()) {
                for (String str2 : linkedHashMap2.keySet()) {
                    Channel ch = linkedHashMap2.get(str2);
                    if (ch == null || ch.urls.isEmpty()) continue;
                    JsonArray jsonarr3 = new JsonArray();
                    for (int i = 0; i < ch.urls.size(); i++) {
                        jsonarr3.add(ch.urls.get(i));
                    }
                    JsonObject jsonobj = new JsonObject();
                    try {
                        jsonobj.addProperty("name", str2);
                        jsonobj.add("urls", jsonarr3);
                        // 源自带的台标：下游（LiveChannelItem.logoUrl）优先用它，
                        // 猜不到库的县级台就靠这一条才有图
                        if (ch.logo != null && !ch.logo.isEmpty()) {
                            jsonobj.addProperty("logo", ch.logo);
                        }
                    } catch (Throwable e) {
                    }
                    jsonarr2.add(jsonobj);
                }
                JsonObject jsonobj2 = new JsonObject();
                try {
                    jsonobj2.addProperty("group", str);
                    jsonobj2.add("channels", jsonarr2);
                } catch (Throwable e) {
                }
                jsonarr.add(jsonobj2);
            }
        }
        return jsonarr;
    }
}

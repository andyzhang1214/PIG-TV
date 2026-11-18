package com.pigtv.util;

import android.text.TextUtils;

import com.orhanobut.hawk.Hawk;
import com.pigtv.bean.LiveChannelItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 收藏频道的读写。
 *
 * <p>为什么存「台名 + 地址」而不是「分组下标 + 频道下标」：频道表是按当前直播源现拉的，
 * 同一个台在不同源里分组顺序、组内位置全都不同，换一次源下标就全错位 ——
 * 收藏会指向隔壁那个台，而且不报错，用户只会觉得「收藏乱了」。
 * 名字和地址是台上唯一两样跨源还稳定的东西。
 *
 * <p>认台的规则是「地址对上」<b>或</b>「名字对上」，任一条成立就算同一个台：
 * <ul>
 *   <li>只看地址不行：源站换 CDN 时地址整条换掉，收藏全丢；</li>
 *   <li>只看名字不行：同一个台在不同源里叫「CCTV-4K」和「CCTV-4 超高清」，
 *       而且名字里常带 {@code [1080P]} 这种画质标记，写法一变就对不上；</li>
 *   <li>两条一起用，改名和换地址各救一半，剩下的交给用户重新收藏。</li>
 * </ul>
 *
 * <p>手写 JSON 而不是 Gson 反射：release 开了 minify，{@link Fav} 的字段名会被混淆，
 * Gson 按字段名映射会全部读成空 —— 表现是「收藏过的台重启后没了」，
 * 不崩也不报错，最难查（{@link LiveSourceStore} 踩过同一个坑）。
 */
public final class FavoriteStore {

    /** JSON 字段名写死在代码里，混淆不影响 */
    private static final String K_NAME = "n";
    private static final String K_URL = "u";
    private static final String K_GROUP = "g";
    private static final String K_TIME = "t";

    /**
     * 收藏上限。
     *
     * <p>不是怕 Hawk 存不下（几百条 JSON 才几十 KB），是怕收藏夹长成一个「第二份频道表」：
     * 真到几百条的时候用户根本不会滚完，这个功能也就没意义了。
     */
    public static final int MAX = 300;

    /** 台名里的画质标记，认台时先剥掉（和 LiveChannelCardAdapter 的显示规则同源） */
    private static final Pattern TAG = Pattern.compile("\\[(?:\\d{3,4}[pP]|[248][kK])\\]");
    /** 台名末尾的画质词 */
    private static final Pattern TAIL = Pattern.compile("(?:高清|超清|蓝光|标清)$");
    /** 台名里认台时无意义的空白和连接符：「CCTV 5」和「CCTV-5」是同一个台 */
    private static final Pattern NOISE = Pattern.compile("[\\s\\-_·|]+");
    /** 只定位 URL authority 末尾的显式端口，路径和查询串里的数字不参与 */
    private static final Pattern URL_PORT =
            Pattern.compile("(?i)^(https?)://([^/?#]*):(\\d+)(?=[/?#]|$)");

    /** 解析后的缓存，写一次就整份换掉；null = 还没读过或刚被改过 */
    private static List<Fav> cache;
    /** 认台的索引：{@code u:<归一地址>} / {@code n:<归一名字>}，避免每格卡片都去遍历整份收藏 */
    private static Set<String> index;

    private FavoriteStore() {
    }

    /** 一条收藏 */
    public static class Fav {
        /** 收藏时的台名（原样，含画质标记）—— 界面上显示用 */
        public final String name;
        /** 收藏时的首路地址（已归一） */
        public final String url;
        /** 收藏时所在的分组名，当前源里对不上时用来提示「它原来在哪一组」 */
        public final String group;
        public final long time;

        Fav(String name, String url, String group, long time) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
            this.group = group == null ? "" : group;
            this.time = time;
        }
    }

    // =====================================================================
    //  读
    // =====================================================================

    /** 全部收藏，按收藏先后排列（先收藏的在前）。只读，不要往里加东西 */
    public static List<Fav> list() {
        if (cache == null) reload();
        return cache;
    }

    public static int count() {
        return list().size();
    }

    public static boolean isFull() {
        return count() >= MAX;
    }

    /** 这个台是不是已收藏（名字或地址任一对上） */
    public static boolean isFav(String name, String firstUrl) {
        if (cache == null) reload();
        String u = normUrl(firstUrl);
        if (!u.isEmpty() && index.contains("u:" + u)) return true;
        String n = normName(name);
        return !n.isEmpty() && index.contains("n:" + n);
    }

    /** 播放页那边的重载：频道对象自带到手的那几条地址 */
    public static boolean isFav(LiveChannelItem item) {
        if (item == null) return false;
        List<String> urls = item.getChannelUrls();
        String first = (urls == null || urls.isEmpty()) ? "" : urls.get(0);
        return isFav(item.getChannelName(), first);
    }

    /** 收藏记录和某个台是不是同一个台 */
    public static boolean match(Fav f, String name, String firstUrl) {
        if (f == null) return false;
        String a = normUrl(f.url);
        String b = normUrl(firstUrl);
        if (!a.isEmpty() && a.equals(b)) return true;
        if (f.name.isEmpty() || TextUtils.isEmpty(name)) return false;
        String n1 = normName(f.name);
        return !n1.isEmpty() && n1.equals(normName(name));
    }

    // =====================================================================
    //  写
    // =====================================================================

    /**
     * 收藏 / 取消收藏，返回操作后是不是「已收藏」。
     *
     * <p>满了直接返回当前状态（不追加），调用方先用 {@link #isFull()} 判断并给提示 ——
     * 这里不弹提示，util 层不碰 UI。
     */
    public static boolean toggle(String name, String firstUrl, String group) {
        List<Fav> l = new ArrayList<>(list());
        boolean found = false;
        // 历史改名可能留下多条记录同时指向这一台；取消必须一次删净，
        // 否则收藏档去重后只剩一张卡，却要按好几次才能真正取消。
        for (int i = l.size() - 1; i >= 0; i--) {
            if (match(l.get(i), name, firstUrl)) {
                l.remove(i);
                found = true;
            }
        }
        if (found) return save(l) ? false : isFav(name, firstUrl);
        if (l.size() >= MAX) return false;
        l.add(new Fav(name, normUrl(firstUrl), group, System.currentTimeMillis()));
        return save(l) ? true : isFav(name, firstUrl);
    }

    /** 播放页与频道墙共用同一份身份：首路地址，而不是当前正在播的第几路 */
    public static boolean toggle(LiveChannelItem item, String group) {
        if (item == null) return false;
        List<String> urls = item.getChannelUrls();
        String first = (urls == null || urls.isEmpty()) ? "" : urls.get(0);
        return toggle(item.getChannelName(), first, group);
    }

    /** 取消收藏；本来就没收藏时什么都不做 */
    public static void remove(String name, String firstUrl) {
        List<Fav> l = new ArrayList<>(list());
        boolean found = false;
        for (int i = l.size() - 1; i >= 0; i--) {
            if (match(l.get(i), name, firstUrl)) {
                l.remove(i);
                found = true;
            }
        }
        if (found) save(l);
    }

    /** 清空（设置里的「清空收藏」用；目前没有入口，留着不占地方） */
    public static void clear() {
        save(new ArrayList<Fav>());
    }

    // =====================================================================
    //  内部
    // =====================================================================

    private static void reload() {
        List<Fav> out = new ArrayList<>();
        try {
            String json = Hawk.get(HawkConfig.LIVE_FAVORITES, "");
            if (!TextUtils.isEmpty(json)) {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String n = o.optString(K_NAME, "").trim();
                    String u = o.optString(K_URL, "").trim();
                    // 名字和地址都空的条目谁也认不出来，纯占位，读的时候就扔掉
                    if (n.isEmpty() && u.isEmpty()) continue;
                    out.add(new Fav(n, normUrl(u), o.optString(K_GROUP, ""), o.optLong(K_TIME, 0)));
                }
            }
        } catch (Throwable th) {
            // 存坏了就当没收藏过，不能让它把频道页拖崩
            th.printStackTrace();
            out.clear();
        }
        cache = Collections.unmodifiableList(out);
        index = new HashSet<>();
        for (Fav f : cache) {
            String u = normUrl(f.url);
            if (!u.isEmpty()) index.add("u:" + u);
            String n = normName(f.name);
            if (!n.isEmpty()) index.add("n:" + n);
        }
    }

    private static boolean save(List<Fav> list) {
        boolean ok = false;
        try {
            JSONArray arr = new JSONArray();
            for (Fav f : list) {
                JSONObject o = new JSONObject();
                o.put(K_NAME, f.name);
                o.put(K_URL, f.url);
                o.put(K_GROUP, f.group);
                o.put(K_TIME, f.time);
                arr.put(o);
            }
            ok = Hawk.put(HawkConfig.LIVE_FAVORITES, arr.toString());
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            // 不管写没写成，缓存都作废；失败时重新读 Hawk，界面回到真实状态。
            cache = null;
            index = null;
        }
        return ok;
    }

    /**
     * 台名归一，只用于「这两个名字是不是同一个台」。
     *
     * <p>不用于显示（显示要用原名的清洁版，见 {@code LiveChannelCardAdapter.cleanName}）。
     * 剥掉画质标记和所有空白/连接符，再转小写：源站同一台写「CCTV-5 体育 [1080P]」
     * 和「cctv5 体育」要能对上。
     */
    public static String normName(String name) {
        String s = name == null ? "" : name.trim();
        if (s.isEmpty()) return "";
        // 去掉 [1080P] 后可能留下尾随空格；先 trim，否则「高清」不在正则的 $ 上
        s = TAG.matcher(s).replaceAll("").trim();
        s = TAIL.matcher(s).replaceAll("");
        return NOISE.matcher(s).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /**
     * 地址归一，只抹默认端口。
     *
     * <p>同一条流常有两种写法（{@code https://host:443/x.m3u8} 和 {@code https://host/x.m3u8}），
     * 字符串直接比会当成两个台。协议和路径一概不动 —— 换个协议、换个路径往往真的是另一条源。
     */
    public static String normUrl(String u) {
        if (u == null) return "";
        String s = u.trim();
        Matcher m = URL_PORT.matcher(s);
        if (!m.find()) return s;
        final int port;
        try {
            port = Integer.parseInt(m.group(3));
        } catch (Throwable t) {
            return s;
        }
        // 只有协议自己的默认端口才能省：http:443 和 https:80 可能是另一项服务
        boolean drop = ("http".equalsIgnoreCase(m.group(1)) && port == 80)
                || ("https".equalsIgnoreCase(m.group(1)) && port == 443);
        if (!drop) return s;
        // 连同端口前的冒号一起删掉，其余原文（含 query / fragment）逐字保留
        return new StringBuilder(s).delete(m.start(3) - 1, m.end(3)).toString();
    }
}

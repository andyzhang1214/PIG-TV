package com.pigtv.util;

import android.text.TextUtils;

import com.orhanobut.hawk.Hawk;
import com.pigtv.base.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户自建直播源清单的读写入口。
 *
 * <p>为什么要有这份清单：{@link HawkConfig#LIVE_URL} 只存「当前生效的那一条」，
 * 换一条就得把它覆盖掉，用户想换回来只能重新敲一遍地址 —— 电视遥控器上敲一条
 * 几十字符的 URL 基本是不可能完成的任务。所以另存一份清单，页面上列出来点着切。
 *
 * <p>清单只是「给人看的索引」，播放链路一个字都没改：切换时依旧是写
 * {@code LIVE_URL / LIVE_NAME}，ApiConfig 和 LivePlayActivity 照旧读那两个键。
 * 这样即使清单被写坏，最坏结果只是列表空了，直播本身不受影响。
 *
 * <p>内置源不进清单，用 {@link #BUILTIN_URL} 这个空地址当它在列表里的身份。
 * 这一点和 ApiConfig 的判定完全一致：{@code LIVE_URL} 为空就走 assets 里的
 * {@code live_builtin.txt}。
 */
public final class LiveSourceStore {

    /** 内置源在清单里的「地址」：空串。写进 Hawk 等于删掉自定义源，回退内置 */
    public static final String BUILTIN_URL = "";

    /**
     * 手写 JSON 而不是 Gson 反射：release 开了 minify，{@code Source} 的两个字段会被
     * 混淆成 a/b，Gson 按字段名映射就全空了 —— 表现是「加过的源重启后消失」，
     * 而且不崩、不报错，最难查。这里字段名写死在代码里，混淆不影响。
     */
    private static final String K_NAME = "name";
    private static final String K_URL = "url";

    /** 历史最多留这么多条，和 ApiConfig.putLiveHistory 的上限一致 */
    private static final int HISTORY_MAX = 20;

    private LiveSourceStore() {
    }

    /** 一条直播源。{@code url} 为空表示内置源 */
    public static class Source {
        public String name;
        public String url;

        public Source() {
        }

        public Source(String name, String url) {
            this.name = name == null ? "" : name.trim();
            this.url = url == null ? "" : url.trim();
        }

        public boolean isBuiltin() {
            return TextUtils.isEmpty(url);
        }

        /** 列表里显示的名字：没起名就退回地址，内置源固定文案 */
        public String displayName() {
            if (isBuiltin()) return "内置频道表";
            if (!TextUtils.isEmpty(name)) return name;
            return LiveSourceStore.suggestName(url);
        }

        /** 副标题：内置源给说明，自定义源给地址 */
        public String displaySub() {
            return isBuiltin() ? "打包在应用里，离线可用" : url;
        }
    }

    // =====================================================================
    //  列表
    // =====================================================================

    /** 只有用户自己加的源（不含内置） */
    public static List<Source> custom() {
        List<Source> out = new ArrayList<>();
        try {
            String json = Hawk.get(HawkConfig.LIVE_SOURCE_LIST, "");
            if (TextUtils.isEmpty(json)) return out;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString(K_URL, "").trim();
                // 地址为空的行直接丢掉：它既播不了，也没法当条目点
                if (TextUtils.isEmpty(url)) continue;
                out.add(new Source(o.optString(K_NAME, ""), url));
            }
        } catch (Throwable th) {
            // 存坏了就当没有，别让管理页打不开
            th.printStackTrace();
            out.clear();
        }
        return out;
    }

    /**
     * 管理页要显示的全部条目：内置源永远第一，后面跟自定义源。
     *
     * <p>顺带做一次对齐：用户在手机网页上填过地址（只写了 LIVE_URL 没进清单）时，
     * 把它补进清单 —— 否则管理页里「当前生效」那一条根本不在列表上，
     * 用户会以为自己的源丢了。
     */
    public static List<Source> all() {
        List<Source> list = custom();
        String cur = currentUrl();
        if (!TextUtils.isEmpty(cur) && indexOf(list, cur) < 0) {
            String name = Hawk.get(HawkConfig.LIVE_NAME, "");
            list.add(0, new Source(TextUtils.isEmpty(name) ? suggestName(cur) : name, cur));
            save(list);
        }
        List<Source> out = new ArrayList<>();
        out.add(new Source("内置频道表", BUILTIN_URL));
        out.addAll(list);
        return out;
    }

    // =====================================================================
    //  增删改
    // =====================================================================

    /**
     * 加一条源。地址已存在就只更新名字，不重复占位。
     *
     * @return 落盘成功与否（地址非法或读写异常返回 false）
     */
    public static boolean add(String name, String url) {
        if (!isValidUrl(url)) return false;
        List<Source> list = custom();
        int idx = indexOf(list, url);
        if (idx >= 0) {
            list.get(idx).name = name == null ? "" : name.trim();
        } else {
            list.add(0, new Source(TextUtils.isEmpty(name) ? suggestName(url) : name, url));
        }
        return save(list);
    }

    /** 删一条。删掉的正好是当前生效的那条时，顺手回退到内置源 */
    public static boolean remove(String url) {
        List<Source> list = custom();
        int idx = indexOf(list, url);
        if (idx < 0) return false;
        list.remove(idx);
        boolean ok = save(list);
        if (ok && url != null && url.trim().equals(currentUrl())) {
            // 删掉的正是当前在用的那条：回退到内置源，别让 app 停在一个已经不存在的地址上
            apply(new Source("", BUILTIN_URL));
        }
        return ok;
    }

    private static boolean save(List<Source> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Source s : list) {
                if (s == null || TextUtils.isEmpty(s.url)) continue;
                JSONObject o = new JSONObject();
                o.put(K_NAME, s.name == null ? "" : s.name);
                o.put(K_URL, s.url);
                arr.put(o);
            }
            Hawk.put(HawkConfig.LIVE_SOURCE_LIST, arr.toString());
            return true;
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }

    // =====================================================================
    //  当前生效
    // =====================================================================

    /** 当前生效的地址，空串 = 内置源 */
    public static String currentUrl() {
        String url = Hawk.get(HawkConfig.LIVE_URL, "");
        return url == null ? "" : url.trim();
    }

    public static boolean isCurrent(Source s) {
        if (s == null) return false;
        return s.url.trim().equals(currentUrl());
    }

    /**
     * 让某个条目生效：只写 Hawk，不重启也不刷界面 —— 由调用方决定怎么收尾。
     *
     * <p>内置源要 {@code delete} 而不是 {@code put("")}：ApiConfig 判的是
     * {@code TextUtils.isEmpty}，空串其实也能过，但留着一条空记录会让
     * LivePlayActivity 那边的 history 判定变得含糊，不如直接删干净。
     */
    public static void apply(Source s) {
        if (s == null) return;
        try {
            if (s.isBuiltin()) {
                Hawk.delete(HawkConfig.LIVE_URL);
                Hawk.delete(HawkConfig.LIVE_NAME);
            } else {
                Hawk.put(HawkConfig.LIVE_URL, s.url);
                Hawk.put(HawkConfig.LIVE_NAME, s.displayName());
                putHistory(s.url);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 同步一份到历史（LivePlayActivity 的 MENU →「直播地址 → 列表历史」读这个）。
     * 那个入口以前是死的：全工程没有任何地方往这写，列表永远空。加源/切源顺手写一下，
     * 两个入口看到的就是同一批地址了。
     */
    private static void putHistory(String url) {
        if (TextUtils.isEmpty(url)) return;
        try {
            ArrayList<String> history = Hawk.get(HawkConfig.LIVE_HISTORY, new ArrayList<String>());
            if (history == null) history = new ArrayList<>();
            if (history.contains(url)) history.remove(url);
            history.add(0, url);
            while (history.size() > HISTORY_MAX) history.remove(history.size() - 1);
            Hawk.put(HawkConfig.LIVE_HISTORY, history);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    // =====================================================================
    //  离线缓存
    // =====================================================================

    /**
     * 上一次成功拉到的源正文，跟源地址一起存在 files 目录。
     *
     * <p>为什么不塞 Hawk：自定义源动辄几百 KB 到几 MB，全量进 SharedPreferences 会让
     * 每次 {@code Hawk.get} 都在主线程反序列化一个大 String，冷启动明显卡。放文件里按需读。
     *
     * <p>第一行是地址，后面全是正文；地址对不上就当没有 —— 换了源还拿旧内容播，
     * 用户看到的是别人家的台，比空列表更难解释。
     */
    private static final String CACHE_FILE = "live_src_cache.txt";

    public static void saveCache(String url, String content) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(content)) return;
        FileOutputStream fos = null;
        try {
            File f = new File(App.getInstance().getFilesDir(), CACHE_FILE);
            fos = new FileOutputStream(f);
            fos.write((url.trim() + "\n").getBytes("UTF-8"));
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable th) {
            // 缓存写不进去只是丢了个离线兜底，不影响在线播放
            th.printStackTrace();
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** @return 该地址上次缓存下来的正文；没缓存或地址不符返回 null */
    public static String readCache(String url) {
        if (TextUtils.isEmpty(url)) return null;
        FileInputStream fis = null;
        try {
            File f = new File(App.getInstance().getFilesDir(), CACHE_FILE);
            if (!f.exists() || f.length() <= 0) return null;
            byte[] all = new byte[(int) f.length()];
            fis = new FileInputStream(f);
            int off = 0;
            while (off < all.length) {
                int n = fis.read(all, off, all.length - off);
                if (n <= 0) break;
                off += n;
            }
            String text = new String(all, 0, off, "UTF-8");
            int nl = text.indexOf('\n');
            if (nl <= 0) return null;
            String cachedUrl = text.substring(0, nl).trim();
            if (!cachedUrl.equals(url.trim())) return null;
            String body = text.substring(nl + 1);
            return TextUtils.isEmpty(body) ? null : body;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        } finally {
            try {
                if (fis != null) fis.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // =====================================================================
    //  小工具
    // =====================================================================

    private static int indexOf(List<Source> list, String url) {
        if (list == null || TextUtils.isEmpty(url)) return -1;
        String t = url.trim();
        for (int i = 0; i < list.size(); i++) {
            Source s = list.get(i);
            if (s != null && s.url != null && s.url.trim().equals(t)) return i;
        }
        return -1;
    }

    /** 只认 http/https：file:// 和空串都不给加，免得点进去一片黑还找不到原因 */
    public static boolean isValidUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /**
     * 从地址猜个名字当默认值：优先取文件名（{@code my_live.txt} → {@code my_live}），
     * 取不到就用域名。用户懒得起名时列表里也不至于全是「自定义源 1/2/3」。
     */
    public static String suggestName(String url) {
        if (TextUtils.isEmpty(url)) return "内置频道表";
        try {
            String u = url.trim();
            int q = u.indexOf('?');
            if (q > 0) u = u.substring(0, q);
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            int slash = u.lastIndexOf('/');
            String tail = slash >= 0 ? u.substring(slash + 1) : u;
            int dot = tail.lastIndexOf('.');
            if (dot > 0) tail = tail.substring(0, dot);
            if (!TextUtils.isEmpty(tail) && tail.length() > 1) return tail;
            String host = u.replace("https://", "").replace("http://", "");
            int s2 = host.indexOf('/');
            if (s2 > 0) host = host.substring(0, s2);
            return TextUtils.isEmpty(host) ? "自定义源" : host;
        } catch (Throwable th) {
            return "自定义源";
        }
    }
}

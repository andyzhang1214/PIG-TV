package com.github.tvbox.osc.util.m3u8;

import android.text.TextUtils;
import android.util.Log;

import com.orhanobut.hawk.Hawk;
import com.github.tvbox.osc.util.HawkConfig;

import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;

/**
 * m3u8 广告段过滤的「IJK 通道」。
 *
 * <p>ExoPlayer 那条链路可以直接包一层 {@code DataSource}（见
 * {@code player/m3u8/M3u8AdFilterDataSource}），但本项目默认播放器是 IJK，它是
 * native（ffmpeg）自己发 HTTP 请求的，Java 层插不进手。所以这里在
 * 127.0.0.1 起一个只监听本机的小服务：
 *
 * <pre>
 *   IJK --&gt; http://127.0.0.1:&lt;port&gt;/p/&lt;id&gt;.m3u8  （我们改写过的播放列表）
 *       --&gt; 分片仍然是源站绝对地址，IJK 自己去拉（headers 照旧生效）
 * </pre>
 *
 * <p>只代理「播放列表」这一个文本请求（几 KB），分片流量完全不经这里，
 * 所以既不费流量也不增加播放端解码负担。取不到、不是播放列表、或者任何异常，
 * 一律 302 回原地址 —— 宁可看广告，也不能让片子放不出来。
 */
public final class M3u8AdProxy extends NanoHTTPD {

    private static final String TAG = "M3u8Filter";
    /** 一个播放列表最多留这么久（直播会反复请求，靠同一 id 复用） */
    private static final long TTL_MS = 3 * 60 * 60 * 1000L;
    /** 上游播放列表本身很小，2MB 已经很宽松，超过就当不是播放列表 */
    private static final long MAX_BODY = 2 * 1024 * 1024L;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0 Safari/537.36";

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");

    private static final Map<String, Item> REG = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();
    private static volatile M3u8AdProxy sInstance;
    private static volatile OkHttpClient sClient;

    private static final class Item {
        final String url;
        final Map<String, String> headers;
        final long ts;
        volatile boolean logged;

        Item(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
            this.ts = System.currentTimeMillis();
        }
    }

    private M3u8AdProxy() {
        super("127.0.0.1", 0);
    }

    // ==================================================================
    //  对外入口
    // ==================================================================

    /**
     * 把 m3u8 地址换成「本地过滤版」地址。
     *
     * @return 改写后的地址；不需要/不能用过滤时**原样返回**入参
     */
    public static String wrap(String url, Map<String, String> headers) {
        if (TextUtils.isEmpty(url)) return url;
        if (url.startsWith("http://127.0.0.1") || url.startsWith("file:")) return url;
        if (!Hawk.get(HawkConfig.M3U8_AD_FILTER, true)) return url;
        if (!looksLikePlaylist(url)) return url;
        try {
            M3u8AdProxy server = ensure();
            if (server == null) return url;
            String local = register(server, url, headers);
            if (local == null) return url;
            Log.i(TAG, "IJK 播放列表走本地代理 " + clip(url));
            return local;
        } catch (Throwable t) {
            return url;
        }
    }

    private static String register(M3u8AdProxy server, String url, Map<String, String> headers) {
        Map<String, String> copy = new HashMap<>();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e == null || e.getKey() == null || e.getValue() == null) continue;
                copy.put(e.getKey(), e.getValue());
            }
        }
        String id = Long.toHexString(SEQ.incrementAndGet()) + "-"
                + Integer.toHexString((url + copy).hashCode());
        REG.put(id, new Item(url, copy));
        purgeIfNeeded();
        return "http://127.0.0.1:" + server.getListeningPort() + "/p/" + id + ".m3u8";
    }

    private static synchronized M3u8AdProxy ensure() {
        M3u8AdProxy s = sInstance;
        if (s != null && s.isAlive()) return s;
        try {
            M3u8AdProxy created = new M3u8AdProxy();
            created.start(SOCKET_READ_TIMEOUT, true);
            sInstance = created;
            return created;
        } catch (Throwable t) {
            Log.w(TAG, "本地代理起不来，退回直连：" + t);
            sInstance = null;
            return null;
        }
    }

    private static OkHttpClient client() {
        OkHttpClient c = sClient;
        if (c == null) {
            synchronized (M3u8AdProxy.class) {
                c = sClient;
                if (c == null) {
                    c = new OkHttpClient.Builder()
                            .connectTimeout(6, TimeUnit.SECONDS)
                            .readTimeout(6, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .build();
                    sClient = c;
                }
            }
        }
        return c;
    }

    private static void purgeIfNeeded() {
        if (REG.size() < 64) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Item>> it = REG.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().ts > TTL_MS) it.remove();
        }
    }

    // ==================================================================
    //  服务端
    // ==================================================================

    @Override
    public NanoHTTPD.Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null || !uri.startsWith("/p/")) return notFound();
        String id = uri.substring(3);
        int dot = id.indexOf(".m3u8");
        if (dot > 0) id = id.substring(0, dot);
        Item item = REG.get(id);
        if (item == null) return notFound();

        okhttp3.Response upstream = null;
        try {
            Request.Builder rb = new Request.Builder().url(item.url).header("User-Agent", UA);
            for (Map.Entry<String, String> e : item.headers.entrySet()) {
                String k = e.getKey();
                // UA 已经单独设过；Host / Accept-Encoding 交给 OkHttp 自己算
                if (k == null) continue;
                if ("User-Agent".equalsIgnoreCase(k)) continue;
                if ("Host".equalsIgnoreCase(k)) continue;
                if ("Accept-Encoding".equalsIgnoreCase(k)) continue;
                try {
                    rb.header(k, e.getValue());
                } catch (Throwable ignored) {
                }
            }
            upstream = client().newCall(rb.build()).execute();
            ResponseBody body = upstream.body();
            if (body == null) return redirect(item.url);
            if (body.contentLength() > MAX_BODY) return redirect(item.url);
            String text = body.string();
            if (TextUtils.isEmpty(text) || !text.contains("#EXTM3U")) {
                // 不是播放列表（有的源会 302 到 mp4），让播放器直连
                return redirect(item.url);
            }
            String base = upstream.request().url().toString();
            String out = M3u8AdFilter.strip(text);
            out = rewrite(out, base, item.headers);
            if (!item.logged) {
                item.logged = true;
                int before = countSegments(text);
                int after = countSegments(out);
                Log.i(TAG, "IJK 走本地代理 " + before + "→" + after + " 段 " + clip(item.url));
            } else if (countSegments(out) != countSegments(text)) {
                Log.i(TAG, "IJK 走本地代理 " + countSegments(text) + "→" + countSegments(out)
                        + " 段（刷新）" + clip(item.url));
            }
            Response r = newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                    "application/vnd.apple.mpegurl", out);
            r.addHeader("Cache-Control", "no-cache");
            return r;
        } catch (Throwable t) {
            return redirect(item.url);
        } finally {
            if (upstream != null) {
                try {
                    upstream.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static NanoHTTPD.Response notFound() {
        return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "");
    }

    private static NanoHTTPD.Response redirect(String url) {
        NanoHTTPD.Response r = newFixedLengthResponse(NanoHTTPD.Response.Status.REDIRECT,
                "text/plain", "");
        r.addHeader("Location", url);
        return r;
    }

    // ==================================================================
    //  播放列表改写：相对地址补成绝对地址，master 里的子列表继续走本代理
    // ==================================================================

    private String rewrite(String text, String base, Map<String, String> headers) {
        String[] lines = text.split("\r?\n", -1);
        StringBuilder sb = new StringBuilder(text.length() + 256);
        boolean nextIsVariant = false;
        for (String line : lines) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty()) {
                sb.append('\n');
                continue;
            }
            if (t.startsWith("#")) {
                if (t.startsWith("#EXT-X-STREAM-INF")) nextIsVariant = true;
                sb.append(rewriteUriAttr(line, base)).append('\n');
                continue;
            }
            String abs = resolve(base, t);
            if (nextIsVariant) {
                nextIsVariant = false;
                // master 列表不做广告判定（里面是子列表地址），但要继续代理，
                // 否则子列表里的广告段就漏过去了
                if (looksLikePlaylist(abs)) {
                    String sub = register(this, abs, headers);
                    sb.append(sub == null ? abs : sub).append('\n');
                    continue;
                }
            }
            sb.append(abs).append('\n');
        }
        return sb.toString();
    }

    private static String rewriteUriAttr(String line, String base) {
        if (line.indexOf("URI=\"") < 0) return line;
        try {
            Matcher m = URI_ATTR.matcher(line);
            StringBuffer out = new StringBuffer(line.length() + 64);
            while (m.find()) {
                String fixed = resolve(base, m.group(1));
                m.appendReplacement(out, Matcher.quoteReplacement("URI=\"" + fixed + "\""));
            }
            m.appendTail(out);
            return out.toString();
        } catch (Throwable t) {
            return line;
        }
    }

    private static String resolve(String base, String rel) {
        if (rel == null) return null;
        String r = rel.trim();
        if (r.isEmpty()) return r;
        String low = r.toLowerCase(Locale.ROOT);
        if (low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("data:") || low.startsWith("file:")) {
            return r;
        }
        try {
            return URI.create(base).resolve(r).toString();
        } catch (Throwable t) {
            return r;
        }
    }

    private static boolean looksLikePlaylist(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String s = url.toLowerCase(Locale.ROOT);
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        return s.endsWith(".m3u8") || s.endsWith(".m3u");
    }

    private static int countSegments(String text) {
        if (text == null) return 0;
        int n = 0;
        int i = 0;
        while ((i = text.indexOf("#EXTINF", i)) >= 0) {
            n++;
            i += 7;
        }
        return n;
    }

    private static String clip(String url) {
        if (url == null) return "";
        return url.length() <= 60 ? url : url.substring(0, 60);
    }
}

package com.github.tvbox.osc.phone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 片源接口体检：一次请求同时回答两个问题 —— 线路快不快、里面到底有没有内容。
 *
 * <p>比单纯 HTTP 200 严得多：采集站失效时常返回导航页或 404 页（HTTP 依然 200），
 * 只看状态码会把死源判成活的；反过来，配置 JSON 完好但 spider 组件下不来的接口，
 * 打开后首页照样空白。所以这里分两步：先确认拿到的是一份配置，再清点其中的站点构成
 * （哪些不需要组件、哪些要靠 jar、组件本身能不能下载且是完整 zip）。
 *
 * <p><b>UA 必须两种都试</b>：盒子迷.top 这类站按 User-Agent 分流，客户端特征给配置 JSON、
 * 浏览器特征给落地网页。只发浏览器 UA 会把能用的源判成「非接口」，只发 okhttp 又会
 * 打不开 qist 那类只认浏览器的站。所以先发客户端 UA（跟真正加载配置时一致），
 * 拿到的不是配置再换浏览器 UA 重试一次。
 */
public final class SourceSpeedTest {

    /* ---------------- 结论级别，前端据此上色 ---------------- */

    /** 连不上 / 超时 */
    public static final int LV_DEAD = 0;
    /** 通了，但返回的不是接口配置（导航页、拦截页、404 页） */
    public static final int LV_NOT_API = 1;
    /** 是一份配置，但按内容清点下来没有任何可用站点 */
    public static final int LV_EMPTY = 2;
    /** 有内容，能用 */
    public static final int LV_OK = 3;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int JAR_READ_TIMEOUT_MS = 6000;
    /** 配置最多读这么多，防一个异常大的文件把内存拖爆 */
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private static final String UA_CLIENT = "okhttp/3.15";
    private static final String UA_BROWSER =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private static volatile OkHttpClient sClient;
    private static volatile OkHttpClient sJarClient;

    private SourceSpeedTest() {
    }

    private static OkHttpClient client() {
        if (sClient == null) {
            synchronized (SourceSpeedTest.class) {
                if (sClient == null) {
                    sClient = new OkHttpClient.Builder()
                            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .callTimeout(READ_TIMEOUT_MS + 3000L, TimeUnit.MILLISECONDS)
                            .retryOnConnectionFailure(false)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build();
                }
            }
        }
        return sClient;
    }

    /** 组件探测单独一个 client：超时更短，不跟配置请求抢时间 */
    private static OkHttpClient jarClient() {
        if (sJarClient == null) {
            synchronized (SourceSpeedTest.class) {
                if (sJarClient == null) {
                    sJarClient = new OkHttpClient.Builder()
                            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(JAR_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .callTimeout(JAR_READ_TIMEOUT_MS + 2000L, TimeUnit.MILLISECONDS)
                            .retryOnConnectionFailure(false)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build();
                }
            }
        }
        return sJarClient;
    }

    /**
     * 完整体检。返回字段：
     * <pre>
     *   level     0 连不上 / 1 非接口 / 2 配置正常但没内容 / 3 有内容
     *   reach     HTTP 是否 2xx
     *   ms        耗时（毫秒）
     *   ok        level==3 时为 1，其余 0
     *   msg       一句话结论
     *   sites     站点总数
     *   httpSites 不依赖组件就能解析的站
     *   jarSites  依赖 spider 组件的站
     *   jsSites   脚本源（无法在端上检测）
     *   lives     直播源组数
     *   jarOk     组件是否可下载且是完整 zip
     *   jarSize   组件大小（字节）
     *   ua        1 = 需要浏览器 UA 才拿得到配置
     * </pre>
     */
    public static JSONObject check(String url) {
        JSONObject out = new JSONObject();
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            fill(out, LV_DEAD, 0, 0, "地址为空");
            return out;
        }

        Fetch f = fetch(u, UA_CLIENT);
        JSONObject cfg = parseConfig(f.body);
        boolean needBrowserUa = false;

        // 客户端 UA 没拿到配置 → 换浏览器 UA 再试一次（qist 这类站只认浏览器）
        if (f.err.length() > 0 || cfg == null) {
            Fetch b = fetch(u, UA_BROWSER);
            JSONObject bc = parseConfig(b.body);
            if (bc != null) {
                f = b;
                cfg = bc;
                needBrowserUa = true;
            } else if (f.err.length() > 0) {
                // 两次都连不上：用后一次的错误信息（通常更贴近真实原因）
                f = b;
            }
        }

        boolean reach = f.code >= 200 && f.code < 300;
        if (f.err.length() > 0) {
            fill(out, LV_DEAD, 0, f.ms, "连不上：" + f.err);
            return out;
        }
        if (!reach) {
            fill(out, LV_NOT_API, 1, f.ms, "HTTP " + f.code);
            return out;
        }
        if (cfg == null) {
            // 弱网下读到 4MB 上限还没读完整份配置时，JSON 必然截断。这种情况
            // 不能判「非接口」把好源判死，只能说「没清点完」——按配置特征认一下。
            String low = f.body == null ? "" : f.body;
            if (low.contains("\"sites\"") || low.contains("\"lives\"") || low.contains("\"spider\"")) {
                fill(out, LV_EMPTY, 1, f.ms, "配置太大没读完，内容未清点");
            } else {
                fill(out, LV_NOT_API, 1, f.ms, "返回的不是接口配置");
            }
            return out;
        }

        // ---- 清点配置内容 ----
        int sites = 0, httpSites = 0, jarSites = 0, jsSites = 0, lives = 0;
        JSONArray arr = cfg.optJSONArray("sites");
        if (arr == null) arr = cfg.optJSONArray("site");
        if (arr != null) {
            sites = arr.length();
            for (int i = 0; i < sites; i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                int type = s.optInt("type", 0);
                String api = s.optString("api", "");
                if (isJsApi(api)) {
                    jsSites++;
                } else if (type == 3 || type == 4 || api.startsWith("csp_")) {
                    jarSites++;
                } else {
                    httpSites++;
                }
            }
        }
        Object livesObj = cfg.opt("lives");
        if (livesObj instanceof JSONArray) lives = ((JSONArray) livesObj).length();
        else if (livesObj instanceof JSONObject) lives = 1;

        // ---- 组件能不能下 ----
        String jarUrl = cfg.optString("spider", "");
        // 形如 http://x/y.jpg;md5;abcdef，兼容老格式的「;pk;」后缀
        int cut = jarUrl.indexOf(";md5;");
        if (cut < 0) cut = jarUrl.indexOf(';');
        if (cut > 0) jarUrl = jarUrl.substring(0, cut);
        jarUrl = jarUrl.trim();

        boolean jarOk = false;
        long jarSize = 0;
        if (!jarUrl.isEmpty()) {
            long[] r = probeJar(jarUrl);
            jarOk = r[0] == 1;
            jarSize = r[1];
        }

        // ---- 下结论 ----
        int usable = httpSites + (jarOk ? jarSites : 0);
        int level;
        String msg;
        if (sites == 0 && lives == 0) {
            level = LV_EMPTY;
            msg = "配置里没有任何站点";
        } else if (usable > 0) {
            level = LV_OK;
            msg = needBrowserUa ? "正常（需浏览器 UA）" : "正常";
        } else if (jarSites > 0 && !jarOk) {
            level = LV_EMPTY;
            msg = jarUrl.isEmpty() ? "站点都依赖组件，但配置里没给组件地址" : "组件下载失败，站点都用不了";
        } else if (jsSites > 0) {
            level = LV_EMPTY;
            msg = "只有脚本源，端上无法检测";
        } else {
            level = LV_EMPTY;
            msg = "没有可用站点";
        }

        fill(out, level, 1, f.ms, msg);
        try {
            out.put("sites", sites);
            out.put("httpSites", httpSites);
            out.put("jarSites", jarSites);
            out.put("jsSites", jsSites);
            out.put("lives", lives);
            out.put("jarOk", jarOk ? 1 : 0);
            out.put("jarSize", jarSize);
            out.put("ua", needBrowserUa ? 1 : 0);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 兼容旧调用：只要 4 个基础字段 */
    public static JSONObject probe(String url) {
        JSONObject r = check(url);
        JSONObject out = new JSONObject();
        try {
            out.put("reach", r.optInt("reach", 0));
            out.put("ms", r.optLong("ms", 0));
            out.put("ok", r.optInt("ok", 0));
            out.put("msg", r.optString("msg", ""));
        } catch (Exception ignored) {
        }
        return out;
    }

    /* ---------------- 内部实现 ---------------- */

    private static final class Fetch {
        int code;
        String body = "";
        long ms;
        String err = "";
    }

    private static Fetch fetch(String url, String ua) {
        Fetch f = new Fetch();
        long t0 = System.currentTimeMillis();
        Response resp = null;
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "*/*")
                    .get()
                    .build();
            resp = client().newCall(req).execute();
            f.code = resp.code();
            f.body = readBody(resp, MAX_BODY);
            f.ms = System.currentTimeMillis() - t0;
        } catch (Throwable e) {
            f.ms = System.currentTimeMillis() - t0;
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (m.length() > 60) m = m.substring(0, 60);
            f.err = m;
        } finally {
            if (resp != null) resp.close();
        }
        return f;
    }

    private static String readBody(Response resp, int limit) {
        ResponseBody body = resp.body();
        if (body == null) return "";
        InputStream in = null;
        try {
            in = body.byteStream();
            byte[] buf = new byte[8192];
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            int n;
            while (bos.size() < limit) {
                n = in.read(buf, 0, Math.min(buf.length, limit - bos.size()));
                if (n <= 0) break;
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 把响应正文当成配置解析。除了标准 JSON，还要兼容三种常见形态：
     * 顶层是数组、落地网页里夹带的 JSON、以及首尾带 BOM/空白的情况。
     */
    private static JSONObject parseConfig(String body) {
        if (body == null) return null;
        String s = body.trim();
        if (s.isEmpty()) return null;
        if (s.charAt(0) == '\uFEFF') s = s.substring(1).trim();
        if (s.isEmpty()) return null;

        try {
            return new JSONObject(s);
        } catch (Throwable ignored) {
        }
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && (o.has("sites") || o.has("lives") || o.has("spider"))) return o;
            }
        } catch (Throwable ignored) {
        }
        int i = s.indexOf('{'), j = s.lastIndexOf('}');
        if (i >= 0 && j > i) {
            try {
                return new JSONObject(s.substring(i, j + 1));
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 脚本源：端上是 quickjs 求值，走这条路会 SIGSEGV，统计时要单独算 */
    private static boolean isJsApi(String api) {
        if (api == null || api.isEmpty()) return false;
        String a = api.toLowerCase();
        return a.endsWith(".js") || a.contains("/drpy") || a.startsWith("js:");
    }

    /**
     * 组件探测：只取前 4 个字节看 zip 魔数（PK\x03\x04），顺手读总大小。
     * 不用 HEAD —— 不少源站对 HEAD 直接 403，或者返回 200 却不给 Content-Length。
     * 很多源把 jar 伪装成 .jpg，所以也不能靠 Content-Type 判断。
     *
     * @return {1/0 是否可用, 字节数}
     */
    private static long[] probeJar(String url) {
        long[] r = new long[]{0, 0};
        Response resp = null;
        InputStream in = null;
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA_CLIENT)
                    .header("Range", "bytes=0-3")
                    .get()
                    .build();
            resp = jarClient().newCall(req).execute();
            if (resp.code() < 200 || resp.code() >= 400) return r;
            ResponseBody body = resp.body();
            if (body == null) return r;

            in = body.byteStream();
            byte[] head = new byte[4];
            int n = 0;
            while (n < 4) {
                int k = in.read(head, n, 4 - n);
                if (k <= 0) break;
                n += k;
            }
            boolean zip = n == 4 && head[0] == 'P' && head[1] == 'K'
                    && (head[2] == 3 || head[2] == 5 || head[2] == 7);
            r[0] = zip ? 1 : 0;

            long total = 0;
            String cr = resp.header("Content-Range");
            if (cr != null) {
                int slash = cr.indexOf('/');
                if (slash > 0) {
                    String tail = cr.substring(slash + 1).trim();
                    if (!"*".equals(tail)) {
                        try {
                            total = Long.parseLong(tail);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if (total <= 0) total = body.contentLength();
            r[1] = total > 0 ? total : 0;
        } catch (Throwable ignored) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (resp != null) resp.close();
        }
        return r;
    }

    /** 只填公共字段；ok 恒等于 level==OK，前端老代码读 alive/ok 也不会错 */
    private static void fill(JSONObject o, int level, int reach, long ms, String msg) {
        try {
            o.put("level", level);
            o.put("reach", reach);
            o.put("ms", ms);
            o.put("ok", level >= LV_OK ? 1 : 0);
            o.put("msg", msg);
        } catch (Exception ignored) {
        }
    }
}

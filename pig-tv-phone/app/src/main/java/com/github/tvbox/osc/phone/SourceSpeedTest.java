package com.github.tvbox.osc.phone;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 片源接口测速。
 *
 * <p>思路来自 LibreTV 的源探活：与其让用户瞎猜「这个接口还能不能用」，不如直接打一次
 * 接口本身，把耗时和「返回的到底是不是接口配置」摆出来。判断标准比单纯 HTTP 200
 * 严一点 —— 采集站失效时经常返回导航页或 404 页面（HTTP 依然是 200），只看状态码
 * 会误判成"活着"。
 *
 * <p>只读前 {@value #SNIFF_BYTES} 字节：够判断内容类型，又不会为了测速把几十兆的
 * 配置整个拉下来（弱网下那反而会拖垮后续切换）。
 */
public final class SourceSpeedTest {

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 7000;
    private static final int SNIFF_BYTES = 64 * 1024;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private static volatile OkHttpClient sClient;

    private SourceSpeedTest() {
    }

    private static OkHttpClient client() {
        if (sClient == null) {
            synchronized (SourceSpeedTest.class) {
                if (sClient == null) {
                    sClient = new OkHttpClient.Builder()
                            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .callTimeout(READ_TIMEOUT_MS + 2000L, TimeUnit.MILLISECONDS)
                            .retryOnConnectionFailure(false)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build();
                }
            }
        }
        return sClient;
    }

    /**
     * 打一次接口，返回 {reach,ms,ok,msg}：
     * <ul>
     *   <li>reach：HTTP 是否拿到 2xx</li>
     *   <li>ms：从发起到收到响应头 + 首段内容的耗时</li>
     *   <li>ok：内容是否像一份 TVBox 配置（含 sites / class / lives / api 字段）</li>
     * </ul>
     */
    public static JSONObject probe(String url) {
        JSONObject out = new JSONObject();
        if (url == null || url.trim().isEmpty()) {
            put(out, 0, 0, 0, "地址为空");
            return out;
        }
        long t0 = System.currentTimeMillis();
        Response resp = null;
        try {
            Request req = new Request.Builder()
                    .url(url.trim())
                    .header("User-Agent", UA)
                    .header("Accept", "*/*")
                    .get()
                    .build();
            resp = client().newCall(req).execute();
            int code = resp.code();
            String body = peek(resp);
            long ms = System.currentTimeMillis() - t0;
            boolean reach = code >= 200 && code < 300;
            if (!reach) {
                put(out, 1, ms, 0, "HTTP " + code);
                return out;
            }
            String lower = body == null ? "" : body;
            boolean looksLikeConfig = lower.contains("\"sites\"") || lower.contains("\"class\"")
                    || lower.contains("\"lives\"") || lower.contains("\"api\"")
                    || lower.contains("\"urls\"");
            if (!looksLikeConfig) {
                put(out, 1, ms, 0, "返回的不是接口配置");
                return out;
            }
            put(out, 1, ms, 1, "正常");
            return out;
        } catch (IOException e) {
            long ms = System.currentTimeMillis() - t0;
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.length() > 40) msg = msg.substring(0, 40);
            put(out, 0, ms, 0, "连不上：" + msg);
            return out;
        } finally {
            if (resp != null) resp.close();
        }
    }

    private static void put(JSONObject o, int reach, long ms, int ok, String msg) {
        try {
            o.put("reach", reach);
            o.put("ms", ms);
            o.put("ok", ok);
            o.put("msg", msg);
        } catch (Exception ignored) {
        }
    }

    /** 只读一小段：既够判断内容，又不至于把大配置整份拖下来 */
    private static String peek(Response resp) {
        ResponseBody body = resp.body();
        if (body == null) return "";
        InputStream in = null;
        try {
            in = body.byteStream();
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
            int n;
            while (bos.size() < SNIFF_BYTES) {
                n = in.read(buf, 0, Math.min(buf.length, SNIFF_BYTES - bos.size()));
                if (n <= 0) break;
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}

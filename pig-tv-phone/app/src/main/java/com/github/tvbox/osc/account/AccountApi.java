package com.github.tvbox.osc.account;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.OkGoHelper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.orhanobut.hawk.Hawk;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 会员服务端的唯一样式出口。
 *
 * <p>为什么不用 OkGo 的异步回调：桥方法本身就跑在线程池里，同步 call 更好读，
 * 也不会出现「回调回到 JS 线程」的时序问题。这里只是把 OkHttp 包一层，
 * 复用 {@link OkGoHelper#getDefaultClient()}（它已经配了 CLEARTEXT + 宽松 TLS，
 * 裸 IP 走 http 也能连）。</p>
 *
 * <p>服务端统一响应体是 {@code {code, message, data}}，code=0 成功；
 * HTTP 403 是封禁（body 里带 banUntil / reason / banType）。
 * 这两种「失败」不能混成一个字符串，否则被封的人只会看到「登录失败」。</p>
 */
public final class AccountApi {

    /**
     * 默认服务端地址。单机部署、没有域名，这里是裸 IP + 端口。
     * 改了要同步 {@code PIG-TV-服务端与管理后台-部署说明.md}。
     */
    public static final String DEFAULT_BASE = "http://127.0.0.1:18088";

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    /** 服务没起时连 30 秒都嫌久，用户已经以为卡死了 */
    private static final long TIMEOUT_S = 12L;

    private AccountApi() {
    }

    // ------------------------------------------------------------------ 地址

    public static String base() {
        String b = Hawk.get(HawkConfig.ACCOUNT_BASE, "");
        if (b == null) {
            b = "";
        }
        b = b.trim();
        if (b.isEmpty()) {
            b = DEFAULT_BASE;
        }
        return trimSlash(b);
    }

    /** 用户手填的地址：补 scheme、去尾部斜杠，别让 http://1.2.3.4:8088/ 变成双斜杠 */
    public static void setBase(String v) {
        Hawk.put(HawkConfig.ACCOUNT_BASE, trimSlash(v == null ? "" : v.trim()));
    }

    public static String normalizedBase(String v) {
        String b = v == null ? "" : v.trim();
        if (b.isEmpty()) {
            return "";
        }
        if (!b.startsWith("http://") && !b.startsWith("https://")) {
            b = "http://" + b;
        }
        return trimSlash(b);
    }

    private static String trimSlash(String b) {
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    // ------------------------------------------------------------------ 结果

    /** 一次调用的结果。ok 只看业务码，http 单独留着判断封禁 */
    public static final class Res {
        /** 业务成功（code == 0） */
        public boolean ok;
        /** 0 成功；-1 网络/解析失败；其余是服务端业务码 */
        public int code;
        public String message = "";
        /** 服务端 data，可能为 null */
        public JsonObject data;
        /** HTTP 状态码，0 表示请求根本没发出去 */
        public int http;

        public String str(String key) {
            if (data == null) {
                return "";
            }
            JsonElement e = data.get(key);
            return e == null || e.isJsonNull() ? "" : e.getAsString();
        }

        public long num(String key, long def) {
            if (data == null) {
                return def;
            }
            JsonElement e = data.get(key);
            try {
                return e == null || e.isJsonNull() ? def : e.getAsLong();
            } catch (Throwable ignored) {
                return def;
            }
        }

        /**
         * 被封禁时的可读原因。
         * 服务端在 403 的 data 里给了 banUntil / reason / banType，
         * 拼成一句人话，用户才知道「等到什么时候」「为什么」。
         */
        public String banText() {
            String until = str("banUntil");
            String reason = str("reason");
            if (reason.isEmpty()) {
                reason = message;
            }
            if (until.isEmpty()) {
                return reason;
            }
            String t = until.replace('T', ' ');
            if (t.length() > 16) {
                t = t.substring(0, 16);
            }
            return reason + "（解封时间 " + t + "）";
        }
    }

    // ------------------------------------------------------------------ HTTP

    public static Res call(String method, String path, String body, boolean auth) {
        Res r = new Res();
        String url = base() + path;
        try {
            Request.Builder rb = new Request.Builder().url(url).header("Accept", "application/json");
            if ("GET".equalsIgnoreCase(method)) {
                rb.get();
            } else {
                rb.method(method.toUpperCase(), RequestBody.create(JSON_TYPE, body == null ? "{}" : body));
            }
            if (auth) {
                String t = AccountStore.token();
                if (!t.isEmpty()) {
                    rb.header("Authorization", "Bearer " + t);
                }
            }

            OkHttpClient c = client();
            Response resp = c.newCall(rb.build()).execute();
            r.http = resp.code();
            String text;
            ResponseBody rb2 = resp.body();
            text = rb2 == null ? "" : rb2.string();
            resp.close();

            JsonElement parsed = null;
            try {
                parsed = JsonParser.parseString(text);
            } catch (Throwable ignored) {
            }
            if (parsed == null || !parsed.isJsonObject()) {
                // 网关错误页 / 代理返回的 HTML：别把整段 HTML 丢给用户看
                r.code = -1;
                r.message = "服务异常（HTTP " + r.http + "）";
                return r;
            }
            JsonObject o = parsed.getAsJsonObject();
            r.code = o.has("code") ? o.get("code").getAsInt() : -1;
            r.message = o.has("message") && !o.get("message").isJsonNull() ? o.get("message").getAsString() : "";
            JsonElement d = o.get("data");
            if (d != null && d.isJsonObject()) {
                r.data = d.getAsJsonObject();
            }
            r.ok = r.code == 0;
            return r;
        } catch (IOException e) {
            r.code = -1;
            r.http = 0;
            r.message = "连不上服务器：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return r;
        } catch (Throwable e) {
            r.code = -1;
            r.http = 0;
            r.message = "请求失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return r;
        }
    }

    private static OkHttpClient client() {
        OkHttpClient c = OkGoHelper.getDefaultClient();
        if (c != null) {
            return c;
        }
        // 极早期（OkGoHelper.init 之前）被调用时的兜底，别 NPE
        return new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .build();
    }

    // ------------------------------------------------------------------ 账号

    private static String body(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                o.addProperty(kv[i], kv[i + 1]);
            }
        }
        return o.toString();
    }

    public static Res login(String username, String password, String deviceId,
                            String deviceName, String platform, int appVersion) {
        JsonObject o = new JsonObject();
        o.addProperty("username", username);
        o.addProperty("password", password);
        o.addProperty("deviceId", deviceId);
        o.addProperty("deviceName", deviceName);
        o.addProperty("platform", platform);
        o.addProperty("appVersion", appVersion);
        return call("POST", "/api/auth/login", o.toString(), false);
    }

    public static Res register(String username, String password, String deviceId,
                               String deviceName, String platform, int appVersion) {
        JsonObject o = new JsonObject();
        o.addProperty("username", username);
        o.addProperty("password", password);
        o.addProperty("deviceId", deviceId);
        o.addProperty("deviceName", deviceName);
        o.addProperty("platform", platform);
        o.addProperty("appVersion", appVersion);
        return call("POST", "/api/auth/register", o.toString(), false);
    }

    public static Res logout() {
        return call("POST", "/api/auth/logout", "{}", true);
    }

    public static Res profile() {
        return call("GET", "/api/user/profile", null, true);
    }

    public static Res changePassword(String oldPwd, String newPwd) {
        JsonObject o = new JsonObject();
        o.addProperty("oldPassword", oldPwd);
        o.addProperty("newPassword", newPwd);
        return call("POST", "/api/auth/change-password", o.toString(), true);
    }

    // ------------------------------------------------------------- 更新 / 片源

    public static Res checkUpdate(String platform, String abi, int versionCode) {
        return call("GET", "/api/update/check?platform=" + platform + "&abi=" + abi
                + "&versionCode=" + versionCode, null, false);
    }

    public static Res sources() {
        return call("GET", "/api/sources", null, false);
    }

    // ------------------------------------------------------------------ 同步

    public static Res syncPush(String json) {
        return call("POST", "/api/sync/push", json, true);
    }

    public static Res syncPull(String since, String types, int limit) {
        StringBuilder p = new StringBuilder("/api/sync/pull?limit=").append(limit);
        if (since != null && !since.isEmpty()) {
            p.append("&since=").append(since);
        }
        if (types != null && !types.isEmpty()) {
            p.append("&types=").append(types);
        }
        return call("GET", p.toString(), null, true);
    }
}

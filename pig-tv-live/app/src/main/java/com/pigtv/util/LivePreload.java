package com.pigtv.util;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import com.google.gson.JsonArray;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.pigtv.api.ApiConfig;
import com.pigtv.bean.LiveChannelGroup;
import com.pigtv.util.live.TxtSubscribe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 直播频道表预热。
 *
 * <p>原来的问题：点「直播」→ 新建 LivePlayActivity → {@code showLoading()} →
 * 现拉频道表。如果这份频道表是站点给的 {@code http://127.0.0.1:9978/proxy?do=live&ext=xxx}
 * 代理地址，就要先做一次网络请求，慢的时候用户盯着转圈十几秒，还以为卡死了；
 * 而且每次进直播都重拉一遍。
 *
 * <p>这里的做法：焦点一落到顶栏「直播」，就在后台把这份表拉回来写进 {@link ApiConfig}
 * （和 LivePlayActivity 用的是同一份全局数据）。真正进页面时通常已经就绪，
 * 直接 showSuccess，不转圈。失败会记一个时间戳，20 秒内不重复重试，避免焦点来回划时打爆源站。
 */
public final class LivePreload {

    public static final int ST_IDLE = 0;
    public static final int ST_LOADING = 1;
    public static final int ST_READY = 2;
    public static final int ST_FAILED = 3;

    /** 失败后多久之内不再重试 */
    private static final long RETRY_GAP_MS = 20_000L;

    private static int state = ST_IDLE;
    private static long lastFailAt = 0L;
    private static String lastError = "";

    private LivePreload() {
    }

    public static int getState() {
        return state;
    }

    public static String getLastError() {
        return lastError == null ? "" : lastError;
    }

    /** 已经拿到真实频道表（而不是那条 127.0.0.1 代理占位） */
    public static boolean isReady() {
        if (state == ST_READY && hasRealList()) return true;
        if (hasRealList()) {
            state = ST_READY;
            return true;
        }
        return false;
    }

    private static boolean hasRealList() {
        List<LiveChannelGroup> list = ApiConfig.get().getChannelGroupList();
        return list != null && !list.isEmpty() && !isProxyOnly(list);
    }

    /** 配置里只有一条 127.0.0.1 的代理地址，说明真实频道表还没拉下来 */
    public static boolean isProxyOnly(List<LiveChannelGroup> list) {
        if (list == null || list.size() != 1) return false;
        LiveChannelGroup g = list.get(0);
        return g != null && g.getGroupName() != null && g.getGroupName().startsWith("http://127.0.0.1");
    }

    /**
     * 把 {@code .../proxy?do=live&ext=<base64>} 里的 ext 解成真实订阅地址。
     * 解不出来返回 null（调用方按「配置坏了」处理）。
     */
    public static String decodeProxyUrl(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isEmpty()) return null;
        try {
            Uri parsed = Uri.parse(proxyUrl);
            String ext = parsed.getQueryParameter("ext");
            if (ext == null || ext.isEmpty()) return null;
            String url = new String(
                    Base64.decode(ext, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
            url = url == null ? "" : url.trim();
            return url.isEmpty() ? null : url;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 焦点停到「直播」时调用。幂等，可以随便重复调。 */
    public static void warmUp(Context ctx) {
        if (state == ST_LOADING) return;
        if (isReady()) return;
        if (System.currentTimeMillis() - lastFailAt < RETRY_GAP_MS) return;

        List<LiveChannelGroup> list = ApiConfig.get().getChannelGroupList();
        if (list == null || list.isEmpty()) return;      // 连直播配置都没有，没什么可预热的
        if (!isProxyOnly(list)) return;

        final String url = decodeProxyUrl(list.get(0).getGroupName());
        if (url == null) {
            state = ST_FAILED;
            lastError = "直播地址解析失败";
            lastFailAt = System.currentTimeMillis();
            return;
        }

        state = ST_LOADING;
        lastError = "";
        // 记下发起预热时的片源：拉取过程中用户可能已经换了源，
        // 那种情况下这份结果属于旧源，写进 ApiConfig 会污染新源的频道表。
        final String srcKey = ApiConfig.get().getHomeSourceBean() == null
                ? "" : ApiConfig.get().getHomeSourceBean().getKey();
        OkGo.<String>get(url).execute(new AbsCallback<String>() {
            @Override
            public String convertResponse(okhttp3.Response response) throws Throwable {
                return response.body() == null ? "" : response.body().string();
            }

            @Override
            public void onSuccess(Response<String> response) {
                if (!sameSource(srcKey)) {
                    state = ST_IDLE;      // 源换了，丢弃这次结果
                    return;
                }
                try {
                    LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> map = new LinkedHashMap<>();
                    TxtSubscribe.parse(map, response.body() == null ? "" : response.body());
                    JsonArray arr = TxtSubscribe.live2JsonArray(map);
                    ApiConfig.get().loadLives(arr);
                    if (hasRealList()) {
                        state = ST_READY;
                    } else {
                        state = ST_FAILED;
                        lastError = "订阅地址里没有解析出频道";
                        lastFailAt = System.currentTimeMillis();
                    }
                } catch (Throwable t) {
                    state = ST_FAILED;
                    lastError = "频道表解析异常：" + t.getClass().getSimpleName();
                    lastFailAt = System.currentTimeMillis();
                }
            }

            @Override
            public void onError(Response<String> response) {
                super.onError(response);
                state = ST_FAILED;
                Throwable t = response == null ? null : response.getException();
                lastError = t == null ? "直播地址请求失败" : "直播地址请求失败：" + t.getClass().getSimpleName();
                lastFailAt = System.currentTimeMillis();
            }
        });
    }

    /** 预热发起时的片源还是当前片源吗 */
    private static boolean sameSource(String srcKey) {
        if (srcKey == null || srcKey.isEmpty()) return true;
        return ApiConfig.get().getHomeSourceBean() != null
                && srcKey.equals(ApiConfig.get().getHomeSourceBean().getKey());
    }
}

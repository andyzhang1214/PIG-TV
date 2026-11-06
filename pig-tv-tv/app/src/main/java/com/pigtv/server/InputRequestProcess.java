package com.pigtv.server;

import com.pigtv.account.AccountApi;
import com.pigtv.account.AccountStore;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * @author pj567
 * @date :2021/1/5
 * @description: 响应按键和输入
 */

public class InputRequestProcess implements RequestProcess {
    private RemoteServer remoteServer;

    public InputRequestProcess(RemoteServer remoteServer) {
        this.remoteServer = remoteServer;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if (session.getMethod() == NanoHTTPD.Method.POST) {
            switch (fileName) {
                case "/action":
                    return true;
            }
        }
        return false;
    }

    /** 扫码页填的显示名。老页面不带 name 参数 → 返回 null，表示「别动已有的名字」 */
    private String name(Map<String, String> params) {
        String n = params.get("name");
        return params.containsKey("name") && n != null ? n.trim() : null;
    }

    /**
     * 手机扫码页提交的账号密码，直接在电视上换 token。
     *
     * <p>返回纯文本给网页：{@code ok} 或 {@code err:原因}。网页按前缀分支，
     * 所以原因里不能出现换行。另外这里必须显式声明 {@code charset=utf-8} ——
     * NanoHTTPD 的 MIME_PLAINTEXT 不带 charset，中文错误提示到浏览器就是乱码。</p>
     */
    private NanoHTTPD.Response doLogin(Map<String, String> params) {
        String u = params.get("u");
        String p = params.get("p");
        if (u == null || p == null || u.trim().isEmpty() || p.isEmpty()) {
            return plain("err:账号和密码都要填");
        }
        AccountApi.Res r;
        try {
            r = AccountApi.login(u.trim(), p, AccountStore.deviceId(),
                    android.os.Build.MODEL, "tv", 0);
        } catch (Throwable th) {
            return plain("err:连不上会员服务，检查电视的网络");
        }
        if (!r.ok || r.data == null) {
            String m = r.message == null ? "" : r.message.trim();
            /* 底层异常是「连不上服务器：Failed to connect to /10.0.0.5:18088」，
               给在手机上输密码的人看这个没有意义，统一换成人话；
               服务端自己给的中文原因（密码错、被封）原样透传。 */
            if (m.isEmpty() || m.startsWith("连不上服务器")) {
                m = "连不上会员服务，检查电视的网络";
            }
            return plain("err:" + m.replace('\n', ' '));
        }
        String token = r.str("token");
        if (token == null || token.isEmpty()) {
            return plain("err:登录成功但没拿到凭证，稍后再试");
        }
        AccountStore.save(token, r.num("userId", 0), r.str("username"));
        return plain("ok");
    }

    private static NanoHTTPD.Response plain(String text) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                "text/plain; charset=utf-8", text);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        DataReceiver mDataReceiver = remoteServer.getDataReceiver();
        switch (fileName) {
            case "/action":
                /* 登录单独走一条：它不经过 DataReceiver ——
                   DataReceiver 那套是「把地址/文本推给播放器」的回调，
                   登录要的是把 token 落库，两件事不该共用一个出口。 */
                if ("login".equals(params.get("do"))) {
                    return doLogin(params);
                }
                if (params.get("do") != null && mDataReceiver != null) {
                    String action = params.get("do");

                    switch (action) {
                        case "search": {
                            mDataReceiver.onTextReceived(params.get("word").trim());
                            break;
                        }
                        case "api": {
                            mDataReceiver.onApiReceived(params.get("url").trim(), name(params));
                            break;
                        }
                        case "live": {
                            mDataReceiver.onLiveReceived(params.get("url").trim(), name(params));
                            break;
                        }
                        case "epg": {
                            mDataReceiver.onEpgReceived(params.get("url").trim());
                            break;
                        }
                        case "proxys": {
                            mDataReceiver.onProxysReceived(params.get("url").trim());
                            break;
                        }
                        case "push": {
                            // 暂未实现
                            mDataReceiver.onPushReceived(params.get("url").trim());
                            break;
                        }
                        case "mirror": {
                            //推送当前电影、电视剧……
                            mDataReceiver.onMirrorReceived(params.get("id").trim(), params.get("sourceKey").trim());
                            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "mirrored");
                        }
                    }
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok");
            default:
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }
}

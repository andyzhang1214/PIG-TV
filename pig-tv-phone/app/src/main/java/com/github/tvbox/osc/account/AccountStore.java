package com.github.tvbox.osc.account;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.UUID;

/**
 * 本地登录态。
 *
 * <p>只有一个原则：**这里存的只是凭据，不是数据**。
 * 清登录态（{@link #clear()}）永远不碰收藏/历史/设置 ——
 * 封号、被踢下线、主动登出都只应该让用户「变成游客」，
 * 不能顺带把攒了几年的片单清空。</p>
 */
public final class AccountStore {

    private AccountStore() {
    }

    public static boolean logged() {
        return !token().isEmpty();
    }

    public static String token() {
        String t = Hawk.get(HawkConfig.USER_TOKEN, "");
        return t == null ? "" : t;
    }

    public static long uid() {
        try {
            Long v = Hawk.get(HawkConfig.USER_ID, 0L);
            return v == null ? 0L : v;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static String name() {
        String n = Hawk.get(HawkConfig.USER_NAME, "");
        return n == null ? "" : n;
    }

    /**
     * 设备标识：首次生成 UUID 后固化。
     * 服务端按「同一设备只算一个网段」判风控，每次启动换 ID 会被当成新设备，
     * 两天就能把自己送到封号线上。
     */
    public static String deviceId() {
        String d = Hawk.get(HawkConfig.DEVICE_ID, "");
        if (d == null || d.trim().isEmpty()) {
            d = UUID.randomUUID().toString().replace("-", "");
            Hawk.put(HawkConfig.DEVICE_ID, d);
        }
        return d;
    }

    public static void save(String token, long uid, String name) {
        Hawk.put(HawkConfig.USER_TOKEN, token == null ? "" : token);
        Hawk.put(HawkConfig.USER_ID, uid);
        Hawk.put(HawkConfig.USER_NAME, name == null ? "" : name);
    }

    /** 只掉登录态，本地数据原样保留 */
    public static void clear() {
        Hawk.put(HawkConfig.USER_TOKEN, "");
        Hawk.put(HawkConfig.USER_ID, 0L);
        Hawk.put(HawkConfig.USER_NAME, "");
    }
}

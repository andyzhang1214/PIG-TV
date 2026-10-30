package com.pigtv.server.security;

/** 当前请求上下文（线程局部）：用户端与管理端分开存 */
public final class Ctx {

    public record Who(long id, String name, String typ, String jti, String ip, String deviceId) {
    }

    private static final ThreadLocal<Who> HOLDER = new ThreadLocal<>();

    private Ctx() {
    }

    public static void set(Who who) {
        HOLDER.set(who);
    }

    public static Who get() {
        return HOLDER.get();
    }

    public static long userId() {
        Who w = HOLDER.get();
        return w == null ? 0L : w.id();
    }

    public static String ip() {
        Who w = HOLDER.get();
        return w == null ? null : w.ip();
    }

    public static void clear() {
        HOLDER.remove();
    }
}

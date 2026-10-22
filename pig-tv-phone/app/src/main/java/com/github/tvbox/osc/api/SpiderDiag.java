package com.github.tvbox.osc.api;

/**
 * spider（jar 里的站点实现）运行时错误的唯一落点。
 *
 * <p>为什么需要它：jar 里抛的异常在正常路径上会被吞三次 ——
 * <ol>
 *   <li>{@code JarLoader.getSpider} 里 {@code catch (Throwable)} 直接返回 {@code SpiderNull}</li>
 *   <li>{@code SourceViewModel.getSort} 里 {@code future.get()} 的 ExecutionException 只 printStackTrace</li>
 *   <li>结果 null → 前端只收到「没有分类」</li>
 * </ol>
 * 于是「站点挂了」「混淆把宿主类改名了」「jar 下了一半」在用户那里长得一模一样。
 * 这里把最后一次真实原因留下来，由 PhoneBridge 塞进首页空态。
 */
public final class SpiderDiag {

    private static volatile String last = null;

    private SpiderDiag() {
    }

    public static void clear() {
        last = null;
    }

    public static void set(String msg) {
        if (msg != null && !msg.isEmpty()) last = msg;
    }

    public static String get() {
        return last;
    }

    /** 把异常链里最内层的那条留下来（外层通常是 ExecutionException / InvocationTargetException，没什么信息） */
    public static void fail(String where, Throwable th) {
        if (th == null) return;
        Throwable c = th;
        int guard = 0;
        while (c.getCause() != null && c.getCause() != c && guard++ < 8) c = c.getCause();
        String msg = c.getMessage();
        last = (where == null || where.isEmpty() ? "" : where + " → ")
                + c.getClass().getSimpleName()
                + (msg == null || msg.isEmpty() ? "" : ": " + msg);
    }
}

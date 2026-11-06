package com.github.tvbox.osc.util;

import androidx.annotation.Nullable;

import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 失败退避：连续失败的条目按指数退避跳过重试，成功一次就清零。
 *
 * <p>为什么需要它：体检的缓存是「固定 TTL」——好结果留 1 小时，坏结果留 10 分钟。
 * 一个真的死掉的站，10 分钟后又被拉出来测一遍，又白等一次超时。一轮 40 多个站里
 * 只要有十几个钉子户，看门狗那 70 秒基本就耗在它们身上，而结论永远是同一个「失败」。
 *
 * <p>规则（照抄 Guovin/iptv-api 的 frozen.py，那个项目的验证过这套参数）：
 * <ul>
 *   <li>连续失败满 {@link #FREEZE_THRESHOLD} 次才开始冻结。前两次不算——
 *       换网、源站抽风、spider 首次 init 抖动，一次失败说明不了任何问题；</li>
 *   <li>冻结时长 = {@code 2^失败次数 × 60s}，封顶 24 小时。
 *       3 次 → 8 分钟，4 次 → 16 分钟，5 次 → 32 分钟，11 次就顶到 24 小时；</li>
 *   <li>成功一次 → <b>整条记录删掉</b>，不是把计数归零。留着旧计数会让
 *       之后一次完全无关的失败过早触发冻结；</li>
 *   <li>冻结到期 → 计数 <b>减一</b>，不是清零。网站恢复是渐进的，
 *       直接清零等于让刚复活一次的死站又拿到两次白测机会。</li>
 * </ul>
 *
 * <p>「计数归零」和「整条删除」这两种清零策略看着等价，实际差很远，别图省事合并。
 *
 * <p><b>必须落盘。</b>不持久化的话每次冷启动都是全新一轮，退避等于没做——
 * 而这个功能唯一的价值就是跨会话累积。用 Hawk 存，键是 URL 或站点 key，
 * 上限 {@link #MAX_ENTRIES} 条（超了按最久没碰过的淘汰），避免长期使用后无限膨胀。
 */
public final class FailBackoff {

    /** 连续失败到几次才开始冻结 */
    private static final int FREEZE_THRESHOLD = 3;
    /** 退避基数 */
    private static final long BASE_MS = 60_000L;
    /** 退避上限 */
    private static final long MAX_MS = 24 * 3600_000L;
    /** 最多记多少条，防止长期使用后无限膨胀 */
    private static final int MAX_ENTRIES = 400;

    private static final String SP_KEY = "pigtv_fail_backoff_v1";

    private static final ConcurrentHashMap<String, Entry> MAP = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private FailBackoff() {
    }

    private static final class Entry {
        int fails;
        long frozenUntil;
        long lastTouch;
    }

    // =====================================================================
    //  对外
    // =====================================================================

    /** 成功一次：整条记录删掉（连同冻结状态一起） */
    public static void markGood(@Nullable String key) {
        if (key == null || key.isEmpty()) return;
        ensureLoaded();
        if (MAP.remove(key) != null) save();
    }

    /** 失败一次：累加计数，到阈值后按指数算出冻结截止时间 */
    public static void markBad(@Nullable String key) {
        if (key == null || key.isEmpty()) return;
        ensureLoaded();
        long now = System.currentTimeMillis();
        Entry e = MAP.get(key);
        if (e == null) {
            e = new Entry();
            MAP.put(key, e);
        }
        e.fails++;
        e.lastTouch = now;
        e.frozenUntil = e.fails < FREEZE_THRESHOLD
                ? 0L
                : now + backoffOf(e.fails);
        save();
    }

    /** 现在是否在冻结期 */
    public static boolean isFrozen(@Nullable String key) {
        return remainMs(key) > 0;
    }

    /**
     * 距离解冻还有多久（毫秒）。未冻结 / 已到期返回 0。
     *
     * <p>到期时会顺手把计数减一并在必要时清理记录，所以这个方法是「会写」的，
     * 别在会被高频调用的渲染路径里用它。
     */
    public static long remainMs(@Nullable String key) {
        if (key == null || key.isEmpty()) return 0;
        ensureLoaded();
        Entry e = MAP.get(key);
        if (e == null) return 0;
        long now = System.currentTimeMillis();
        if (e.frozenUntil <= 0) return 0;
        if (e.frozenUntil > now) return e.frozenUntil - now;

        // 到期：计数减一，掉到 0 就整条删掉
        e.frozenUntil = 0L;
        e.fails = Math.max(0, e.fails - 1);
        if (e.fails == 0) {
            MAP.remove(key);
        }
        save();
        return 0;
    }

    /** 失败了几次（0 表示没记录） */
    public static int failsOf(@Nullable String key) {
        if (key == null || key.isEmpty()) return 0;
        ensureLoaded();
        Entry e = MAP.get(key);
        return e == null ? 0 : e.fails;
    }

    /** 清空（调试用；换源 / 换配置后如果想重来也可以调） */
    public static void reset() {
        MAP.clear();
        loaded = true;
        try {
            Hawk.delete(SP_KEY);
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    //  内部
    // =====================================================================

    private static long backoffOf(int fails) {
        // 2^fails 在 fails 大时会溢出，先夹住指数
        int shift = Math.min(fails, 20);
        long v = (1L << shift) * BASE_MS;
        if (v <= 0 || v > MAX_MS) v = MAX_MS;
        return Math.min(v, MAX_MS);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (FailBackoff.class) {
            if (loaded) return;
            load();
            loaded = true;
        }
    }

    private static void load() {
        String raw = null;
        try {
            raw = Hawk.get(SP_KEY, "");
        } catch (Throwable ignored) {
        }
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(raw);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject v = o.optJSONObject(k);
                if (v == null) continue;
                Entry e = new Entry();
                e.fails = v.optInt("f", 0);
                e.frozenUntil = v.optLong("u", 0L);
                e.lastTouch = v.optLong("t", 0L);
                if (e.fails > 0) MAP.put(k, e);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void save() {
        try {
            prune();
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Entry> en : MAP.entrySet()) {
                Entry e = en.getValue();
                if (e == null || e.fails <= 0) continue;
                JSONObject v = new JSONObject();
                v.put("f", e.fails);
                v.put("u", e.frozenUntil);
                v.put("t", e.lastTouch);
                o.put(en.getKey(), v);
            }
            Hawk.put(SP_KEY, o.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 超过上限时淘汰最久没碰过的，优先淘汰没在冻结期的（冻结中的还有利用价值） */
    private static void prune() {
        if (MAP.size() <= MAX_ENTRIES) return;
        long now = System.currentTimeMillis();
        List<Map.Entry<String, Entry>> all = new ArrayList<>(MAP.entrySet());
        all.sort((a, b) -> {
            boolean af = a.getValue().frozenUntil > now;
            boolean bf = b.getValue().frozenUntil > now;
            if (af != bf) return af ? 1 : -1;        // 冻结中的排后面（留）
            return Long.compare(a.getValue().lastTouch, b.getValue().lastTouch);
        });
        int drop = MAP.size() - MAX_ENTRIES;
        for (int i = 0; i < drop && i < all.size(); i++) {
            MAP.remove(all.get(i).getKey());
        }
    }
}

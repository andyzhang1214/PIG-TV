package com.pigtv.ui.dialog;

import com.github.catvod.crawler.Spider;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.pigtv.R;
import com.pigtv.api.ApiConfig;
import com.pigtv.bean.SourceBean;
import com.pigtv.ui.adapter.SelectDialogAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 站点体检：选中一个数据源之前，先知道它到底能不能取到内容。
 *
 * <p>为什么不能像「片源接口」那样量 HTTP 耗时：站点（{@link SourceBean}）没有自己的地址，
 * 只有一个 {@code csp_xxx} 类名，内容全靠 spider 组件现算。所以这里的做法是直接问 spider
 * 要一次首页；首页空就退一步问第一个分类。拿回来的列表条数 > 0 才算真的能看。
 *
 * <p>体检结果分两级用法：
 * <ul>
 *   <li>检测过程中列表保持全量，逐行出徽章（{@code 120部 / 无片 / 失败 / 脚本源}），能看见进度；</li>
 *   <li>全部测完把「无片 / 失败」的直接从列表里摘掉，只留有内容的那批，底部显示隐藏了几条。
 *       当前正在用的源无论好坏都保留，否则换源时自己的源会消失。</li>
 * </ul>
 *
 * <p>三条硬约束：
 * <ul>
 *   <li><b>脚本源一律跳过</b>：{@code .js} 站点走 quickjs 求值，端上必 SIGSEGV，碰都不能碰；
 *       这类源测不了，就不下「没内容」的结论，照样留在列表里标「脚本源」。</li>
 *   <li><b>只调实例方法，不动全局状态</b>：{@code getCSP()} 拿实例后直接调，不碰
 *       {@code ApiConfig.homeSource}，所以不会重载首页、不会打断正在播的片子。</li>
 *   <li><b>并发限 4</b>：spider 内部大多共用 okhttp 连接池，放开了跑会把弱网下的站全拖成超时，
 *       反而测不出真实快慢。</li>
 * </ul>
 */
public final class SiteProbe {

    public static final int ST_IDLE = 0;
    /** 有内容 */
    public static final int ST_OK = 2;
    /** 能问到，但一条内容都没有 */
    public static final int ST_EMPTY = 3;
    /** 组件没加载 / 抛异常 */
    public static final int ST_FAIL = 4;
    /** 脚本源，端上测不了 */
    public static final int ST_SKIP = 5;
    /** 这轮没轮到它（卡在看门狗外面）—— 既不算有内容也不算没内容，不能藏 */
    public static final int ST_PEND = 6;

    private static final int C_OK = 0xFF2E9E5B;
    private static final int C_EMPTY = 0xFFD08A2C;
    private static final int C_FAIL = 0xFFC0392B;
    private static final int C_SKIP = 0xFF6B7280;
    private static final int C_RUN = 0xFF3B6EA5;

    /** 「有内容」的结论留 1 小时：开机预热一次，一晚上不用再测 */
    private static final long FRESH_MS = 60 * 60 * 1000L;
    /**
     * 「无片 / 失败」只留 10 分钟。
     * 这两类最容易误判（站临时抽风、spider 第一次 init 拉配置失败、弱网超时），
     * 留太久等于把一个能用的站一直按在列表最底下。
     */
    private static final long FRESH_BAD_MS = 10 * 60 * 1000L;
    /** 兜底看门狗：某个 spider 死死卡住时，别让弹窗永远停在「检测中」。
     *  每站要问两次（首页 + 第一个分类拿总数），实测 6 并发 50 秒能跑完 44/48，
     *  剩下几个基本都是死站，给到 70 秒就够，再等只是让人干瞪眼。 */
    private static final long WATCHDOG_MS = 70_000L;

    private static final ConcurrentHashMap<String, Result> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> RUNNING =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    /** 并发 6：每站多了一次分类请求，4 个并发拉不动，全卡在队列里只会让看门狗提前收尾 */
    private static final Semaphore GATE = new Semaphore(6);

    /** 代次：点「重新检测」或关弹窗都 +1，旧一轮的回调发现代次不对就整批丢弃 */
    private static final AtomicInteger GEN = new AtomicInteger(0);

    private SiteProbe() {
    }

    public static final class Result {
        public int state = ST_IDLE;
        /** 拿到的影片条数 */
        public int count;
        public long ms;
        public String msg = "";
        public long at;
    }

    /** 一次弹窗的上下文：全量列表固定在 sites，adapter 上真正挂的是 shown */
    private static final class Panel {
        final SelectDialog<SourceBean> dialog;
        final SelectDialogAdapter<SourceBean> adapter;
        final TvRecyclerView rv;
        final List<SourceBean> sites;
        final String tip;
        List<SourceBean> shown;
        /** 这一轮是否已经收尾：收尾后迟到的单站结果不许再把标题改回「检测中」 */
        volatile boolean finished;

        Panel(SelectDialog<SourceBean> dialog, SelectDialogAdapter<SourceBean> adapter,
              TvRecyclerView rv, List<SourceBean> sites, String tip) {
            this.dialog = dialog;
            this.adapter = adapter;
            this.rv = rv;
            this.sites = sites;
            this.tip = tip;
            this.shown = sites;
        }
    }

    // =====================================================================
    //  开机预热
    // =====================================================================

    /** 预热代次：重复调用（切源回来又调一次）时，前一轮直接退出 */
    private static final AtomicInteger WARM_GEN = new AtomicInteger(0);

    /**
     * App 一起来就在后台把所有站点测一遍写进缓存，用户点「切换源」时直接命中，
     * 不用对着一排「检测中」干等。
     *
     * <p>不碰弹窗（{@link Panel}），只写 {@link #CACHE}；也不占 {@link #GEN}，
     * 免得跟正在打开的弹窗互相顶掉。缓存里已经有新鲜结论的站直接跳过。
     */
    public static void warm(final android.content.Context ctx) {
        if (ctx == null) return;
        final AtomicInteger gen = new AtomicInteger(WARM_GEN.incrementAndGet());
        POOL.execute(() -> {
            // 冷启动时配置和 jar 可能还没下完，这时候拿到的是空表 —— 先等一等
            List<SourceBean> all = null;
            for (int i = 0; i < 12; i++) {
                if (gen.get() != WARM_GEN.get()) return;
                try {
                    all = ApiConfig.get().getSourceBeanList();
                } catch (Throwable ignored) {
                }
                if (all != null && !all.isEmpty()) break;
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (all == null || all.isEmpty()) return;
            if (gen.get() != WARM_GEN.get()) return;

            for (final SourceBean sb : all) {
                if (sb == null || sb.getHide() == 1) continue;
                if (isFresh(CACHE.get(sb.getKey()), System.currentTimeMillis())) continue;
                POOL.execute(() -> {
                    if (gen.get() != WARM_GEN.get()) return;
                    boolean acquired = false;
                    try {
                        GATE.acquire();
                        acquired = true;
                        if (gen.get() != WARM_GEN.get()) return;
                        // 排队期间可能已经被弹窗那轮测完了，别重复打
                        if (isFresh(CACHE.get(sb.getKey()), System.currentTimeMillis())) return;
                        CACHE.put(sb.getKey(), check(sb));
                    } catch (Throwable ignored) {
                    } finally {
                        if (acquired) GATE.release();
                    }
                });
            }
        });
    }

    // =====================================================================
    //  弹窗接入
    // =====================================================================

    /**
     * 给站点选择弹窗挂上体检。必须在 {@code dialog.setAdapter(...)} 之后、
     * {@code dialog.show()} 之前调用，这样首次 bind 就能带出缓存结果。
     *
     * @param tip 原标题，检测过程中会在后面追加进度
     */
    public static void attach(final SelectDialog<SourceBean> dialog,
                              final TvRecyclerView rv,
                              final List<SourceBean> sites,
                              final String tip) {
        if (dialog == null || rv == null || sites == null || sites.isEmpty()) return;
        final SelectDialogAdapter<SourceBean> adapter = dialog.getAdapter();
        if (adapter == null) return;

        final Panel panel = new Panel(dialog, adapter, rv, sites, tip);

        // 1) 先挂徽章渲染，缓存的旧结果会立刻显示出来
        adapter.setBadgeInterface(new SelectDialogAdapter.BadgeInterface<SourceBean>() {
            @Override
            public String badgeOf(SourceBean value) {
                if (value == null) return "";
                String key = value.getKey();
                if (RUNNING.contains(key)) return "检测中";
                Result r = CACHE.get(key);
                return r == null ? "" : textOf(r);
            }

            @Override
            public int badgeColorOf(SourceBean value) {
                if (value == null) return C_SKIP;
                String key = value.getKey();
                if (RUNNING.contains(key)) return C_RUN;
                Result r = CACHE.get(key);
                return r == null ? C_SKIP : colorOf(r);
            }
        });

        // 2) 底部「重新检测」：按钮只管发起，缓存清理和列表复原都在 run(true) 里做
        dialog.setRefreshAction(new Runnable() {
            @Override
            public void run() {
                // 要写成 SiteProbe.run：匿名 Runnable 自己的 run() 会把重载解析抢走
                SiteProbe.run(panel, true);
            }
        });

        run(panel, false);
    }

    /**
     * 跑一轮体检。
     *
     * @param force true = 忽略缓存全部重测（按「重新检测」时）
     */
    private static void run(final Panel p, boolean force) {
        final int gen = GEN.incrementAndGet();
        p.finished = false;
        // 弹窗一开就把「开机预热」整批作废：预热还有几十个任务堵在 GATE 上排队，
        // 弹窗自己的检测会排在它们后面，看起来像点了没反应。
        // 作废后排队中的预热任务拿到许可就立刻退出，许可马上回到弹窗这边。
        WARM_GEN.incrementAndGet();
        if (force) {
            for (SourceBean sb : p.sites) {
                CACHE.remove(sb.getKey());
                RUNNING.remove(sb.getKey());
            }
            // 先把全量列表摆回去，边测边出徽章，别让人对着过滤后的短列表猜
            p.shown = new ArrayList<>(p.sites);
            int idx = identityIndex(p.sites, ApiConfig.get().getHomeSourceBean());
            p.adapter.setData(p.shown, Math.max(idx, 0));
        }
        p.dialog.setHint(null);

        // 3) 只补测「没结果或结果过期」的
        final List<SourceBean> todo = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (SourceBean sb : p.sites) {
            Result r = CACHE.get(sb.getKey());
            if (force || !isFresh(r, now)) todo.add(sb);
        }
        // 全部命中缓存：直接按上次结果排好，别让人看到「先原始序、再跳一下」
        if (todo.isEmpty()) {
            finish(p, gen);
            return;
        }

        final AtomicInteger left = new AtomicInteger(todo.size());
        final int total = todo.size();

        // 4) 弹窗关掉就别再发新任务了（已经在跑的没法中断，spider 不支持 cancel）
        p.dialog.setOnDismissListener(d -> GEN.incrementAndGet());

        // 5) 看门狗：到点还没跑完就强制收尾，把没回来的标成未测
        final Runnable watchdog = () -> {
            if (gen != GEN.get()) return;
            for (SourceBean sb : todo) {
                if (CACHE.get(sb.getKey()) == null) {
                    Result r = new Result();
                    r.state = ST_PEND;
                    r.msg = "未测";
                    // at=0：明确「这不是结论」，下次打开弹窗一定重测，
                    // 不能让它靠在 30 分钟缓存里冒充结果
                    r.at = 0;
                    CACHE.put(sb.getKey(), r);
                    // 卡死的 spider 永远不返回，RUNNING 里那条得在这里摘掉，
                    // 否则徽章会一直停在「检测中」，收尾了也不改
                    RUNNING.remove(sb.getKey());
                }
            }
            left.set(0);
            finish(p, gen);
        };
        p.rv.postDelayed(watchdog, WATCHDOG_MS);

        // 6) 开跑：每条测完只刷自己那一行，焦点不动
        for (final SourceBean sb : todo) {
            POOL.execute(() -> {
                boolean acquired = false;
                try {
                    GATE.acquire();
                    acquired = true;
                    if (gen != GEN.get()) return;
                    RUNNING.add(sb.getKey());
                    refreshRow(p, gen, sb);

                    final Result r = check(sb);
                    CACHE.put(sb.getKey(), r);
                    RUNNING.remove(sb.getKey());
                    if (gen != GEN.get()) return;
                    refreshRow(p, gen, sb);

                    final int remain = left.decrementAndGet();
                    p.rv.post(() -> {
                        if (gen != GEN.get()) return;
                        // 已经收尾（看门狗到点）之后才回来的结果：只更新那一行的徽章，
                        // 不能再把标题改回「检测中 n/N」—— 那种「测完了又跳回检测中」
                        // 看着像卡死
                        if (p.finished) return;
                        p.dialog.setTip(p.tip + "   检测中 " + (total - remain) + "/" + total);
                        if (remain <= 0) {
                            p.rv.removeCallbacks(watchdog);
                            finish(p, gen);
                        }
                    });
                } catch (Throwable ignored) {
                    RUNNING.remove(sb.getKey());
                    if (left.decrementAndGet() <= 0) {
                        p.rv.post(() -> {
                            if (gen == GEN.get()) finish(p, gen);
                        });
                    }
                } finally {
                    if (acquired) GATE.release();
                }
            });
        }
        p.dialog.setTip(p.tip + "   检测中 0/" + total);
    }

    /**
     * 全部测完：按「能用的在上、没片/失败的沉底」重排，一条都不摘掉。
     *
     * <p>以前是把无片/失败的直接从列表里删掉，结果体检误判的站（用户明明点进去能播）
     * 在弹窗里直接消失，连点都点不到。宁可好坏都摆出来，让顺序说明问题。
     */
    private static void finish(final Panel p, final int gen) {
        if (gen != GEN.get()) return;
        p.finished = true;
        p.dialog.setTip(p.tip);

        final SourceBean cur = ApiConfig.get().getHomeSourceBean();
        int noData = 0;
        for (SourceBean sb : p.sites) {
            Result r = CACHE.get(sb.getKey());
            if (r != null && (r.state == ST_EMPTY || r.state == ST_FAIL)) noData++;
        }

        List<SourceBean> visible = new ArrayList<>(p.sites);
        Collections.sort(visible, new Comparator<SourceBean>() {
            @Override
            public int compare(SourceBean a, SourceBean b) {
                boolean ac = a == cur;
                boolean bc = b == cur;
                if (ac != bc) return ac ? -1 : 1;
                Result ra = CACHE.get(a.getKey());
                Result rb = CACHE.get(b.getKey());
                int ka = rank(ra);
                int kb = rank(rb);
                if (ka != kb) return ka - kb;
                long ma = ra == null ? Long.MAX_VALUE : ra.ms;
                long mb = rb == null ? Long.MAX_VALUE : rb.ms;
                return Long.compare(ma, mb);
            }
        });

        p.shown = visible;
        int idx = Math.max(identityIndex(visible, cur), 0);
        p.adapter.setData(visible, idx);

        // 底部提示：不再说「藏了几条」，只说明排序口径
        if (noData > 0) {
            p.dialog.setHint(p.dialog.getContext().getString(R.string.v8_probe_hidden, noData));
        } else {
            p.dialog.setHint(null);
        }

        // setData 走的是 notifyDataSetChanged，焦点会丢，这里补回来
        final int target = idx;
        p.rv.post(() -> {
            try {
                p.rv.setSelectedPosition(target);
                p.rv.setSelection(target);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 结果出来后只刷对应的那一行；用对象引用找位置，过滤/排序后 position 会变 */
    private static void refreshRow(final Panel p, final int gen, final SourceBean target) {
        if (gen != GEN.get()) return;
        final int pos = identityIndex(p.shown, target);
        if (pos < 0) return;
        p.rv.post(() -> {
            if (gen != GEN.get()) return;
            try {
                p.adapter.notifyItemChanged(pos);
            } catch (Throwable ignored) {
            }
        });
    }

    private static int identityIndex(List<SourceBean> list, SourceBean target) {
        if (list == null || target == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) return i;
        }
        return -1;
    }

    // =====================================================================
    //  单个站点检测
    // =====================================================================

    public static Result check(SourceBean sb) {
        long t0 = System.currentTimeMillis();
        Result r = checkOnce(sb);
        // 首轮就判「失败」而且失败得很快（不到 8 秒）→ 多半是 spider 第一次 init 抖动
        // （要现拉配置/校验 token），不是站真的死了。用户反馈「标失败的站进去能正常播」
        // 基本都是这一类。超时（>8s）不重试 —— 那只会再等一次超时。
        if (r.state == ST_FAIL && System.currentTimeMillis() - t0 < 8_000L) {
            Result again = checkOnce(sb);
            if (again.state != ST_FAIL) r = again;
        }
        r.ms = System.currentTimeMillis() - t0;
        r.at = System.currentTimeMillis();
        return r;
    }

    /**
     * 探一次。判定顺序是「能拿到内容就算活」，任何一步抛异常都不直接判死：
     * <ol>
     *   <li>首页 {@code homeContent} —— 只当线索用，抛异常不算结论（不少爬虫只实现分类，
     *       首页直接 throw，按老逻辑就会被判「失败」，但点进去照样能播）；</li>
     *   <li>分类 {@code categoryContent} —— 试前 3 个，任一有内容即算活。只试第一个的话，
     *       撞上「最近更新」/需登录的空分类就会被判「无片」；</li>
     *   <li>两边都调通但都没内容 → 无片（ST_EMPTY）；全部抛异常 → 失败（ST_FAIL）。</li>
     * </ol>
     */
    private static Result checkOnce(SourceBean sb) {
        Result r = new Result();
        String api = sb == null || sb.getApi() == null ? "" : sb.getApi().trim();

        if (isScriptSite(api)) {
            r.state = ST_SKIP;
            r.msg = "脚本源";
            return r;
        }
        Spider sp = null;
        try {
            sp = ApiConfig.get().getCSP(sb);
        } catch (Throwable ignored) {
        }
        if (sp == null) {
            r.state = ST_FAIL;
            r.msg = "组件里没有这个站";
            return r;
        }

        String lastErr = null;
        boolean anyCallOk = false;      // 有一次调用能正常返回，就说明站是通的
        JSONObject home = null;
        try {
            home = toObj(sp.homeContent(false));
            anyCallOk = true;
        } catch (Throwable e) {
            lastErr = errText(e);
        }
        JSONArray classes = home == null ? null : home.optJSONArray("class");
        int homeLen = listLen(home);

        // 数量怎么来的：首页 homeContent 的 list 只是推荐位，很多源就给十几条，
        // 拿它当「这个站有几部片」会差几个数量级。所以再问分类接口 ——
        // spider 的 categoryContent 通常带 total（分类总数）或 pagecount × limit。
        // 两样都没有就只报「可用」，不拿首页条数冒充总片量。
        int total = 0;
        int bestLen = homeLen;
        int classCount = classes == null ? 0 : classes.length();
        int tryN = Math.min(classCount, 3);
        for (int i = 0; i < tryN; i++) {
            String tid = tidOf(classes.optJSONObject(i));
            if (tid.isEmpty()) continue;
            try {
                JSONObject cat = toObj(sp.categoryContent(tid, "1", false,
                        new HashMap<String, String>()));
                anyCallOk = true;
                int t = totalOf(cat);
                int len = listLen(cat);
                if (t > total) total = t;
                if (len > bestLen) bestLen = len;
                if (total > 0 || len > 0) break;   // 有结论了，别再挨个分类打
            } catch (Throwable e) {
                lastErr = errText(e);
            }
        }

        if (total > 0) {
            r.state = ST_OK;
            r.count = total;
            r.msg = countText(total, classCount > 1);
        } else if (bestLen > 0) {
            r.state = ST_OK;
            r.count = bestLen;
            // 「可用」不是「有内容」：TV 网格一行放不下四个字，会把站名挤成「厂...」
            r.msg = "可用";
        } else if (anyCallOk) {
            r.state = ST_EMPTY;      // 接口调通了，只是真的没内容
            r.msg = "取不到内容";
        } else {
            r.state = ST_FAIL;       // 一次都没调通
            r.msg = lastErr == null ? "连不上" : lastErr;
        }
        return r;
    }

    private static String errText(Throwable e) {
        String m = e == null ? null : e.getMessage();
        if (m == null || m.isEmpty()) m = e == null ? "未知错误" : e.getClass().getSimpleName();
        return m.length() > 40 ? m.substring(0, 40) : m;
    }

    /** 脚本源：quickjs 求值必崩，快速搜索里也是直接跳过的 */
    private static boolean isScriptSite(String api) {
        if (api.isEmpty()) return false;
        String a = api.toLowerCase();
        return a.endsWith(".js") || a.contains(".js?") || a.contains("/drpy") || a.startsWith("js:");
    }

    private static JSONObject toObj(String json) {
        if (json == null) return null;
        String s = json.trim();
        if (s.isEmpty() || s.charAt(0) != '{') return null;
        try {
            return new JSONObject(s);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int listLen(JSONObject o) {
        if (o == null) return 0;
        JSONArray a = o.optJSONArray("list");
        return a == null ? 0 : a.length();
    }

    /**
     * 分类接口自己报的总数：优先 total，退而求其次 pagecount × limit。
     *
     * <p>两个坑：有些源用 {@code pagecount = Integer.MAX_VALUE} 表示「无限/未知」，
     * 有些源 total 直接给 {@code 2147483647}，乘起来还会整型溢出 ——
     * 超过合理量级的一律当「没报」，宁可显示「有内容」也不甩个 2 亿部出来。
     */
    private static final long COUNT_MAX = 5_000_000L;

    private static int totalOf(JSONObject o) {
        if (o == null) return 0;
        long total = o.optLong("total", 0);
        if (total > 0 && total <= COUNT_MAX) return (int) total;
        long pc = o.optLong("pagecount", 0);
        long limit = o.optLong("limit", 0);
        if (pc <= 0 || limit <= 0) return 0;
        long v = pc * limit;                       // long 乘，不会溢出
        if (v > 0 && v <= COUNT_MAX) return (int) v;
        return 0;
    }

    /** 只有站方自己报的量才写成数字；atLeast 表示只量了一个分类，这是下限 */
    private static String countText(int n, boolean atLeast) {
        // 十部以下就别报数字了：不管是「1+」还是「1部」，看着都像在说这个站只有一部片。
        // 真要只有一部的站，用户点进去一眼就看见了，不必在这里提前劝退。
        if (n < 10) return "可用";
        String num;
        if (n >= 10000) {
            String s = String.format(java.util.Locale.US, "%.1f", n / 10000f);
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            num = s + "万";
        } else {
            num = String.valueOf(n);
        }
        return atLeast ? num + "+" : num + "部";
    }

    /** 一个分类节点的 tid；有的源写 type_id，有的源写 id */
    private static String tidOf(JSONObject c) {
        if (c == null) return "";
        String tid = c.optString("type_id", "");
        if (tid.isEmpty()) tid = c.optString("id", "");
        return tid;
    }

    private static String textOf(Result r) {
        switch (r.state) {
            case ST_OK:
                return r.msg == null || r.msg.isEmpty() ? (r.count + "部") : r.msg;
            case ST_EMPTY:
                return "无片";
            case ST_FAIL:
                return "失败";
            case ST_SKIP:
                return "脚本源";
            case ST_PEND:
                return "未测";
            default:
                return "";
        }
    }

    private static int colorOf(Result r) {
        switch (r.state) {
            case ST_OK:
                return C_OK;
            case ST_EMPTY:
                return C_EMPTY;
            case ST_FAIL:
                return C_FAIL;
            default:
                return C_SKIP;
        }
    }

    /** 排序权重：越小越靠前。未测的夹在「能用」和「没内容」之间 —— 它只是这轮没轮到 */
    private static int rank(Result r) {
        if (r == null) return 2;
        switch (r.state) {
            case ST_OK:
                return 0;
            case ST_IDLE:
            case ST_PEND:
                return 2;
            case ST_SKIP:
                return 3;
            case ST_EMPTY:
                return 4;
            case ST_FAIL:
                return 5;
            default:
                return 2;
        }
    }

    /** 缓存里的结论还能不能用：好结果 1 小时，无片/失败只认 10 分钟 */
    private static boolean isFresh(Result r, long now) {
        if (r == null) return false;
        if (r.state == ST_IDLE || r.state == ST_PEND) return false;   // 不是结论
        long ttl = (r.state == ST_EMPTY || r.state == ST_FAIL) ? FRESH_BAD_MS : FRESH_MS;
        return r.at > 0 && now - r.at <= ttl;
    }
}

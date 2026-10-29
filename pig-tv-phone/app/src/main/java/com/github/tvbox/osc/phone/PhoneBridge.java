package com.github.tvbox.osc.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import com.github.catvod.crawler.Spider;
import com.bumptech.glide.Glide;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.api.JarFetcher;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.ui.activity.PlayActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.convert.StringConvert;
import com.orhanobut.hawk.Hawk;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手机版数据桥：把 Box 的数据层（ApiConfig / SourceViewModel / RoomDataManger）
 * 暴露给 WebView 里的手机 UI。
 *
 * 约定：
 *  - 所有方法参数都用 String（JS 数字传过来是 double，用 int 声明会 TypeError）
 *  - 每个方法都以 cbId 结尾，结果通过 window.__cb(cbId, jsonString) 回传
 *  - 阻塞操作一律丢到线程池，绝不占用 JS 线程
 */
public class PhoneBridge {

    /** 全站搜索的总预算：超过这个时间就收工（结果早就流式推给前端了，用户不用干等） */
    private static final long SEARCH_BUDGET_MS = 30000L;
    /** 单站搜索超时（秒）：取组件 + 搜索 + 解析都算在里面。
     *  12 秒是给「组件首次初始化」留的余量 —— 自带组件的站第一次用会先拉一次首页，
     *  慢站经常要好几秒，7 秒会把它们全判超时。 */
    private static final int SITE_TIMEOUT_S = 12;
    /** 换源要搜的站点数：比主搜索少（换源是「找同一部片的其他源」，不能一直转圈） */
    private static final int ALT_SITE_LIMIT = 24;

    private final Activity act;
    private final WebView web;
    private final SourceViewModel vm;
    private final Gson gson = new Gson();
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    /**
     * 搜索必须用独立的池：搜索任务自己会阻塞等待 N 个子任务，
     * 如果和子任务共用同一个池，多个搜索并发时会把线程全部占死。
     * 24 = 全站搜索的并发度（实测配置里 128 个可搜站，16 不够铺开）。
     * <p>组件下载不会再占这个池：JarLoader 里按组件地址加了锁，
     * 同一个 jar 只下一次，不会几十个站一起抢。</p>
     */
    private final ExecutorService searchPool = Executors.newFixedThreadPool(24);
    /**
     * 换源专用池：换源同样要并发打所有站点，和搜索共用一个池会互相饿死
     * （两个主任务各占 1 线程等待，剩下的线程跑不完子任务，双双超时）。
     */
    private final ExecutorService altPool = Executors.newFixedThreadPool(18);
    /**
     * 搜索协调线程：单独一个，不跟搜索子任务抢线程
     * （它要等所有子任务、再汇总，顺带发最终回包）。
     */
    private final ExecutorService searchCoord = Executors.newSingleThreadExecutor();
    /**
     * 组件（jar）预热池：全站搜索最多用到 7~8 个不同组件，
     * 下一个可能几十秒（海外机房 + 断点续传），不能占着 searchPool 的线程。
     */
    private final ExecutorService jarPool = Executors.newFixedThreadPool(8);
    /**
     * 旁路池：单站超时后任务会被放弃，但线程还在跑（网络调用不可中断，
     * 只能等 OkHttp 自己的读超时）。固定 40 个，别用 cached ——
     * cached 会让卡住的任务无限攒线程，低配机上直接把内存吃光。
     */
    private final ExecutorService bgPool = Executors.newFixedThreadPool(40);
    /**
     * 兜底代数：正常情况下代次由前端给（前端每次搜索都会自增，原生原样回带）。
     * 只有前端没传（比如老版本前端）才用这个自增的号顶上。
     */
    private final AtomicInteger searchGen = new AtomicInteger();
    /** 当前正在跑的那一轮搜索的代次：用户又搜了别的词，上一轮立刻作废 */
    private volatile int curSearchSeq = 0;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 当前正在等待的请求（同一时刻只允许一个，UI 侧是串行操作的） */
    private String sortCb = null;
    private String detailCb = null;
    private final List<MovieSort.SortData> sorts = Collections.synchronizedList(new ArrayList<MovieSort.SortData>());
    /**
     * 分类列表请求的 FIFO 队列。
     * <p>
     * SourceViewModel.listResult 是**一条** LiveData（postValue 还会把连发的值合并掉），
     * 结构上没法把「回来的结果」和「谁发的请求」对上。以前用单个 listCb 槽存回调，
     * 而首页补推荐池一次要并发 12 个 list —— 12 个请求在几十微秒内把槽覆盖成最后一个，
     * 第一条结果回来就把它消费掉，剩下 11 个回调永远不触发
     * （真机表现：推荐墙只剩一个分类）。所以这里改成串行队列：
     * 一次只发一个，回来一个再发下一个，每个请求各自记着自己的 cbId / page / 筛选表。
     */
    private final java.util.ArrayDeque<ListReq> listQ = new java.util.ArrayDeque<>();
    /**
     * 后台队列：首页推荐池补桶用。
     * 分两条队列是因为它一共有 8 个请求，串行之后如果和用户的请求排在一起，
     * 用户点开分类页要先等最多 8 个网络往返 —— 推荐墙是装饰性的，不该挡路。
     * pumpList 永远先取 listQ，listQ 空了才轮到 hotQ。
     */
    private final java.util.ArrayDeque<ListReq> hotQ = new java.util.ArrayDeque<>();
    private boolean listBusy = false;
    /** 正在飞的那一条。从两条队列里取的时候队列里就已经没有它了，只能单独记着 */
    private ListReq flyingReq = null;
    /** 代次：超时看门狗只认自己那一代，避免把后一个请求误判超时 */
    private int listToken = 0;
    /** 单个列表请求的上限。串行之后一个卡死的请求会堵住整条队列，必须有兜底 */
    private static final long LIST_TIMEOUT_MS = 20000L;

    /** 队列里的一条列表请求 */
    private static final class ListReq {
        final String cbId;
        /** 热池请求用的是 SortData 的副本，见 {@link #hotList} */
        final MovieSort.SortData sd;
        final int page;
        /** 筛选表要在真正发请求前才写进 sd，否则排队期间会被后一个请求覆盖 */
        final String filterJson;
        boolean done = false;

        ListReq(String cbId, MovieSort.SortData sd, int page, String filterJson) {
            this.cbId = cbId;
            this.sd = sd;
            this.page = page;
            this.filterJson = filterJson;
        }
    }
    /**
     * 配置里的 spider jar 只有 TV 版 HomeActivity 会去下载，手机版根本走不到那里。
     * 不加载 jar，所有 type=3 的站点（走 CSP 的）都会拿不到数据。
     */
    private volatile boolean jarReady = false;
    /** jar 加载必须是单飞：并发时多份 loadJar 会同时占住线程池，把 list/detail 全堵死 */
    private final Object jarLock = new Object();
    private boolean jarLoading = false;

    // ==================================================================
    //  详情 → 播放 的数据复用
    // ==================================================================

    /**
     * 详情页解析出来的 VodInfo 缓存。
     *
     * 之前 play() 会自己再拉一次详情，走的是另一套解析（fetchDetailSync），
     * 和详情页那份数据不一定一致 —— 详情页明明有 40 集，点播放却说
     * 「这个片源没有播放列表」。现在播放直接用详情页那一份，
     * 用户看到几集就一定能播第几集。
     */
    private final LinkedHashMap<String, VodInfo> vodCache =
            new LinkedHashMap<String, VodInfo>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Entry<String, VodInfo> eldest) {
                    return size() > 12;
                }
            };

    /** detailCb 的保留值：这次详情请求是为了开播，不是给前端渲染 */
    private static final String PLAY_TOKEN = "\u0000play";

    /** 待开播的请求（只有拿到详情后才知道该播哪一集） */
    private volatile PlayReq pendingPlay = null;

    private static final class PlayReq {
        final String sourceKey;
        final String vodId;
        final String flag;
        final int ep;

        PlayReq(String sourceKey, String vodId, String flag, int ep) {
            this.sourceKey = sourceKey;
            this.vodId = vodId;
            this.flag = flag;
            this.ep = ep;
        }
    }

    private static String vodKey(String sourceKey, String vodId) {
        return nz(sourceKey) + "|" + nz(vodId);
    }

    private VodInfo cacheGet(String sourceKey, String vodId) {
        synchronized (vodCache) {
            return vodCache.get(vodKey(sourceKey, vodId));
        }
    }

    private void cachePut(VodInfo vi) {
        if (vi == null || vi.id == null || vi.isSeriesEmpty()) return;
        synchronized (vodCache) {
            vodCache.put(vodKey(vi.sourceKey, vi.id), vi);
        }
    }

    private void cacheClear() {
        synchronized (vodCache) {
            vodCache.clear();
        }
    }

    /**
     * 换片源接口 / 换线路之后，把「跟上一份片源绑在一起」的内存状态全丢掉。
     * <p>
     * 只清 vodCache 是不够的，另外两处会直接把旧数据喂到新界面上：
     * <ul>
     *   <li>{@link #sorts}：分类对象是旧接口的（id 相同语义不同），
     *       findSort 命中后 list() 会拿旧 site 去请求；</li>
     *   <li>{@link #listQ} / {@link #hotQ}：换接口前发出去的分类请求还在排队，
     *       回来时会把上一个接口的影片塞进新接口的分类页 —— 用户看到的就是
     *       「新接口里出现旧接口的片」。</li>
     * </ul>
     * 正在飞的那一条打不断，它的回包由前端 catSeq / hotSeq 丢弃。
     */
    private void resetSourceCaches(String reason) {
        sorts.clear();
        cacheClear();
        // 取分类也要作废：旧站点的 AbsSortXml 结果和旧站点的 homeContent 缓存
        // 一个都不能落到新站点上（否则切完线路首页还是上一个站的内容）
        try {
            vm.cancelSort();
        } catch (Throwable ignored) {
        }
        jarReady = false;           // 旧 jar 不一定对得上新接口
        jarFailAt = 0;              // 换了片源就允许立刻重新尝试加载 jar
        jarAutoTry = 0;             // 自动续传的名额也重算（新站点可能另一份包）
        jarWarmAt = 0;              // 新站点的自带 jar 立刻预热
        jarWarmKey = null;
        lastJarError = null;
        // 正在等结果的 config()/detail() 也要作废：它们的回包是旧接口的数据，
        // 落到新界面上就是「新接口里点开一个片，出来的是上一个接口的详情」。
        // 不回调（而不是回一条错误）：前端紧接着会自己再发一次请求，
        // 多回一条只会闪一下无意义的「已切换接口」提示。
        sortCb = null;
        detailCb = null;
        pendingPlay = null;
        java.util.ArrayList<ListReq> dropped = new java.util.ArrayList<>();
        synchronized (listQ) {
            while (!listQ.isEmpty()) dropped.add(listQ.pollFirst());
            while (!hotQ.isEmpty()) dropped.add(hotQ.pollFirst());
        }
        for (ListReq r : dropped) {
            if (r == null || r.done) continue;
            r.done = true;          // 标记掉，免得看门狗 / finishList 再回一次
            try {
                JSONObject o = new JSONObject();
                o.put("ok", 0);
                o.put("canceled", 1);
                o.put("msg", reason);
                o.put("page", r.page);
                o.put("list", new JSONArray());
                cb(r.cbId, o.toString());
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 同步等 jar 就绪。失败也放行：至少 json 直连的站点还能用，
     * 总比首页一片空白强。
     */
    private volatile String lastJarError = null;   // 给前端显示「为什么没内容」
    private volatile long jarFailAt = 0;           // 上次彻底失败的时间，用于冷却
    private volatile boolean jarAutoRetry = false; // 已经排了一次后台续传，别排第二次
    private volatile int jarAutoTry = 0;           // 后台自动续传一共试了几轮（上限 3）
    /** 站点自带 jar 的预热：独立线程池，不能和 pool(6) 抢线程（config 自己就占着一个） */
    private final ExecutorService jarWarmPool = Executors.newCachedThreadPool();
    private volatile String jarWarmKey = null;     // 正在预热的 jar 地址
    private volatile long jarWarmAt = 0;           // 上次预热时间（失败冷却 30 秒）
    /**
     * 本次取分类「请求时」的站点名，以及这个站点是不是真的依赖 spider 组件。
     * <p>
     * 回包时绝不能再读一次 {@code getHomeSourceBean()}：切站点的瞬间它会指向新站点，
     * 于是「内容还是 A 站的、名字已经变成 B 站」。首页那行名字就是这么乱跳的。
     */
    private volatile String sortSrcName = null;
    private volatile boolean sortNeedJar = false;
    // 重试交给 JarFetcher 内部做（它会分段续传 + 换 https），这里不再叠外层重试，
    // 否则一次点击会同时拉起好几条下载抢同一个 .part。
    private static final int JAR_MAX_TRY = 1;
    private static final long JAR_COOLDOWN_MS = 30_000L;  // 失败后 30 秒内不再尝试（避免每次切页都卡住）
    /**
     * 首页拿分类时最多等 jar 多久（只对真正依赖 jar 的爬虫站生效）。
     * <p>20 秒：分段续传下 1MB 通常 10~20 秒够了。再久就先让页面出结果，
     * 下载线程继续在后台跑，下完推一条 done 事件让页面自己刷新 —— 这是上游
     * {@code HomeActivity.initData()} 那种「回调里重新进入」的思路，只是我们没有一个
     * 可以反复重入的 Activity，只能在回包里补一次通知。</p>
     */
    private static final long JAR_WAIT_MS = 20_000L;

    /**
     * 把 jar 下载进度实时推给页面。
     * <p>
     * 用例：源站每次连接只给六十几 KB 就 RST，1MB 的组件要靠十几次续传才下得完，
     * 整个过程几十秒。没有进度条时用户只看到「还没有内容」，会以为卡死了；
     * 有进度条就能看出「一直在涨」，耐心等它下完。
     */
    private void bindJarProgress() {
        JarFetcher.setProgressSink((got, total, noResume) -> {
            try {
                JSONObject o = new JSONObject();
                o.put("got", got);
                o.put("total", total);
                // 服务器不认断点续传时，界面要换文案（否则「进度不涨」看起来像卡死）
                o.put("nr", noResume ? 1 : 0);
                emit("jar", o.toString());
            } catch (Throwable ignored) {
            }
        });
    }

    /** 向前端推一个事件：window.__evt(name, jsonString) */
    private void emit(String name, String json) {
        final String js = "window.__evt&&window.__evt(" + JSONObject.quote(name) + ","
                + JSONObject.quote(json == null ? "{}" : json) + ")";
        main.post(() -> {
            try {
                web.evaluateJavascript(js, null);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 这个站点取分类时是否真的依赖 Spider jar。
     * <p>判据和 {@code ApiConfig.getCSP()} 的分发条件保持一致：
     * type=0/1 是 xml/json 直连；api 以 .js 结尾或含 .js? 走 QuickJS；含 .py 走 Python。
     * 这三类都不会去加载全局 spider jar，也就没必要让用户陪着等下载。</p>
     */
    private static boolean needsJar(SourceBean sb) {
        if (sb == null || sb.getType() != 3) return false;
        String api = sb.getApi();
        if (api == null || api.isEmpty()) return false;
        if (api.endsWith(".js") || api.contains(".js?")) return false;
        return !api.contains(".py");
    }

    private void ensureJarBlocking(long maxWaitMs) {
        if (jarReady) return;
        String spider;
        try {
            spider = ApiConfig.get().getSpider();
        } catch (Throwable th) {
            jarReady = true;
            return;
        }
        if (spider == null || spider.isEmpty()) {
            jarReady = true;
            return;
        }
        // 刚失败过就别立刻再卡一轮：旧 jar 若已降级可用，这里本来也不会走到
        if (jarFailAt > 0 && System.currentTimeMillis() - jarFailAt < JAR_COOLDOWN_MS) return;

        synchronized (jarLock) {
            if (jarReady) return;
            if (jarLoading) {
                // 已有线程在下载，等它结束就行，不重复发起
                long deadline = System.currentTimeMillis() + maxWaitMs;
                while (jarLoading && !jarReady) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) return;
                    try {
                        jarLock.wait(left);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                return;
            }
            jarLoading = true;
        }

        try {
            // 2026-10-22：原来 error() 里直接 jarReady = true，等于「失败一次，整个进程内
            // 40 个 type=3 站点全部当哑巴」，必须杀进程才可能恢复。现在改成失败可重试。
            for (int attempt = 1; attempt <= JAR_MAX_TRY && !jarReady; attempt++) {
                final CountDownLatch latch = new CountDownLatch(1);
                final boolean[] done = {false};
                // 等待窗口关闭后 jar 才下完：上游是「回调里重新 initData()」，
                // 我们没法重入，就推一条 done 事件，让页面自己再拉一次数据。
                final boolean[] late = {false};
                try {
                    ApiConfig.get().loadJar(false, spider, new ApiConfig.LoadConfigCallback() {
                        @Override
                        public void success() {
                            done[0] = true;
                            jarReady = true;
                            latch.countDown();
                            if (late[0]) {
                                android.util.Log.i("PigBridge", "jar 迟到下载完成，通知页面刷新");
                                emit("jar", "{\"got\":1,\"total\":1,\"done\":1}");
                            }
                        }

                        @Override
                        public void retry() {
                            latch.countDown();
                        }

                        @Override
                        public void error(String msg) {
                            lastJarError = msg;
                            latch.countDown();
                        }
                    });
                } catch (Throwable th) {
                    lastJarError = th.getMessage();
                    break;
                }
                // 最多等调用方给的时长。以前这里是 Math.max(maxWaitMs, JAR_WAIT_MS)，
                // 传 30s 进来实际等 60s —— 注释和代码对不上，冷启动白等半分钟。
                try {
                    latch.await(maxWaitMs, TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {
                }
                if (!done[0]) late[0] = true;   // 等待窗口关闭，之后的成功算「迟到」
                if (done[0]) break;
                if (attempt < JAR_MAX_TRY) {
                    try {
                        Thread.sleep(1200L * attempt);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        } finally {
            finishJarLoad();
        }
        if (!jarReady) {
            jarFailAt = System.currentTimeMillis();
            android.util.Log.w("PigBridge", "jar 加载失败，30 秒后可重试: " + lastJarError);
            armJarAutoRetry();
        }
    }

    /**
     * 站点自带组件（配置里每个 site 的 {@code "jar"} 字段）的预热。
     * <p>
     * 为什么需要：这类站点的主组件不是配置顶层的 spider，而是自己带一份 jar。
     * 原来的路径是「getSort → getCSP → JarLoader 现下」，而 getSort 外面套着
     * {@code future.get(20s)}：1MB 的包在限速源上 20 秒根本下不完，超时就把
     * 下载线程 cancel 掉 —— 于是这个站点永远是空的，而且报的还是
     * 「站点返回的分类解析不了」，看起来像站点坏了。
     * <p>
     * 这里在取分类之前先单独把这个 jar 弄好（JarFetcher 有 210 秒预算、分段续传），
     * 等不到就先让页面出结果，下完再推一条 jar done 让页面自己重拉。
     */
    private void warmSiteJar(final SourceBean sb, long maxWaitMs) {
        final String jar = siteJar(sb);
        if (jar.isEmpty()) return;
        if (jar.equals(jarWarmKey)) return;                  // 同一份已经在弄
        long now = System.currentTimeMillis();
        if (jarWarmAt > 0 && now - jarWarmAt < 30_000L) return;   // 失败冷却，别每次 config 都卡 20 秒
        jarWarmKey = jar;
        jarWarmAt = now;
        final CountDownLatch latch = new CountDownLatch(1);
        jarWarmPool.execute(() -> {
            boolean ok = false;
            try {
                // getCSP 内部把站点 jar 交给 JarFetcher 下到 csp/ 并建好 DexClassLoader。
                // 成功之后 getSort 里的 getCSP 直接命中缓存，20 秒超时就不会再掐断下载。
                com.github.catvod.crawler.Spider sp = ApiConfig.get().getCSP(sb);
                ok = sp != null && !(sp instanceof com.github.catvod.crawler.SpiderNull);
            } catch (Throwable ignored) {
            } finally {
                jarWarmKey = null;
                latch.countDown();
            }
            if (ok) {
                android.util.Log.i("PigBridge", "站点组件准备好了，通知页面重拉");
                emit("jar", "{\"got\":1,\"total\":1,\"done\":1}");
            }
        });
        try {
            latch.await(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
    }

    private static String siteJar(SourceBean sb) {
        try {
            String j = sb == null ? null : sb.getJar();
            return j == null ? "" : j.trim();
        } catch (Throwable th) {
            return "";
        }
    }

    /**
     * 组件下了一半就断了（源站限速 / 反复 RST）时，45 秒后自己再续一轮。
     * <p>
     * 这种站的毛病是「连一会儿就被掐」，靠用户手动点「重新加载」也能一点点下完
     * （残包是保留的），但没人会点那么多次。后台静默续传到成功，再通知页面刷新，
     * 用户只需要等，不需要操作。
     */
    private void armJarAutoRetry() {
        String diag = ApiConfig.get().getJarDiag();
        if (jarAutoRetry || jarAutoTry >= 3) return;
        // 服务器不认断点续传时，后台再续一轮也是同样结果 —— 得靠用户换网络
        if (diag != null && diag.contains("不认断点续传")) return;
        // 以前只认「残包保留」这一种措辞，整包模式失败（“连续 N 次都没超过 X KB”）
        // 和从来没下过（diag 为空）就完全不会自动重试，用户只能自己反复点。
        // 现在只要不是「明确白搭」，都排队续一轮，最多 3 次。
        jarAutoRetry = true;
        jarAutoTry++;
        pool.execute(() -> {
            try {
                Thread.sleep(45_000L);
                jarFailAt = 0;                 // 清掉冷却
                ensureJarBlocking(60_000L);
                if (jarReady) {
                    android.util.Log.i("PigBridge", "jar 后台续传成功，通知页面刷新");
                    emit("jar", "{\"got\":1,\"total\":1,\"done\":1}");
                }
            } catch (Throwable ignored) {
            } finally {
                jarAutoRetry = false;
            }
        });
    }

    private void finishJarLoad() {
        synchronized (jarLock) {
            jarLoading = false;
            jarLock.notifyAll();
        }
    }

    public PhoneBridge(Activity a, WebView w, SourceViewModel v) {
        this.act = a;
        this.web = w;
        this.vm = v;
        bindJarProgress();
        initObservers();
    }

    // ==================================================================
    //  LiveData 观察：把 ViewModel 的结果转成 JSON 回给 JS
    // ==================================================================

    private void initObservers() {
        vm.sortResult.observeForever(abs -> {
            if (sortCb == null) return;
            String id = sortCb;
            sortCb = null;
            try {
                JSONObject o = new JSONObject();
                JSONArray arr = new JSONArray();
                sorts.clear();
                if (abs != null && abs.classes != null && abs.classes.sortList != null) {
                    for (MovieSort.SortData sd : abs.classes.sortList) {
                        if (sd == null || TextUtils.isEmpty(sd.id)) continue;
                        sorts.add(sd);
                        arr.put(sortJson(sd));
                    }
                }
                SourceBean home = ApiConfig.get().getHomeSourceBean();
                // 分类为空说明这一趟什么都没拿到（接口挂了 / spider jar 没加载），
                // 不能再无脑回 ok:1 —— 前端会把它当成「接口正常但内容为空」，
                // 渲染成「还没有内容，先去配一个接口」，把真正的错误盖掉。
                o.put("ok", arr.length() > 0 ? 1 : 0);
                o.put("needConfig", 0);
                o.put("jarError", lastJarError == null ? "" : lastJarError);
                o.put("jarDiag", ApiConfig.get().getJarDiag());
                o.put("jarReady", jarReady ? 1 : 0);
                o.put("spiderError", ApiConfig.get().getSpiderError());
                boolean needJar = sortNeedJar;
                if (arr.length() == 0) {
                    // 优先级：组件确实没就绪 > 站点自己报的错 > 兜底文案。
                    // 只有「这个站点真的要用组件」时才可以把锅甩给组件 ——
                    // xml/json 直连站本来就不碰 jar，一句「组件还没准备好」纯属误导。
                    String msg;
                    if (needJar && lastJarError != null) msg = lastJarError;
                    else if (needJar && !jarReady) msg = "播放组件还没下载完，正在后台续传…";
                    else {
                        String se = ApiConfig.get().getSpiderError();
                        msg = (se == null || se.isEmpty()) ? "接口没有返回任何分类" : se;
                    }
                    o.put("msg", msg);
                }
                // 站名用「发请求那一刻」的，别在回调里重读（切站点瞬间会串台）
                String nm = sortSrcName;
                if (TextUtils.isEmpty(nm) && home != null) nm = home.getName();
                o.put("source", nm == null ? "" : nm);
                o.put("sorts", arr);
                if (abs != null && abs.videoList != null && !abs.videoList.isEmpty()) {
                    o.put("rec", videoArray(abs.videoList));
                } else {
                    o.put("rec", new JSONArray());
                }
                cb(id, o.toString());
            } catch (Throwable th) {
                cbErr(id, th);
            }
        });

        vm.listResult.observeForever(abs -> {
            // 一次只有一个 list 在飞，回来的结果必然是队首那个请求的
            ListReq r = currentListReq();
            if (r == null) return;
            String json = null;
            Throwable fail = null;
            try {
                JSONObject o = new JSONObject();
                List<Movie.Video> list = (abs != null && abs.movie != null) ? abs.movie.videoList : null;
                o.put("ok", (list != null && !list.isEmpty()) ? 1 : 0);
                o.put("page", r.page);
                o.put("pagecount", (abs != null && abs.movie != null) ? abs.movie.pagecount : 0);
                o.put("list", videoArray(list));
                json = o.toString();
            } catch (Throwable th) {
                fail = th;
            }
            finishList(r, json);
            if (fail != null) cbErr(r.cbId, fail);
        });

        vm.detailResult.observeForever(abs -> {
            if (detailCb == null) return;
            String id = detailCb;
            detailCb = null;
            // 「播放」借用了详情链路：它要的是 VodInfo，不是 JSON
            if (PLAY_TOKEN.equals(id)) {
                onPlayDetail(abs);
                return;
            }
            try {
                cb(id, detailJson(abs));
            } catch (Throwable th) {
                cbErr(id, th);
            }
        });
    }

    // ==================================================================
    //  JS 入口
    // ==================================================================

    /** 配置状态 + 分类列表 + 首页推荐 */
    @JavascriptInterface
    public void config(String cbId) {
        pool.execute(() -> {
            try {
                SourceBean home = ApiConfig.get().getHomeSourceBean();
                if (home == null || TextUtils.isEmpty(home.getApi())) {
                    JSONObject o = new JSONObject();
                    o.put("ok", 0);
                    o.put("needConfig", 1);
                    o.put("sorts", new JSONArray());
                    o.put("rec", new JSONArray());
                    cb(cbId, o.toString());
                    return;
                }
                // 上游 takagen99/Box 的 initData() 是「状态机 + 回调重入」：loadJar 没回来
                // 就不往下走，但它是异步重入，从不阻塞任何线程。我们这边是一次请求一次回包，
                // 只能同步等 —— 那就只让「真的需要 jar」的站点等。
                // xml / json 直连站点（以及 .js / .py 站点）取分类根本不碰 Spider，
                // 让它们陪等 20 秒纯属白等，这正是上游异步写法感觉不到、我们会吃亏的地方。
                //
                // 必须放在挂 sortCb 之前：等待期间如果 LiveData 推了旧值会把回调提前消费掉。
                // 先把「这次是为哪个站点取的」记下来，回包时按它回答（见 sortSrcName 注释）
                sortSrcName = home.getName();
                sortNeedJar = needsJar(home);
                if (sortNeedJar) ensureJarBlocking(JAR_WAIT_MS);
                // 站点自带的 jar（site 的 "jar" 字段）也要先弄好，否则 getSort 里
                // 那个 20 秒超时会把下载掐在半路（详见 warmSiteJar 注释）
                warmSiteJar(home, JAR_WAIT_MS);
                // jar 没就绪就跑 getSort，spider 还是 SpiderNull，返回的东西不是标准 JSON，
                // SourceViewModel 会把「组件没下完」写成「站点返回的分类解析不了（不是标准 JSON）」——
                // 用户看到的是一句假诊断，还会以为站点坏了。这里直接回真实状态：
                // 后台下载完成后 emit jar done，页面自己会重新拉一次。
                if (sortNeedJar && !jarReady && siteJar(home).isEmpty()) {
                    JSONObject o = new JSONObject();
                    o.put("ok", 0);
                    o.put("needConfig", 0);
                    o.put("jarReady", 0);
                    o.put("jarError", lastJarError == null ? "" : lastJarError);
                    o.put("jarDiag", ApiConfig.get().getJarDiag());
                    o.put("msg", lastJarError != null
                            ? lastJarError
                            : "播放组件还没下载完，正在后台续传…");
                    o.put("source", sortSrcName == null ? "" : sortSrcName);
                    o.put("sorts", new JSONArray());
                    o.put("rec", new JSONArray());
                    cb(cbId, o.toString());
                    return;
                }
                sortCb = cbId;
                vm.getSort(home.getKey());
                armSortWatchdog(cbId);
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /**
     * 取分类的看门狗。
     * <p>
     * 正常情况下 sortResult 一定会回一次（成功 / 空 / 异常都会 postValue），
     * 但只要中间有一环没回 —— spider 挂在 native 里、线程池被占满、
     * sortCb 被另一个请求顶掉 —— 前端那个 loadAll 就会永远停在「还没有内容」，
     * 左上角站名也不会刷新。用户看到的正是「切了线路，首页没反应」。
     * <p>
     * 所以这里给一个硬兜底：35 秒还没回就自己回一条错误，让页面至少有话说。
     * 真正的慢请求仍然会继续跑，结果若晚到会被 SourceViewModel 的代数机制丢弃。
     */
    private void armSortWatchdog(final String cbId) {
        main.postDelayed(() -> {
            try {
                if (sortCb == null || !sortCb.equals(cbId)) return;   // 已经回过 / 已被新请求接管
                sortCb = null;
                JSONObject o = new JSONObject();
                o.put("ok", 0);
                o.put("needConfig", 0);
                o.put("msg", "取分类超时（35 秒），站点没有响应");
                o.put("source", sourceName());
                o.put("sorts", new JSONArray());
                o.put("rec", new JSONArray());
                cb(cbId, o.toString());
            } catch (Throwable ignored) {
            }
        }, 35_000L);
    }

    /**
     * 手动重试 spider jar。首页空态里的「重试」按钮调它。
     * <p>
     * 存在的意义：jar 下载失败后为了不每次切页都卡 12 秒，加了 30 秒冷却，
     * 但用户不该被迫等 —— 点一下就清掉冷却、立刻重来一轮。
     */
    @JavascriptInterface
    public void jarRetry(String cbId) {
        pool.execute(() -> {
            try {
                jarFailAt = 0;
                jarReady = false;
                lastJarError = null;
                ensureJarBlocking(15000L);
                JSONObject o = new JSONObject();
                o.put("ok", jarReady ? 1 : 0);
                o.put("msg", lastJarError == null ? "" : lastJarError);
                cb(cbId, o.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /**
     * 分类内容。
     * <p>
     * filterJson 是 JS 传上来的**完整**筛选表 `{"class":"动作","area":"香港"}`，
     * 每次覆盖写进 SortData.filterSelect —— 不能增量合并，
     * 否则用户把某一项切回「全部」时旧值还留在 Map 里，接口会一直带着旧条件。
     * 空表代表不筛。页码 > 1 时 JS 会原样再传一次，翻页条件才不会丢。
     */
    @JavascriptInterface
    public void list(String sortId, String pageStr, String filterJson, String cbId) {
        pool.execute(() -> {
            try {
                MovieSort.SortData sd = findSort(sortId);
                if (sd == null) {
                    JSONObject o = new JSONObject();
                    o.put("ok", 0);
                    o.put("msg", "分类不存在");
                    o.put("list", new JSONArray());
                    cb(cbId, o.toString());
                    return;
                }
                int page = 1;
                try { page = Integer.parseInt(pageStr); } catch (Throwable ignored) { }
                synchronized (listQ) {
                    listQ.addLast(new ListReq(cbId, sd, page, filterJson));
                }
                pumpList();
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /**
     * 首页推荐池用的分类列表（后台请求）。
     * <p>
     * 和 {@link #list} 的区别只有两点，但两点都必须有：
     * <ul>
     *   <li>进的是低优先级的 hotQ，用户点分类不会被 8 个预热请求堵住；</li>
     *   <li>用 SortData 的**副本**、筛选表恒为空。热池只是「这个分类有些什么」，
     *       走共享对象的话会把用户在该分类上已选的筛选条件清掉（applyFilters 是覆盖写），
     *       等用户真进这个分类时看到的就成了不带条件的第一页。</li>
     * </ul>
     * 副本只带 id：getList 里 type 0/1 只用 id + filterSelect，type 3 的 spider
     * categoryContent 同样只吃 id，filters 那张筛选项清单是给 UI 用的，请求侧不需要。
     */
    @JavascriptInterface
    public void hotList(String sortId, String pageStr, String cbId) {
        pool.execute(() -> {
            try {
                MovieSort.SortData sd = findSort(sortId);
                if (sd == null) {
                    JSONObject o = new JSONObject();
                    o.put("ok", 0);
                    o.put("msg", "分类不存在");
                    o.put("list", new JSONArray());
                    cb(cbId, o.toString());
                    return;
                }
                int page = 1;
                try { page = Integer.parseInt(pageStr); } catch (Throwable ignored) { }
                ListReq r = new ListReq(cbId, copyForHot(sd), page, null);
                synchronized (listQ) {
                    hotQ.addLast(r);
                }
                pumpList();
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 只带 id / name 的空筛选 SortData，给后台请求用，避免污染共享对象 */
    private static MovieSort.SortData copyForHot(MovieSort.SortData src) {
        MovieSort.SortData cp = new MovieSort.SortData(src.id, src.name);
        cp.sort = src.sort;
        cp.filterSelect = new HashMap<>();
        return cp;
    }

    /** 队首那条正在飞的请求（没有在飞的就返回 null） */
    private ListReq currentListReq() {
        synchronized (listQ) {
            return listBusy ? flyingReq : null;
        }
    }

    /** 队列里没人在飞，就取队首发出去。用户请求优先，热池请求垫底。 */
    private void pumpList() {
        final ListReq r;
        final int tok;
        synchronized (listQ) {
            if (listBusy) return;
            r = !listQ.isEmpty() ? listQ.pollFirst() : hotQ.pollFirst();
            if (r == null) return;
            listBusy = true;
            flyingReq = r;
            tok = ++listToken;
        }
        // 筛选表到这一刻才写：排队期间同一个分类可能被改了条件，写早了会用上别人的条件
        applyFilters(r.sd, r.filterJson);
        // 串行之后一个卡死的请求会把整条队列堵死，超时兜底把它踹下去。
        // 残留风险：被踹掉的那条如果过一会儿又回来了，listResult 只有一个槽，
        // 结果会落到下一个请求的回调上。要根治得让 SourceViewModel 回包带请求标识，
        // 而 20 秒上限已经把这个窗口压到「源真的挂了」这种场景，先这样。
        main.postDelayed(() -> {
            boolean stale;
            synchronized (listQ) {
                stale = (tok != listToken) || r.done;
            }
            if (stale) return;
            JSONObject o = new JSONObject();
            try {
                o.put("ok", 0);
                o.put("msg", "请求超时，请重试");
                o.put("list", new JSONArray());
            } catch (Throwable ignored) {
            }
            finishList(r, o.toString());
        }, LIST_TIMEOUT_MS);
        try {
            vm.getList(r.sd, r.page);
        } catch (Throwable th) {
            finishList(r, null);
            cbErr(r.cbId, th);
        }
    }

    /**
     * 结束队首请求：回包 + 把队列往前推一个。
     * 回调和超时看门狗都会调这里，靠 ListReq.done 保证只生效一次。
     */
    private void finishList(ListReq r, String json) {
        synchronized (listQ) {
            if (r.done) return;
            r.done = true;
            if (flyingReq == r) flyingReq = null;
            listBusy = false;
        }
        if (json != null) cb(r.cbId, json);
        pumpList();
    }

    /**
     * 把 JS 的筛选表写进 SortData.filterSelect。
     * 值为空字符串 / null 的项直接不写，等于「全部」。
     */
    private void applyFilters(MovieSort.SortData sd, String filterJson) {
        if (sd.filterSelect == null) sd.filterSelect = new HashMap<>();
        synchronized (sd.filterSelect) {
            sd.filterSelect.clear();
            if (TextUtils.isEmpty(filterJson)) return;
            try {
                JSONObject f = new JSONObject(filterJson);
                java.util.Iterator<String> it = f.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = f.optString(k, "");
                    if (!TextUtils.isEmpty(k) && !TextUtils.isEmpty(v)) sd.filterSelect.put(k, v);
                }
            } catch (Throwable ignored) {
                // 传坏了就当不筛，不要把整个分类打废
            }
        }
    }

    /** 详情（含线路与选集） */
    @JavascriptInterface
    public void detail(String sourceKey, String vodId, String cbId) {
        pool.execute(() -> {
            try {
                detailCb = cbId;
                vm.getDetail(sourceKey, vodId);
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /**
     * 全站聚合搜索（对齐影视仓）。
     * <p>
     * 和旧版的三个区别：
     * ① <b>搜全部可搜站点</b> —— 旧版只搜前 12 个，实际配置里有一百多个可搜站；
     * ② <b>不跨站合并</b> —— 同一个片名在几个站搜到就列几条，每条带自己的站点名。
     * 旧版按片名去重，12 个站的结果被压成 2 条，用户以为「只有 2 部」；
     * ③ <b>流式返回</b> —— 搜完一个站就往前端推一次，状态栏实时显示「已搜 n/N」。
     */
    @JavascriptInterface
    public void search(String keyword, String seqStr, String cbId) {
        /* 代次由前端给（前端每次搜索都会自增），原生原样回带。
           前端拿它把上一轮的迟到流式结果丢掉 —— 连着搜两次时特别重要。 */
        int parsed = 0;
        try {
            parsed = Integer.parseInt(nz(seqStr).trim());
        } catch (Throwable ignored) {
        }
        final int seq = parsed > 0 ? parsed : searchGen.incrementAndGet();
        curSearchSeq = seq;
        searchCoord.execute(() -> {
            JSONObject out = new JSONObject();
            try {
                String wd = keyword == null ? "" : keyword.trim();
                if (wd.isEmpty()) {
                    out.put("ok", 0);
                    out.put("seq", seq);
                    out.put("list", new JSONArray());
                    out.put("total", 0);
                    out.put("done", 0);
                    cb(cbId, out.toString());
                    return;
                }

                final List<SourceBean> targets = pickSearchSites(searchSiteLimit());
                final int total = targets.size();
                final AtomicInteger done = new AtomicInteger();
                final AtomicInteger found = new AtomicInteger();
                final List<JSONObject> all = Collections.synchronizedList(new ArrayList<JSONObject>());
                final CountDownLatch latch = new CountDownLatch(total);
                final Set<String> warming = Collections.synchronizedSet(new HashSet<String>());
                final Set<String> jarBad = Collections.synchronizedSet(new HashSet<String>());
                /** 已经搜过并计过数的站：防止组件预热完成后重投时重复计数 */
                final Set<String> published = Collections.synchronizedSet(new HashSet<String>());
                final Map<String, List<SourceBean>> byJar = groupByJar(targets);

                for (final SourceBean sb : targets) {
                    searchPool.execute(() -> {
                        boolean finished = runSearchSite(seq, sb, wd, byJar, warming, jarBad, published,
                                done, found, all, total, latch);
                        // finished=false 表示这一站被丢去后台预热组件了，
                        // 等预热完会再投回来搜一次 —— 那时才减计数。
                        if (finished) latch.countDown();
                    });
                }

                /* 等全部搜完，但每 300ms 看一眼代次：
                   用户又搜了别的词就立刻收工，别让上一轮占着协调线程。 */
                long deadline = System.currentTimeMillis() + SEARCH_BUDGET_MS;
                boolean finishedAll = false;
                while (System.currentTimeMillis() < deadline) {
                    if (curSearchSeq != seq) break;
                    try {
                        if (latch.await(300, TimeUnit.MILLISECONDS)) {
                            finishedAll = true;
                            break;
                        }
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }

                JSONArray arr = new JSONArray();
                synchronized (all) {
                    for (JSONObject o : all) arr.put(o);
                }
                out.put("ok", arr.length() > 0 ? 1 : 0);
                out.put("seq", seq);
                out.put("q", wd);
                out.put("list", arr);
                out.put("total", total);
                out.put("done", done.get());
                out.put("sites", done.get());
                out.put("found", found.get());
                out.put("finished", finishedAll ? 1 : 0);
                cb(cbId, out.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /**
     * 搜一个站。
     *
     * @return true = 这一站本轮已经算完（调用方减计数）；
     *         false = 组件还没下好，已经丢去后台预热，预热完会再投回来搜一次。
     */
    private boolean runSearchSite(int seq, SourceBean sb, String wd,
                                  Map<String, List<SourceBean>> byJar, Set<String> warming, Set<String> jarBad,
                                  Set<String> published, AtomicInteger done, AtomicInteger found,
                                  List<JSONObject> all, int total, CountDownLatch latch) {
        final String siteKey = nz(sb.getKey());
        if (published.contains(siteKey)) {
            /* 这一站已经搜完并计过数了。
               组件预热期间它可能自己就搜完了（比如主组件早就下好），
               预热完再把它重投一次的话就会重复计数 —— 表现为「已搜 141/140」。 */
            return false;
        }
        final String jk = ApiConfig.get().cspKey(sb);
        JSONArray arr = new JSONArray();
        try {
            if (!jk.isEmpty()) {
                if (jarBad.contains(jk)) {
                    // 组件下不下来，这一站直接放弃。
                    // 不这么做的话，每个站都会再白白等一次组件超时。
                    published.add(siteKey);
                    publish(seq, wd, sb, arr, done, found, all, total);
                    return true;
                }
                if (!ApiConfig.get().cspReady(sb)) {
                    // 组件还没下好：丢去后台下载，下好了再回来搜这一站。
                    // 千万别在这里干等 —— 几十个站会一起堵在同一个组件上。
                    warmUpJar(seq, jk, sb, wd, byJar, warming, jarBad, published, done, found, all, total, latch);
                    return false;
                }
            }
            List<Movie.Video> got = searchOne(sb, wd);
            if (got != null) {
                Set<String> seen = new HashSet<>();
                for (Movie.Video v : got) {
                    if (v == null || TextUtils.isEmpty(v.name)) continue;
                    // 同一个站内部按片名去重（一个站常把同一部片列好几遍），
                    // 但**不跨站去重** —— 同一部片在几个站搜到就列几条，各带各的站点名。
                    if (!seen.add(normalizeName(v.name))) continue;
                    JSONObject o = videoJson(v);
                    o.put("site", nz(sb.getName()));
                    arr.put(o);
                }
            }
        } catch (Throwable ignored) {
        }
        published.add(siteKey);
        publish(seq, wd, sb, arr, done, found, all, total);
        return true;
    }

    /**
     * 后台把某个组件拉下来，拉好之后把用它的站重新投回搜索池。
     * <p>同一个组件只预热一次（几个站共用一个 jar 地址，JarLoader 内部也有下载锁）。</p>
     */
    private void warmUpJar(int seq, String jk, SourceBean sb, String wd,
                           Map<String, List<SourceBean>> byJar, Set<String> warming, Set<String> jarBad,
                           Set<String> published, AtomicInteger done, AtomicInteger found,
                           List<JSONObject> all, int total, CountDownLatch latch) {
        if (!warming.add(jk)) return;   // 已经在预热了
        jarPool.execute(() -> {
            boolean ok = false;
            try {
                ApiConfig.get().getCSP(sb);
                ok = ApiConfig.get().cspReady(sb);
            } catch (Throwable ignored) {
            }
            if (!ok) {
                jarBad.add(jk);
                android.util.Log.i("PhoneBridge", "搜索组件预热失败: " + jk);
            }
            List<SourceBean> group = byJar.get(jk);
            if (group == null) group = Collections.singletonList(sb);
            for (final SourceBean s2 : group) {
                searchPool.execute(() -> {
                    if (runSearchSite(seq, s2, wd, byJar, warming, jarBad, published,
                            done, found, all, total, latch)) {
                        // 这一站补搜完了，减掉协调线程那边的等待计数
                        latch.countDown();
                    }
                });
            }
        });
    }

    /** 把一个站的结果推给前端（顺带记账） */
    private void publish(int seq, String wd, SourceBean sb, JSONArray arr, AtomicInteger done,
                         AtomicInteger found, List<JSONObject> all, int total) {
        try {
            synchronized (all) {
                for (int i = 0; i < arr.length(); i++) all.add(arr.getJSONObject(i));
            }
            int f = found.addAndGet(arr.length());
            int d = done.incrementAndGet();
            JSONObject ev = new JSONObject();
            ev.put("seq", seq);
            ev.put("q", wd);
            ev.put("site", nz(sb.getName()));
            ev.put("items", arr);
            ev.put("done", d);
            ev.put("total", total);
            ev.put("found", f);
            emit("search", ev.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 按组件把站点分组（几个站常常共用同一个 jar） */
    private Map<String, List<SourceBean>> groupByJar(List<SourceBean> targets) {
        Map<String, List<SourceBean>> m = new LinkedHashMap<>();
        if (targets == null) return m;
        for (SourceBean sb : targets) {
            String jk = ApiConfig.get().cspKey(sb);
            if (jk == null || jk.isEmpty()) continue;
            List<SourceBean> l = m.get(jk);
            if (l == null) {
                l = new ArrayList<>();
                m.put(jk, l);
            }
            l.add(sb);
        }
        return m;
    }

    /** 播放：拿详情 → 组装 VodInfo → 交给原生 PlayActivity（横屏全屏） */
    @JavascriptInterface
    public void play(String sourceKey, String vodId, String flag, String epStr) {
        pool.execute(() -> {
            try {
                int ep = 0;
                try { ep = Integer.parseInt(epStr); } catch (Throwable ignored) { }

                // 首选：详情页刚解析出来的那一份，集数/线路与用户看到的一模一样
                VodInfo cached = cacheGet(sourceKey, vodId);
                if (cached != null && !cached.isSeriesEmpty()) {
                    startPlay(cached, nz(flag), ep);
                    return;
                }

                // 没缓存（进程被回收后从历史直接开播、或详情页没打开过）：
                // 走和详情页完全相同的解析链路 —— 它会做 beanList 拆分、
                // push:// 解析、磁力解析，比自己再拼一份靠谱。
                pendingPlay = new PlayReq(nz(sourceKey), nz(vodId), nz(flag), ep);
                detailCb = PLAY_TOKEN;
                vm.getDetail(sourceKey, vodId);
            } catch (Throwable th) {
                toast("播放失败：" + th.getMessage());
            }
        });
    }

    /** 详情链路回来了，把 AbsXml 变成 VodInfo 再开播 */
    private void onPlayDetail(AbsXml abs) {
        final PlayReq req = pendingPlay;
        pendingPlay = null;
        if (req == null) return;
        try {
            VodInfo vi = viFromAbs(req.sourceKey, req.vodId, abs);
            if (vi != null && !vi.isSeriesEmpty()) {
                startPlay(vi, req.flag, req.ep);
                return;
            }

            // 详情链路也没给出剧集，最后再直连同步拉一次（少数站点第二次才给数据）
            try {
                pool.execute(() -> {
                    VodInfo again = viFromAbs(req.sourceKey, req.vodId, fetchDetailSync(req.sourceKey, req.vodId));
                    if (again == null || again.isSeriesEmpty()) {
                        toast("这个片源没有播放列表，换个源试试");
                        return;
                    }
                    startPlay(again, req.flag, req.ep);
                });
            } catch (Throwable th) {
                toast("这个片源没有播放列表，换个源试试");
            }
        } catch (Throwable th) {
            toast("播放失败：" + th.getMessage());
        }
    }

    private VodInfo viFromAbs(String sourceKey, String vodId, AbsXml abs) {
        if (abs == null || abs.movie == null || abs.movie.videoList == null || abs.movie.videoList.isEmpty()) {
            return null;
        }
        return buildVodInfo(sourceKey, vodId, abs.movie.videoList.get(0));
    }

    /**
     * Movie.Video → VodInfo 的唯一构造点。详情页和播放共用，
     * 顺便把结果塞进缓存，保证两边永远看到同一份剧集。
     */
    private VodInfo buildVodInfo(String sourceKey, String vodId, Movie.Video v) {
        if (v == null) return null;
        try {
            if (!TextUtils.isEmpty(vodId)) v.id = vodId;
            VodInfo vi = new VodInfo();
            vi.setVideo(v);
            vi.sourceKey = TextUtils.isEmpty(sourceKey) ? nz(v.sourceKey) : sourceKey;
            cachePut(vi);
            return vi;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        }
    }

    /** 真正把 VodInfo 交给原生播放器：定线路、定集、按原版规则分堆、写历史 */
    private void startPlay(VodInfo vi, String flag, int ep) {
        try {
            if (vi == null || vi.isSeriesEmpty()) {
                toast("这个片源没有播放列表");
                return;
            }
            String useFlag = flag;
            if (TextUtils.isEmpty(useFlag) || !vi.seriesMap.containsKey(useFlag)) {
                useFlag = vi.seriesMap.keySet().iterator().next();
            }
            List<VodInfo.VodSeries> list = vi.seriesMap.get(useFlag);
            if (list == null || list.isEmpty()) {
                toast("这个线路是空的");
                return;
            }
            vi.playFlag = useFlag;
            if (ep < 0 || ep >= list.size()) ep = 0;
            // 有些源会给「第01集$」这种空地址，进去就是一句「视频播放出错」。
            // 这里退到最近的一个有效地址，实在没有才报错。
            if (TextUtils.isEmpty(list.get(ep).url)) {
                int fixed = -1;
                for (int i = 0; i < list.size(); i++) {
                    String u = list.get(i).url;
                    if (!TextUtils.isEmpty(u) && !u.startsWith("push://")) {
                        fixed = i;
                        break;
                    }
                }
                if (fixed < 0) {
                    toast("这条线路里没有有效播放地址，换个线路试试");
                    return;
                }
                ep = fixed;
            }
            // 和 DetailActivity 一样按集数分堆：组内索引 + 组号，
            // 否则 playGroupCount 为 0 时 PlayFragment 的「下一集」会除以零
            int groupCount = groupCountFor(list.size());
            vi.playGroupCount = groupCount;
            vi.playGroup = ep / groupCount;
            vi.playIndex = ep % groupCount;
            try {
                vi.playNote = list.get(ep).name;
            } catch (Throwable ignored) {
            }
            // 写历史（只记一份，不影响播放本身）
            try {
                RoomDataManger.insertVodRecord(vi.sourceKey, vi);
            } catch (Throwable ignored) {
            }

            final VodInfo send = vi;
            final String key = nz(vi.sourceKey);
            main.post(() -> {
                try {
                    Bundle b = new Bundle();
                    b.putString("sourceKey", key);
                    b.putSerializable("VodInfo", send);
                    // 「我的 → 播放默认全屏」决定走哪条路：
                    // false（默认）先在详情页上方开小窗，用户点全屏按钮才进横屏全屏页
                    boolean full = Hawk.get(HawkConfig.PHONE_PLAY_FULLSCREEN, false);
                    Intent it;
                    if (full) {
                        it = new Intent(act, PlayActivity.class);
                    } else {
                        MiniPlayActivity.dismissIfAny();   // 换集/换源：先收起旧的小窗
                        it = new Intent(act, MiniPlayActivity.class);
                    }
                    it.putExtras(b);
                    act.startActivity(it);
                } catch (Throwable th) {
                    toast("启动播放器失败：" + th.getMessage());
                }
            });
        } catch (Throwable th) {
            toast("播放失败：" + th.getMessage());
        }
    }

    /** 观看历史 */
    @JavascriptInterface
    public void history(String cbId) {
        pool.execute(() -> {
            try {
                List<VodInfo> all = RoomDataManger.getAllVodRecord(60);
                JSONArray arr = new JSONArray();
                if (all != null) {
                    for (VodInfo vi : all) {
                        if (vi == null) continue;
                        JSONObject o = new JSONObject();
                        o.put("id", nz(vi.id));
                        o.put("name", nz(vi.name));
                        o.put("pic", nz(vi.pic));
                        o.put("note", nz(vi.playNote));
                        o.put("flag", nz(vi.playFlag));
                        o.put("ep", vi.getplayIndex());
                        o.put("key", nz(vi.sourceKey));
                        o.put("year", vi.year);
                        o.put("area", nz(vi.area));
                        o.put("type", nz(vi.type));
                        o.put("updateTime", vi.last == null ? 0 : 1);
                        arr.put(o);
                    }
                }
                JSONObject out = new JSONObject();
                out.put("ok", 1);
                out.put("list", arr);
                cb(cbId, out.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 收藏列表 */
    @JavascriptInterface
    public void favList(String cbId) {
        pool.execute(() -> {
            try {
                List<VodCollect> all = RoomDataManger.getAllVodCollect();
                JSONArray arr = new JSONArray();
                if (all != null) {
                    for (VodCollect c : all) {
                        if (c == null) continue;
                        JSONObject o = new JSONObject();
                        o.put("id", nz(c.vodId));
                        o.put("name", nz(c.name));
                        o.put("pic", nz(c.pic));
                        o.put("key", nz(c.sourceKey));
                        arr.put(o);
                    }
                }
                JSONObject out = new JSONObject();
                out.put("ok", 1);
                out.put("list", arr);
                cb(cbId, out.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 收藏 / 取消收藏，返回 {ok:1, fav:true/false} */
    @JavascriptInterface
    public void favToggle(String sourceKey, String vodId, String name, String pic, String cbId) {
        pool.execute(() -> {
            try {
                boolean nowFav;
                if (RoomDataManger.isVodCollect(sourceKey, vodId)) {
                    VodInfo tmp = new VodInfo();
                    tmp.id = vodId;
                    tmp.sourceKey = sourceKey;
                    RoomDataManger.deleteVodCollect(sourceKey, tmp);
                    nowFav = false;
                } else {
                    VodInfo tmp = new VodInfo();
                    tmp.id = vodId;
                    tmp.name = name;
                    tmp.pic = pic;
                    tmp.sourceKey = sourceKey;
                    RoomDataManger.insertVodCollect(sourceKey, tmp);
                    nowFav = true;
                }
                JSONObject out = new JSONObject();
                out.put("ok", 1);
                out.put("fav", nowFav);
                cb(cbId, out.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    @JavascriptInterface
    public void isFav(String sourceKey, String vodId, String cbId) {
        pool.execute(() -> {
            try {
                JSONObject out = new JSONObject();
                out.put("ok", 1);
                out.put("fav", RoomDataManger.isVodCollect(sourceKey, vodId));
                cb(cbId, out.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /**
     * 保存接口地址并重新加载（成功后记入「已保存的接口」列表）。
     * name 是用户在表单里自己起的接口名，留空就自动取「接口全名」
     * （站点真名，取不到则用完整地址），保证每条都能认出来。
     */
    @JavascriptInterface
    public void setConfig(String url, String name, String cbId) {
        doSetConfig(url, cbId, true, name);
    }

    /** 在已保存的接口里手动切换：只是切过去重载，行为跟 setConfig 一致（不动已有的接口名） */
    @JavascriptInterface
    public void switchConfig(String url, String cbId) {
        doSetConfig(url, cbId, false, "");
    }

    /**
     * 接口体检：打一次接口，既测线路（耗时/可达）也查内容（配置里有多少站、
     * 组件能不能下、到底有没有能看的片）。
     *
     * <p>「ok」是本次调用是否成功，不代表接口能用；能不能用看 {@code alive}
     * （= SourceSpeedTest 的 level 是否达到「有内容」）。两者分开是因为前端要能区分
     * 「测了但源是死的」和「根本没测成」。
     */
    @JavascriptInterface
    public void speedTestApi(String url, String cbId) {
        pool.execute(() -> {
            try {
                String u = url == null ? "" : url.trim();
                JSONObject r = SourceSpeedTest.check(u);
                rememberSpeed(u, r);
                JSONObject out = new JSONObject();
                out.put("ok", 1);
                out.put("alive", r.optInt("ok", 0));
                out.put("reach", r.optInt("reach", 0));
                out.put("ms", r.optLong("ms", 0));
                out.put("level", r.optInt("level", 0));
                out.put("msg", r.optString("msg", ""));
                out.put("sites", r.optInt("sites", 0));
                out.put("httpSites", r.optInt("httpSites", 0));
                out.put("jarSites", r.optInt("jarSites", 0));
                out.put("jsSites", r.optInt("jsSites", 0));
                out.put("lives", r.optInt("lives", 0));
                out.put("jarOk", r.optInt("jarOk", 0));
                out.put("jarSize", r.optLong("jarSize", 0));
                out.put("ua", r.optInt("ua", 0));
                cb(cbId, out.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 已保存的接口列表 + 当前用的是哪一个 */
    @JavascriptInterface
    public void cfgList(String cbId) {
        pool.execute(() -> {
            try {
                String saved = Hawk.get(HawkConfig.API_URL, "");
                // 「使用中」不能只看 Hawk：全新安装 / 迁移清理过之后 Hawk 是空的，
                // 实际在跑的是 strings.xml 的内置源。这里用实际生效地址兜底，
                // 列表里至少有一条「内置 · 使用中」，用户才知道片源没丢。
                String cur = saved;
                boolean curBuiltin = TextUtils.isEmpty(cur);
                if (curBuiltin) {
                    cur = ApiConfig.currentApiUrl();
                    if (cur == null) cur = "";
                }
                JSONArray arr = apiList();
                JSONArray list = new JSONArray();
                boolean hasCur = false;
                // 老数据里常常几条同名（以前名字退化成主机名，同一个域名下
                // 挂多个接口就全一样了），列表上根本分不出谁是谁。
                // 这里显示时统一去重：重名的补「（2）」「（3）」。
                Set<String> usedNames = new LinkedHashSet<>();
                JSONObject speeds = speedMap();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String u = o.optString("url", "");
                    if (TextUtils.isEmpty(u)) continue;
                    boolean isCur = u.equals(cur);
                    if (isCur) hasCur = true;
                    String nm = uniqueName(displayName(u, o.optString("name", "")), usedNames);
                    JSONObject item = cfgItem(u, nm).put("cur", isCur ? 1 : 0);
                    attachSpeed(item, speeds, u);
                    list.put(item);
                }
                // 老用户：当前接口是在这个功能之前配的，列表里还没有它，补在第一条。
                // 没手工存过地址（curBuiltin）时补的是内置源，打上 builtin 标记，
                // 前端据此不给「改名/删除」——内置源跟着包里走，改了也没处存。
                if (!hasCur && !TextUtils.isEmpty(cur)) {
                    JSONArray head = new JSONArray();
                    JSONObject first = cfgItem(cur, uniqueName(displayName(cur, ""), usedNames));
                    first.put("cur", 1);
                    attachSpeed(first, speeds, cur);
                    if (curBuiltin) first.put("builtin", 1);
                    head.put(first);
                    for (int i = 0; i < list.length(); i++) head.put(list.get(i));
                    list = head;
                }
                JSONObject out = new JSONObject();
                out.put("ok", 1);
                out.put("cur", cur);
                out.put("list", list);
                cb(cbId, out.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 从列表里删掉一个接口（不影响正在使用的那个） */
    @JavascriptInterface
    public void delConfig(String url, String cbId) {
        pool.execute(() -> {
            try {
                String u = url == null ? "" : url.trim();
                JSONArray arr = apiList();
                JSONArray out = new JSONArray();
                boolean removed = false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String x = o.optString("url", "");
                    if (!TextUtils.isEmpty(u) && u.equals(x)) {
                        removed = true;
                        continue;
                    }
                    out.put(o);
                }
                Hawk.put(HawkConfig.PHONE_API_LIST, out.toString());
                JSONObject r = new JSONObject();
                r.put("ok", 1);
                r.put("removed", removed ? 1 : 0);
                r.put("cur", Hawk.get(HawkConfig.API_URL, ""));
                cb(cbId, r.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 给已保存的接口改名（列表里的「改名」）。name 留空 = 恢复自动生成的名字 */
    @JavascriptInterface
    public void renameConfig(String url, String name, String cbId) {
        pool.execute(() -> {
            try {
                String u = url == null ? "" : url.trim();
                if (TextUtils.isEmpty(u)) {
                    cbErrMsg(cbId, "接口地址为空");
                    return;
                }
                JSONArray arr = apiList();
                // 改名后不能跟别人撞，撞了自动加序号
                Set<String> used = new LinkedHashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String x = o.optString("url", "");
                    if (TextUtils.isEmpty(x) || x.equals(u)) continue;
                    used.add(displayName(x, o.optString("name", "")));
                }
                String nm = clip(name);
                // 留空 = 恢复成自动生成的「接口全名」
                String finalName = uniqueName(
                        TextUtils.isEmpty(nm) ? clip(fallbackName(u)) : nm, used);

                JSONArray out = new JSONArray();
                boolean hit = false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String x = o.optString("url", "");
                    if (u.equals(x)) {
                        hit = true;
                        out.put(cfgItem(u, finalName));
                    } else {
                        out.put(o);
                    }
                }
                if (!hit) {
                    cbErrMsg(cbId, "列表里没有这个接口");
                    return;
                }
                Hawk.put(HawkConfig.PHONE_API_LIST, out.toString());
                // 回读确认，别让前端显示一个没存住的名字
                String back = storedName(u);
                JSONObject r = new JSONObject();
                boolean ok = !TextUtils.isEmpty(back);
                r.put("ok", ok ? 1 : 0);
                r.put("name", ok ? back : finalName);
                if (!ok) r.put("msg", "没能写入，请重试");
                cb(cbId, r.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    private void doSetConfig(String url, final String cbId, final boolean remember, final String wantedName) {
        pool.execute(() -> {
            // 当前地址先记下来。写 API_URL 只是为了下面 loadConfig 能读到它，
            // 一旦加载失败（含同步抛异常）必须还原 —— 否则一次粘贴错误就把「当前接口」
            // 写成死地址，下次启动直接掉进配置页，用户会以为 App 坏了。
            final String prevUrl = Hawk.get(HawkConfig.API_URL, "");
            try {
                String u = normalizeUrl(url);
                if (TextUtils.isEmpty(u)) {
                    cbErrMsg(cbId, "接口地址不能为空");
                    return;
                }
                Hawk.put(HawkConfig.API_URL, u);
                // 换接口要把旧接口留下的一切状态清干净：分类对象、详情缓存、
                // 排队中的分类请求、jar 就绪标记。少清一样就会「新接口里冒出旧接口的片」。
                resetSourceCaches("已切换接口");
                ApiConfig.get().loadConfig(false, new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void retry() {
                        // ApiConfig 目前不会走这个回调（只在内存里保留着接口声明），
                        // 留着是为了以后它真调起来时行为正确：一样要还原地址。
                        restoreApiUrl(prevUrl);
                        cbErrMsg(cbId, "加载失败，已还原为上一个接口\n" + u);
                    }

                    @Override
                    public void success() {
                        // 配置已经生效，但 spider jar 还要单独下载，下完再回前端
                        try {
                            pool.execute(() -> {
                                try {
                                    ensureJarBlocking(20000L);
                                } catch (Throwable ignored) {
                                }
                                // 只有真能加载的接口才进「已保存」列表。
                                // 名字优先用用户在表单里填的；没填就沿用这条接口
                                // 原来的名字（切换时不能把自己的命名冲掉），
                                // 再没有才用「接口全名」自动生成。
                                // 写列表失败不能装作成功：前端要靠 saved 字段
                                // 判断是否提示「接口可用但没记住」
                                String nm = resolveName(u, wantedName);
                                // 返回真正落盘的名字（重名时会被加序号），
                                // 空 = 没写进去
                                String stored = rememberApi(u, nm);
                                boolean saved = stored != null;
                                if (saved) nm = stored;
                                JSONObject o = new JSONObject();
                                try {
                                    o.put("ok", 1);
                                    o.put("url", u);
                                    o.put("name", nm);
                                    o.put("source", sourceName());
                                    o.put("remembered", remember ? 1 : 0);
                                    o.put("saved", saved ? 1 : 0);
                                } catch (Throwable ignored) {
                                }
                                cb(cbId, o.toString());
                            });
                        } catch (Throwable th) {
                            // 池已 shutdown（Activity 已经销毁），别让前端一直转圈
                            cbErrMsg(cbId, "页面已关闭，请重新打开");
                        }
                    }

                    @Override
                    public void error(String msg) {
                        String m = TextUtils.isEmpty(msg) ? "加载失败" : msg;
                        // 最常见的一种填错：把 XML 源的 api.php 地址直接当接口填了。
                        // 这里必须说清楚，否则用户只会看到「解析配置失败」，
                        // 完全不知道自己填错了什么。
                        if (m.contains("解析配置失败")) {
                            m = "这个地址返回的不是接口配置（JSON）。"
                                    + "TVBox 的 XML 源（api.php/provide/vod 这类）不能直接填在这里，"
                                    + "要填配置 JSON 的地址。";
                        }
                        restoreApiUrl(prevUrl);
                        cbErrMsg(cbId, m + "  " + u + "\n已还原为上一个接口，原来的片源还能继续用。");
                    }
                }, act);
            } catch (Throwable th) {
                // loadConfig 同步抛异常（比如 Activity 已销毁）也要把地址还原
                restoreApiUrl(prevUrl);
                cbErr(cbId, th);
            }
        });
    }

    /**
     * 加载失败时把「当前接口」还原成调用前的地址。
     * 只还原落盘值：ApiConfig 内存里的配置在失败路径上没有被替换，
     * 还原之后当前进程和下次启动看到的都是原来那个能用的接口。
     */
    private static void restoreApiUrl(String oldUrl) {
        try {
            Hawk.put(HawkConfig.API_URL, oldUrl == null ? "" : oldUrl);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 已保存接口列表的读写 ---------------- */

    private static JSONArray apiList() {
        try {
            String s = Hawk.get(HawkConfig.PHONE_API_LIST, "[]");
            if (TextUtils.isEmpty(s)) return new JSONArray();
            return new JSONArray(s);
        } catch (Throwable th) {
            return new JSONArray();
        }
    }

    /**
     * 最近用过的排最前，同一个地址只留一条；最多存 12 个。
     * 返回真正落盘的名字，空（null）表示没写进去 —— 前端要据此提示
     * 「接口能用但没记住」，不能默默失败让用户以为加过了。
     */
    private static String rememberApi(String url, String name) {
        try {
            if (TextUtils.isEmpty(url)) return null;
            JSONArray old = apiList();
            JSONArray out = new JSONArray();
            // 别的条目的名字先占位，新条目重名时自动加序号，
            // 否则列表上又是几条一模一样的
            Set<String> used = new LinkedHashSet<>();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("url", "");
                if (TextUtils.isEmpty(u) || u.equals(url)) continue;
                used.add(displayName(u, o.optString("name", "")));
            }
            String finalName = uniqueName(displayName(url, name), used);
            out.put(cfgItem(url, finalName));
            for (int i = 0; i < old.length() && out.length() < 12; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("url", "");
                if (TextUtils.isEmpty(u) || u.equals(url)) continue;
                out.put(cfgItem(u, o.optString("name", "")));
            }
            Hawk.put(HawkConfig.PHONE_API_LIST, out.toString());
            // 回读确认：Hawk(SharedPreferences) 是同步写，读不到就是真失败
            String back = storedName(url);
            return TextUtils.isEmpty(back) ? null : back;
        } catch (Throwable th) {
            return null;
        }
    }

    private static JSONObject cfgItem(String url, String name) throws Exception {
        JSONObject it = new JSONObject();
        it.put("url", url);
        it.put("name", clip(displayName(url, name)));
        return it;
    }

    /** 已测过的速度：{url: {ms, ok, reach, t, msg}}，只留最近一次 */
    private static JSONObject speedMap() {
        try {
            String raw = Hawk.get(HawkConfig.PHONE_API_SPEED, "{}");
            if (TextUtils.isEmpty(raw)) return new JSONObject();
            return new JSONObject(raw);
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    private static void rememberSpeed(String url, JSONObject r) {
        if (TextUtils.isEmpty(url) || r == null) return;
        try {
            JSONObject map = speedMap();
            JSONObject o = new JSONObject();
            o.put("ms", r.optLong("ms", 0));
            o.put("ok", r.optInt("ok", 0));
            o.put("reach", r.optInt("reach", 0));
            o.put("msg", r.optString("msg", ""));
            // 体检明细：下次进列表不联网也能看出这条源「好在哪、坏在哪」
            o.put("lv", r.optInt("level", 0));
            o.put("sites", r.optInt("sites", 0));
            o.put("http", r.optInt("httpSites", 0));
            o.put("jar", r.optInt("jarSites", 0));
            o.put("js", r.optInt("jsSites", 0));
            o.put("lives", r.optInt("lives", 0));
            o.put("jarOk", r.optInt("jarOk", 0));
            o.put("jarSize", r.optLong("jarSize", 0));
            o.put("t", System.currentTimeMillis());
            map.put(url.trim(), o);
            Hawk.put(HawkConfig.PHONE_API_SPEED, map.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 把测速结果贴到列表项上，前端据此显示「128ms / 无内容 / 超时」 */
    private static void attachSpeed(JSONObject item, JSONObject speeds, String url) {
        if (item == null || speeds == null || TextUtils.isEmpty(url)) return;
        JSONObject sp = speeds.optJSONObject(url);
        if (sp == null) return;
        try {
            item.put("spd", sp.optLong("ms", 0));
            item.put("spdOk", sp.optInt("ok", 0));
            item.put("spdReach", sp.optInt("reach", 0));
            item.put("spdLv", sp.optInt("lv", sp.optInt("ok", 0) > 0 ? 3 : 0));
            item.put("spdSites", sp.optInt("sites", 0));
            item.put("spdHttp", sp.optInt("http", 0));
            item.put("spdJar", sp.optInt("jar", 0));
            item.put("spdJs", sp.optInt("js", 0));
            item.put("spdLives", sp.optInt("lives", 0));
            item.put("spdJarOk", sp.optInt("jarOk", 0));
            item.put("spdJarSize", sp.optLong("jarSize", 0));
            item.put("spdT", sp.optLong("t", 0));
            item.put("spdMsg", sp.optString("msg", ""));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 接口名：用户填的 > 列表里已存的 > 自动生成的「接口全名」。
     * 切换接口时前端传空串，这里就保住用户之前起的名字，不会被站点名冲掉。
     */
    private static String resolveName(String url, String wanted) {
        String w = wanted == null ? "" : wanted.trim();
        if (!TextUtils.isEmpty(w)) return clip(w);
        String old = storedName(url);
        if (!TextUtils.isEmpty(old)) return old;
        return clip(fallbackName(url));
    }

    /** 已保存条目里的名字（没存过返回空串） */
    private static String storedName(String url) {
        try {
            JSONArray arr = apiList();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (url != null && url.equals(o.optString("url", ""))) {
                    return o.optString("name", "");
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 「接口全名」：站点真名最好认，取不到就用完整地址。
     * 以前这里退的是主机名 —— 同一个域名下挂多个接口时
     * 几条名字完全一样，列表上根本分不出谁是谁。
     */
    private static String fallbackName(String url) {
        String sn = sourceName();
        if (!TextUtils.isEmpty(sn)) return sn;
        return url == null ? "" : url.trim();
    }

    /** 展示用名字：存过的优先，空的现算一个 */
    private static String displayName(String url, String stored) {
        return TextUtils.isEmpty(stored) ? fallbackName(url) : stored;
    }

    /** 重名补序号，保证列表上每条都能区分 */
    private static String uniqueName(String base, Set<String> used) {
        String b = TextUtils.isEmpty(base) ? "接口" : base;
        if (!used.contains(b)) {
            used.add(b);
            return b;
        }
        for (int i = 2; i < 100; i++) {
            String c = b + "（" + i + "）";
            if (!used.contains(c)) {
                used.add(c);
                return c;
            }
        }
        used.add(b);
        return b;
    }

    /** 名字太长会把列表行撑爆（用地址当名字时尤其长），截一下 */
    private static String clip(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 60 ? t.substring(0, 58) + "…" : t;
    }

    /** 当前片源的站点名，取不到就回退空串 */
    private static String sourceName() {
        try {
            SourceBean home = ApiConfig.get().getHomeSourceBean();
            return home != null && home.getName() != null ? home.getName() : "";
        } catch (Throwable th) {
            return "";
        }
    }

    /** 从接口地址里取主机名，只为了列表上能认出来 */
    private static String hostOf(String url) {
        String s = url == null ? "" : url;
        int p = s.indexOf("://");
        if (p > 0) s = s.substring(p + 3);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == ':') return s.substring(0, i);
        }
        return s;
    }

    /**
     * 用户手输 / 粘贴的接口地址经常带空格、引号、缺协议头，
     * 中文域名（如「肥猫.net」）还必须先转成 punycode，否则 OkHttp 直接 UnknownHost。
     */
    private static String normalizeUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim()
                .replace("\u200B", "").replace("\uFEFF", "")
                .replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "");
        s = s.replace("\"", "").replace("'", "")
                .replace("\u201C", "").replace("\u201D", "")
                .replace("\u2018", "").replace("\u2019", "");
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://") && !s.startsWith("clan")) {
            s = "http://" + s;
        }
        // 域名转 punycode 统一交给 ApiConfig.toAsciiUrl：
        // 它会把端口剥出来单独处理，这里自己转的话 "肥猫.net:8080" 会整串送进 IDN 失败
        return ApiConfig.toAsciiUrl(s);
    }

    /** 当前接口地址（回显到设置页） */
    @JavascriptInterface
    public void currentConfig(String cbId) {
        pool.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("ok", 1);
                o.put("url", Hawk.get(HawkConfig.API_URL, ""));
                SourceBean home = ApiConfig.get().getHomeSourceBean();
                o.put("source", home != null && home.getName() != null ? home.getName() : "");
                o.put("home", home != null && home.getApi() != null ? home.getApi() : "");
                cb(cbId, o.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 读剪贴板：接口地址通常是一长串，手动输入很痛苦 */
    @JavascriptInterface
    public void readClipboard(String cbId) {
        main.post(() -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) act.getSystemService(Activity.CLIPBOARD_SERVICE);
                String text = "";
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                        && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(act);
                    text = cs == null ? "" : cs.toString();
                }
                JSONObject o = new JSONObject();
                o.put("ok", 1);
                o.put("text", text == null ? "" : text.trim());
                cb(cbId, o.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /**
     * 复制文本到剪贴板。首页加载失败时空态里有个「复制错误详情」，
     * 用户贴回来就能直接看出是超时、拦截页还是 md5 不符。
     */
    @JavascriptInterface
    public void copyText(String text, String cbId) {
        main.post(() -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) act.getSystemService(Activity.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("pigtv", text == null ? "" : text));
                }
                JSONObject o = new JSONObject();
                o.put("ok", 1);
                cb(cbId, o.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 电视直播：直接交给原生的 LivePlayActivity，它自己处理"接口没有直播源"的情况 */
    @JavascriptInterface
    public void openLive() {
        main.post(() -> {
            try {
                act.startActivity(new Intent(act, com.github.tvbox.osc.ui.activity.LivePlayActivity.class));
            } catch (Throwable th) {
                toast("当前接口没有直播源");
            }
        });
    }

    /** 网盘登录：打开原生页面（内置 WebView 登录后自动取凭据） */
    @JavascriptInterface
    public void openNetdisk() {
        main.post(() -> {
            try {
                act.startActivity(new Intent(act, NetdiskLoginActivity.class));
            } catch (Throwable th) {
                toast("打不开网盘登录页");
            }
        });
    }

    /** 网盘登录状态：给「我的」页徽章用 */
    @JavascriptInterface
    public void netdiskState(String cbId) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", 1);
            o.put("count", NetdiskStore.count(act));
            o.put("quark", TextUtils.isEmpty(NetdiskStore.get(act, NetdiskStore.QUARK)) ? 0 : 1);
            o.put("uc", TextUtils.isEmpty(NetdiskStore.get(act, NetdiskStore.UC)) ? 0 : 1);
            o.put("ali", TextUtils.isEmpty(NetdiskStore.get(act, NetdiskStore.ALI)) ? 0 : 1);
            cb(cbId, o.toString());
        } catch (Throwable th) {
            cbErr(cbId, th);
        }
    }

    /**
     * 直播页：把接口自带的直播分组 + 频道一次性给前端。
     * 数据量很小（几十到几百条），一次全给比翻页简单，前端也能自己算「共 N 个频道」。
     * <p>
     * 注意 index 的含义：这里给的 index 是<b>列表下标</b>，不是 bean 里的
     * channelIndex / groupIndex。原生 LivePlayActivity 用的是
     * getLiveChannels(groupIndex).get(channelIndex)，取的就是下标，
     * 所以前端拿着这个下标回调 openLiveAt 才不会错位。
     */
    @JavascriptInterface
    public void liveGroups(String cbId) {
        pool.execute(() -> {
            try {
                JSONArray groups = new JSONArray();
                boolean hasProxy = false;
                List<LiveChannelGroup> gl = null;
                try {
                    gl = ApiConfig.get().getChannelGroupList();
                    // 配置接口还没加载出来（首次启动没网、接口挂了、用户还没填源）时，
                    // 用打包在 assets 里的直播源兜底：它不依赖任何外网地址，
                    // 让直播页至少有几组台可以点，而不是一片空白。
                    if ((gl == null || gl.isEmpty()) && ApiConfig.get().loadBuiltinLives()) {
                        gl = ApiConfig.get().getChannelGroupList();
                    }
                } catch (Throwable ignored) {
                }
                if (gl != null) {
                    for (int gi = 0; gi < gl.size(); gi++) {
                        LiveChannelGroup g = gl.get(gi);
                        if (g == null) continue;
                        String gname = nz(g.getGroupName());
                        // 用户自己在设置里填了直播源时，ApiConfig 只往列表里塞一个占位组：
                        //   groupName      = http://127.0.0.1:9978/proxy?do=live&type=txt&ext=<base64>
                        //   liveChannelItems = null
                        // 真正的频道要 LivePlayActivity 去拉那个 URL 才有，这里给不出来。
                        // 既不能把它当普通分组（前端会看到 1 个分组 0 个频道 → 误报"没有直播源"），
                        // 更不能把这条 200 字的长 URL 当分组名扔给界面。
                        if (isProxyLiveGroup(gname)) {
                            hasProxy = true;
                            continue;
                        }
                        JSONArray chs = new JSONArray();
                        ArrayList<LiveChannelItem> items = g.getLiveChannels();
                        if (items != null) {
                            for (int ci = 0; ci < items.size(); ci++) {
                                LiveChannelItem it = items.get(ci);
                                if (it == null) continue;
                                JSONObject c = new JSONObject();
                                c.put("index", ci);
                                c.put("num", it.getChannelNum());
                                c.put("name", nz(it.getChannelName()));
                                c.put("srcs", it.getChannelUrls() == null ? 0 : it.getChannelUrls().size());
                                chs.put(c);
                            }
                        }
                        JSONObject o = new JSONObject();
                        // index 必须是它在 liveChannelGroupList 里的**原始下标**：
                        // openLiveAt 会把它原样塞进 intent，原生按 get(groupIndex) 取。
                        // 上面跳过 proxy 占位组时下标会留空，所以这里绝不能重新编号。
                        o.put("index", gi);
                        o.put("name", gname);
                        o.put("locked", !nz(g.getGroupPassword()).isEmpty());
                        o.put("channels", chs);
                        groups.put(o);
                    }
                }
                JSONObject out = new JSONObject();
                out.put("ok", 1);
                out.put("groups", groups);
                // 配了自定义直播源：前端空态要说"已设置，去播放器里看"，而不是"还没配"
                out.put("proxy", hasProxy);
                cb(cbId, out.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /**
     * 判断是不是「用户自定义直播源」留下的占位分组。
     * 见 ApiConfig#parseJson 里 liveURL_final 那段：它把真实地址 base64 后拼成
     * http://127.0.0.1:9978/proxy?do=live&type=txt&ext=xxx 只放一个空壳组。
     */
    private static boolean isProxyLiveGroup(String groupName) {
        return groupName.startsWith("http://127.0.0.1") && groupName.contains("/proxy?do=live");
    }

    /**
     * 点某个频道直接播：进 LivePlayActivity 并定位到第 groupIndex 组第 channelIndex 个。
     * LivePlayActivity.initLiveState() 本来就在读 intent 里的 groupIndex/channelIndex
     * （没带才回落到「上次看的那个频道」），所以这里只需要塞两个 extra，不用改原生逻辑。
     * <p>
     * 参数用 String 而不是 int：这个类里所有桥方法都是 String 进、内部再转，
     * JS 的 number 过 WebView 桥时类型匹配容易出岔子，跟着现有约定走最稳。
     */
    @JavascriptInterface
    public void openLiveAt(String groupIndex, String channelIndex) {
        final int gi;
        final int ci;
        try {
            gi = Integer.parseInt(String.valueOf(groupIndex).trim());
            ci = Integer.parseInt(String.valueOf(channelIndex).trim());
        } catch (Throwable th) {
            toast("频道参数无效");
            return;
        }
        main.post(() -> {
            try {
                Intent it = new Intent(act, com.github.tvbox.osc.ui.activity.LivePlayActivity.class);
                // 这里必须再校验一次下标：前端拿到列表和用户真正点下去之间隔了多久不知道，
                // 中间如果换了接口或改了直播源，同一组下标指向的可能已经是别的频道、或者根本不存在。
                // 而原生侧 selectChannelGroup() 是 liveChannelGroupList.get(groupIndex) 裸取
                // （见 LivePlayActivity:1396 的 isNeedInputPassword），越界就是 Activity 直接崩。
                // 校验不过就**不塞 extra**，退化成和「我的→电视直播」一样从上次看的频道起播。
                if (inLiveRange(gi, ci)) {
                    it.putExtra("groupIndex", gi);
                    it.putExtra("channelIndex", ci);
                }
                act.startActivity(it);
            } catch (Throwable th) {
                toast("当前接口没有直播源");
            }
        });
    }

    /**
     * groupIndex / channelIndex 是否还落在当前直播列表的有效范围内。
     * 只做越界判断，不判断加密分组 —— 加密组的密码弹窗交给原生自己处理。
     */
    private static boolean inLiveRange(int gi, int ci) {
        try {
            List<LiveChannelGroup> gl = ApiConfig.get().getChannelGroupList();
            if (gl == null || gi < 0 || gi >= gl.size()) return false;
            LiveChannelGroup g = gl.get(gi);
            if (g == null) return false;
            ArrayList<LiveChannelItem> items = g.getLiveChannels();
            return items != null && ci >= 0 && ci < items.size();
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 可切换的片源站点。对应 TV 版首页点「源名」弹出的那个列表
     * （HomeActivity.showSiteSwitch），只是把弹窗搬到了 Web 端。
     */
    @JavascriptInterface
    public void sites(String cbId) {
        pool.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                SourceBean cur = ApiConfig.get().getHomeSourceBean();
                String curKey = cur == null ? "" : nz(cur.getKey());
                List<SourceBean> all = ApiConfig.get().getSourceBeanList();
                if (all != null) {
                    for (SourceBean sb : all) {
                        if (sb == null || sb.getHide() == 1) continue;
                        if (TextUtils.isEmpty(sb.getName())) continue;
                        JSONObject o = new JSONObject();
                        o.put("key", nz(sb.getKey()));
                        o.put("name", nz(sb.getName()));
                        o.put("cur", nz(sb.getKey()).equals(curKey) ? 1 : 0);
                        arr.put(o);
                    }
                }
                JSONObject out = new JSONObject();
                out.put("ok", 1);
                out.put("list", arr);
                cb(cbId, out.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    // =====================================================================
    //  站点体检（「切换片源站点」弹层用）
    // =====================================================================

    /**
     * 每个站点这次到底能不能取到内容。对应 TV 版的 SiteProbe。
     *
     * <p>站点（{@link SourceBean}）没有自己的地址，只有一个 {@code csp_xxx} 类名，
     * 内容全靠 spider 现算，所以没法像接口那样量 HTTP 耗时 —— 只能真问它要一次首页，
     * 首页空就退一步问第一个分类，拿回来的条数 &gt; 0 才算真的能看。
     *
     * <p>结果按单个站点流式推给页面（{@code __evt('site', …)}），页面边测边出徽章，
     * 全部测完前端自己决定隐藏哪些 —— 前端才知道当前滚动位置和「正在使用」是哪条。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, JSONObject> SITE_PROBE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> SITE_PROBE_AT =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * 并发 6：spider 内部共用 okhttp 连接池，开太大反而把弱网站全拖成超时；
     * 但 4 个又太少——48 个站排在 4 个线程后面，弱网站一卡就是半分钟，
     * 后面的站全程「检测中」，看起来像卡死。
     */
    private static final ExecutorService SITE_PROBE_POOL = Executors.newFixedThreadPool(6);
    /** 定时收尾用：到点掐断那些还在半空的 spider 请求 */
    private static final java.util.concurrent.ScheduledExecutorService SITE_TIMER =
            Executors.newSingleThreadScheduledExecutor();
    /** 结果留 30 分钟：5 分钟太短，看完一轮翻两页就全过期，等于每次进来都重测 */
    private static final long SITE_FRESH_MS = 60 * 60 * 1000L;
    /**
     * 「无片 / 失败」只留 10 分钟。
     * 这两类最容易误判（站临时抽风、spider 第一次 init 拉配置失败、弱网超时），
     * 留太久等于把一个能用的站一直按在最底下。
     */
    private static final long SITE_FRESH_BAD_MS = 10 * 60 * 1000L;
    /** 总预算：到点还没轮到的站推「未测」，前端不清空也不当无内容藏掉。
     *  50 秒实测能跑完 45/48，剩下三个多半是死站，没必要让整张表陪它们等。 */
    private static final long SITE_BUDGET_MS = 60_000L;
    /** 单站硬上限：一个站卡住不能拖着整轮不结束（spider 调用无法中断，只能不认它的结果） */
    private static final long SITE_ONE_MS = 20_000L;
    /**
     * 代次：每点一次「检测」就 +1，上一轮还在排队的任务看到代数变了立刻退出。
     * 没有这个，用户关掉弹层再打开，新任务会排在旧任务后面，屏幕上就一直「检测中」。
     */
    private static final java.util.concurrent.atomic.AtomicInteger SITE_GEN =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 前端自己发起过体检 → 预热没有意义了，而且再跑会把前台那轮顶掉 */
    private static volatile boolean SITE_FG_USED = false;

    /**
     * @param seq   前端给的代次，原样带回，页面关掉重开后旧一轮的迟到结果自己丢弃
     * @param force "1" = 忽略缓存全部重测（点「重新检测」）
     */
    @JavascriptInterface
    public void probeSites(String seq, String force, String cbId) {
        // 用户已经自己点开过站点列表：之后预热一律不再启动。
        // 预热也走 doProbe 且会 +1 SITE_GEN，落在前台那一轮后面启动的话，
        // 会把前台这轮整批顶掉 —— 表现就是「打开列表一直检测中，永远不出结果」。
        SITE_FG_USED = true;
        doProbe(spInt(seq), "1".equals(force == null ? "" : force.trim()), cbId, false);
    }

    /**
     * 开机预热：App 一起来就在后台把全部站点测一遍存进缓存，
     * 用户点「切换片源站点」时直接整批命中、秒出结果。
     *
     * <p>和 {@link #probeSites} 走同一套探测逻辑，区别只是不往页面推事件 ——
     * 这时候弹层通常还没打开，推了也没人接。
     */
    @JavascriptInterface
    public void warmSites(String cbId) {
        warmTry(cbId, 0);
    }

    /** 配置/站点列表还没就绪就测，等于拿一张空表测，白跑一轮 —— 所以先等一会儿再试 */
    private void warmTry(final String cbId, final int attempt) {
        if (attempt > 4) return;
        SITE_TIMER.schedule(() -> {
            // 用户已经点开过列表（前台那轮正在跑）→ 预热直接放弃，别去顶人家的代次
            if (SITE_FG_USED) return;
            List<SourceBean> raw = null;
            try {
                raw = ApiConfig.get().getSourceBeanList();
            } catch (Throwable ignored) {
            }
            if (raw == null || raw.isEmpty()) {
                warmTry(cbId, attempt + 1);
                return;
            }
            doProbe(-1, false, cbId, true);
        }, attempt == 0 ? 4_000L : 8_000L, TimeUnit.MILLISECONDS);
    }

    /**
     * 用户切到这个站并且首页真的出片了 —— 直接把它在缓存里改判成「可用」。
     * 体检再怎么小心也会误判（站临时抽风、需要先登录、超时），
     * 用户实际点进去能用才是硬证据，下次排序不能再把它按在底下。
     */
    @JavascriptInterface
    public void siteOk(String key, String cbId) {
        String k = nz(key);
        if (k.isEmpty()) {
            cb(cbId, "{\"ok\":0}");
            return;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("st", 2);
            o.put("n", 0);
            o.put("msg", "可用");
            o.put("ms", 0);
        } catch (Throwable ignored) {
        }
        SITE_PROBE.put(k, o);
        SITE_PROBE_AT.put(k, System.currentTimeMillis());
        cb(cbId, "{\"ok\":1}");
    }

    private static boolean allCached(List<SourceBean> all) {
        for (SourceBean sb : all) {
            if (freshProbe(sb.getKey()) == null) return false;
        }
        return true;
    }

    /**
     * @param silent true = 预热，只写缓存、不往页面推事件
     */
    private void doProbe(final int mySeq, final boolean forced, final String cbId, final boolean silent) {
        // 新的一轮开始：上一轮还排在池子里的任务看到代数变了会立刻退出，
        // 否则关掉弹层再打开时，新任务要排在旧任务后面，屏幕一直停在「检测中」。
        final int myGen = SITE_GEN.incrementAndGet();
        bgPool.execute(() -> {
            try {
                final List<SourceBean> all = new ArrayList<>();
                List<SourceBean> raw = ApiConfig.get().getSourceBeanList();
                if (raw != null) {
                    for (SourceBean sb : raw) {
                        if (sb == null || sb.getHide() == 1) continue;
                        if (TextUtils.isEmpty(sb.getName())) continue;
                        all.add(sb);
                    }
                }

                // 组件没备好就开测，type=3 的站会全被判「组件里没有这个站」——
                // 那不是站坏了，是我们还没把 jar 下回来，结论会误导人。
                boolean needJar = false;
                for (SourceBean sb : all) {
                    if (needsJar(sb)) {
                        needJar = true;
                        break;
                    }
                }
                if (needJar && !jarReady) ensureJarBlocking(JAR_WAIT_MS);
                if (myGen != SITE_GEN.get()) return;

                // 全部命中缓存就整批一次推完：一个个推的话，用户要盯着 48 行「检测中」
                // 慢慢变绿，明明数据早就在内存里了。
                if (!forced && silent) {
                    // 预热：缓存齐了就没别的事了（不发事件、不回包）
                    if (allCached(all)) return;
                } else if (!forced && emitCached(mySeq, all)) {
                    cb(cbId, probeDone(all.size(), all.size()).toString());
                    return;
                }

                final long deadline = System.currentTimeMillis() + SITE_BUDGET_MS;
                final CountDownLatch latch = new CountDownLatch(all.size());
                // 已推过的 key：跑完用它找出「压根没轮上」的站，补一条「未测」，
                // 不然那些行会永远停在「检测中」。
                final java.util.Set<String> sent = java.util.Collections.newSetFromMap(
                        new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
                final java.util.concurrent.atomic.AtomicBoolean closed =
                        new java.util.concurrent.atomic.AtomicBoolean(false);

                // 收尾（只会真正执行一次）：把没轮上的站标「未测」再回包。
                // 不能等 latch —— 只要有一个站卡在 spider 里，整轮就得跟着干等，
                // 屏幕上一直是「检测中 45/48」。
                final Runnable finish = () -> {
                    if (!closed.compareAndSet(false, true)) return;
                    if (myGen != SITE_GEN.get()) return;
                    if (!silent) {
                        JSONObject un = spUntested();
                        for (SourceBean sb : all) {
                            if (sent.add(nz(sb.getKey()))) {
                                emit("site", spSiteEvt(mySeq, sb, un).toString());
                            }
                        }
                    }
                    if (!silent) cb(cbId, probeDone(all.size(), sent.size()).toString());
                };
                // 看门狗：到点直接收尾，别管那几个还在半空中的请求
                SITE_TIMER.schedule(finish, SITE_BUDGET_MS + 2_000L, TimeUnit.MILLISECONDS);

                final java.util.concurrent.atomic.AtomicInteger left =
                        new java.util.concurrent.atomic.AtomicInteger(all.size());
                for (final SourceBean sb : all) {
                    SITE_PROBE_POOL.execute(() -> {
                        try {
                            if (myGen != SITE_GEN.get()) return;
                            JSONObject r = forced ? null : freshProbe(sb.getKey());
                            if (r == null) {
                                if (System.currentTimeMillis() > deadline) {
                                    r = spUntested();
                                } else {
                                    r = probeOneSite(sb, deadline);
                                    // 只有真正测出结论的才进缓存：「未测」不是结论
                                    if (r.optInt("st", 0) != 0) {
                                        SITE_PROBE.put(nz(sb.getKey()), r);
                                        SITE_PROBE_AT.put(nz(sb.getKey()), System.currentTimeMillis());
                                    }
                                }
                            }
                            if (myGen != SITE_GEN.get()) return;
                            sent.add(nz(sb.getKey()));
                            if (!silent) emit("site", spSiteEvt(mySeq, sb, r).toString());
                        } catch (Throwable ignored) {
                        } finally {
                            latch.countDown();
                            if (left.decrementAndGet() <= 0) finish.run();
                        }
                    });
                }
                latch.await(SITE_BUDGET_MS + SITE_ONE_MS + 5_000L, TimeUnit.MILLISECONDS);
                finish.run();
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 缓存全命中就整批推一次；有一条没缓存就返回 false，走逐站流程 */
    private boolean emitCached(int seq, List<SourceBean> all) {
        JSONArray arr = new JSONArray();
        for (SourceBean sb : all) {
            JSONObject r = freshProbe(sb.getKey());
            if (r == null) return false;
            arr.put(spSiteEvt(seq, sb, r));
        }
        JSONObject o = new JSONObject();
        try {
            o.put("seq", seq);
            o.put("list", arr);
        } catch (Throwable ignored) {
            return false;
        }
        emit("sites", o.toString());
        return true;
    }

    private static JSONObject probeDone(int total, int tested) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", 1);
            out.put("total", total);
            out.put("tested", tested);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** st=0：这轮没轮到它（超时 / 被新一轮顶掉），既不算有内容也不算没内容 */
    private static JSONObject spUntested() {
        JSONObject o = new JSONObject();
        try {
            o.put("st", 0);
            o.put("n", 0);
            o.put("msg", "未测");
            o.put("ms", 0);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * 单站体检：state 和 TV 版 SiteProbe 对齐（2 有内容 / 3 无片 / 4 失败 / 5 脚本源 / 0 未测）
     *
     * <p>数量怎么来的：首页 {@code homeContent} 的 list 只是**推荐位**，很多源只给十几条，
     * 拿它当「这个站有几部片」会差好几个数量级。所以再问一次分类接口 ——
     * spider 的 {@code categoryContent} 通常带 {@code total}（分类总数）或
     * {@code pagecount × limit}，那才是站方自己报的量。
     *
     * <p>两个都没有时宁可不报数字（前端显示「有内容」），
     * 也不拿首页推荐位的条数冒充总片量。
     *
     * @param deadline 整轮预算的截止时间，过了就别再发第二次请求
     */
    private static JSONObject probeOneSite(SourceBean sb, long deadline) {
        long t0 = System.currentTimeMillis();
        JSONObject r = probeOnce(sb, deadline);
        // 首轮就判「失败」而且失败得很快（不到 8 秒）→ 多半是 spider 第一次 init 抖动
        // （要现拉配置/校验 token），不是站真的死了。用户实测「标失败的站进去能正常播」
        // 基本都是这一类。超时（>8s）不重试 —— 那只会再等一次超时。
        if (r.optInt("st", 0) == 4 && System.currentTimeMillis() - t0 < 8_000L) {
            JSONObject again = probeOnce(sb, deadline);
            if (again.optInt("st", 0) != 4) r = again;
        }
        try {
            r.put("ms", System.currentTimeMillis() - t0);
        } catch (Throwable ignored) {
        }
        return r;
    }

    /**
     * 单站探一次。判定顺序是「能拿到内容就算活」，任何一步抛异常都不直接判死：
     * <ol>
     *   <li>首页 {@code homeContent} —— 只当线索用，抛异常不算结论（很多爬虫只实现分类，
     *       首页直接 throw，按老逻辑就会被判「失败」，但点进去照样能播）；</li>
     *   <li>分类 {@code categoryContent} —— 试前 3 个分类，任一有内容即算活。
     *       只试第一个的话，撞上「最近更新」/需登录的空分类就会被判「无片」；</li>
     *   <li>两边都调通但都没内容 → 无片（st=3）；全部抛异常 → 失败（st=4）。</li>
     * </ol>
     */
    private static JSONObject probeOnce(SourceBean sb, long deadline) {
        int st = 4;
        int n = 0;
        String msg = "";
        String api = sb == null || sb.getApi() == null ? "" : sb.getApi().trim();
        if (spIsScript(api)) {
            // quickjs 求值必 SIGSEGV，碰都不能碰；测不了就不下「没内容」的结论
            st = 5;
            msg = "脚本源";
        } else {
            Spider sp = null;
            try {
                sp = ApiConfig.get().getCSP(sb);
            } catch (Throwable ignored) {
            }
            if (sp == null || sp instanceof com.github.catvod.crawler.SpiderNull) {
                st = 4;
                msg = "组件里没有这个站";
            } else {
                String lastErr = null;

                // ---- 1) 首页：软失败 ----
                JSONObject home = null;
                try {
                    home = spObj(sp.homeContent(false));
                } catch (Throwable th) {
                    lastErr = spErrText(th);
                }
                JSONArray classes = home == null ? null : home.optJSONArray("class");
                int homeLen = spListLen(home);
                boolean anyCallOk = home != null;      // 有一次调用能正常返回，就说明站是通的

                // ---- 2) 分类：最多试 3 个，任一有内容就停 ----
                int total = 0;
                int bestLen = homeLen;
                int classCount = classes == null ? 0 : classes.length();
                int tryN = Math.min(classCount, 3);
                for (int i = 0; i < tryN; i++) {
                    if (System.currentTimeMillis() >= deadline) break;
                    String tid = spTidOf(classes.optJSONObject(i));
                    if (tid.isEmpty()) continue;
                    try {
                        JSONObject cat = spObj(sp.categoryContent(tid, "1", false,
                                new HashMap<String, String>()));
                        anyCallOk = true;
                        int t = spTotalOf(cat);
                        int len = spListLen(cat);
                        if (t > total) total = t;
                        if (len > bestLen) bestLen = len;
                        if (total > 0 || len > 0) break;   // 有结论了，别再挨个分类打
                    } catch (Throwable th) {
                        lastErr = spErrText(th);
                    }
                }

                // ---- 3) 落结论 ----
                if (total > 0) {
                    st = 2;
                    n = total;
                    msg = spCountText(total, classCount > 1);
                } else if (bestLen > 0) {
                    st = 2;
                    n = bestLen;
                    msg = "可用";                 // 量不出总数就别报假数字
                } else if (anyCallOk) {
                    st = 3;                       // 接口调通了，只是真的没内容
                    msg = "取不到内容";
                } else {
                    st = 4;                       // 一次都没调通
                    msg = lastErr == null ? "连不上" : lastErr;
                }
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("st", st);
            o.put("n", n);
            o.put("msg", msg);
            o.put("ms", 0);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static String spErrText(Throwable th) {
        String m = th == null ? null : th.getMessage();
        if (m == null || m.isEmpty()) m = th == null ? "未知错误" : th.getClass().getSimpleName();
        return m.length() > 40 ? m.substring(0, 40) : m;
    }

    /**
     * 分类接口自己报的总数：优先 total，退而求其次 pagecount × limit。
     *
     * <p>两个坑：有些源用 {@code pagecount = Integer.MAX_VALUE} 表示「无限/未知」，
     * 有些源 total 直接给 {@code 2147483647}，乘起来还会整型溢出 ——
     * 超过合理量级的一律当「没报」，宁可显示「有内容」也不甩个 2 亿部出来。
     */
    private static final long SP_COUNT_MAX = 5_000_000L;

    private static int spTotalOf(JSONObject o) {
        if (o == null) return 0;
        long total = o.optLong("total", 0);
        if (total > 0 && total <= SP_COUNT_MAX) return (int) total;
        long pc = o.optLong("pagecount", 0);
        long limit = o.optLong("limit", 0);
        if (pc <= 0 || limit <= 0) return 0;
        long v = pc * limit;                       // long 乘，不会溢出
        if (v > 0 && v <= SP_COUNT_MAX) return (int) v;
        return 0;
    }

    /** 徽章文案：只有站方自己报的量才写成数字，atLeast 表示这只是下限 */
    private static String spCountText(int n, boolean atLeast) {
        // 十部以下就别报数字了：不管是「1+」还是「1部」，看着都像在说这个站只有一部片
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

    private static JSONObject spSiteEvt(int seq, SourceBean sb, JSONObject r) {
        JSONObject o = new JSONObject();
        try {
            o.put("seq", seq);
            o.put("key", nz(sb.getKey()));
            o.put("st", r.optInt("st", 0));
            o.put("n", r.optInt("n", 0));
            o.put("msg", r.optString("msg", ""));
            o.put("ms", r.optLong("ms", 0));
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject freshProbe(String key) {
        JSONObject r = SITE_PROBE.get(nz(key));
        Long at = SITE_PROBE_AT.get(nz(key));
        if (r == null || at == null) return null;
        int st = r.optInt("st", 0);
        if (st == 0) return null;                 // 「未测」不是结论，下次一定重测
        // 好结果留久一点（1 小时），坏结果只留 10 分钟 —— 误判要能自己恢复，
        // 否则一个能用的站会被一直按在列表最底下。
        long ttl = (st == 3 || st == 4) ? SITE_FRESH_BAD_MS : SITE_FRESH_MS;
        return System.currentTimeMillis() - at > ttl ? null : r;
    }

    private static int spInt(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (Throwable th) {
            return 0;
        }
    }

    private static boolean spIsScript(String api) {
        if (api == null || api.isEmpty()) return false;
        String a = api.toLowerCase();
        return a.endsWith(".js") || a.contains(".js?") || a.contains("/drpy") || a.startsWith("js:");
    }

    private static JSONObject spObj(String json) {
        if (json == null) return null;
        String s = json.trim();
        if (s.isEmpty() || s.charAt(0) != '{') return null;
        try {
            return new JSONObject(s);
        } catch (Throwable th) {
            return null;
        }
    }

    private static int spListLen(JSONObject o) {
        if (o == null) return 0;
        JSONArray a = o.optJSONArray("list");
        return a == null ? 0 : a.length();
    }

    /** 一个分类节点的 tid；有的源写 type_id，有的源写 id */
    private static String spTidOf(JSONObject c) {
        if (c == null) return "";
        String tid = c.optString("type_id", "");
        if (tid.isEmpty()) tid = c.optString("id", "");
        return tid;
    }

    /** 切换首页片源站点，等同 TV 版 showSiteSwitch 里选中后的 setSourceBean */
    @JavascriptInterface
    public void setSite(String key, String cbId) {
        pool.execute(() -> {
            try {
                SourceBean sb = ApiConfig.get().getSource(key);
                if (sb == null) {
                    cbErrMsg(cbId, "找不到这个站点");
                    return;
                }
                ApiConfig.get().setSourceBean(sb);
                // 分类列表跟着站点走，必须丢弃旧的；排队中的旧站点请求同理
                resetSourceCaches("已切换线路");
                JSONObject out = new JSONObject();
                out.put("ok", 1);
                out.put("name", nz(sb.getName()));
                cb(cbId, out.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /**
     * 换源：拿片名搜遍所有可搜站点，返回同名条目。
     * 与 search() 的区别是**不按片名去重** —— 这里要的正是"同一部片在不同站点"的列表。
     */
    @JavascriptInterface
    public void altSources(String name, String curKey, String cbId) {
        altPool.execute(() -> {
            try {
                String wd = name == null ? "" : name.trim();
                if (wd.isEmpty()) {
                    cbErrMsg(cbId, "片名为空");
                    return;
                }
                final String want = normalizeName(wd);
                final List<SourceBean> targets = pickSearchSites(ALT_SITE_LIMIT);
                final List<JSONObject> hits = Collections.synchronizedList(new ArrayList<JSONObject>());
                final CountDownLatch latch = new CountDownLatch(targets.size());

                for (final SourceBean sb : targets) {
                    altPool.execute(() -> {
                        try {
                            List<Movie.Video> got = searchOne(sb, wd);
                            for (Movie.Video v : got) {
                                if (v == null || TextUtils.isEmpty(v.name)) continue;
                                String n = normalizeName(v.name);
                                // 同名，或者一方包含另一方（"沙丘2" vs "沙丘2 高清"）
                                if (!n.equals(want) && !n.contains(want) && !want.contains(n)) continue;
                                JSONObject o = new JSONObject();
                                o.put("key", nz(sb.getKey()));
                                o.put("site", nz(sb.getName()));
                                o.put("id", nz(v.id));
                                o.put("name", nz(v.name));
                                o.put("pic", nz(v.pic));
                                o.put("note", nz(v.note));
                                o.put("cur", nz(sb.getKey()).equals(nz(curKey)) ? 1 : 0);
                                hits.add(o);
                                break;   // 一个站点只留最像的那一条
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                try {
                    latch.await(9, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }

                JSONArray arr = new JSONArray();
                synchronized (hits) {
                    for (JSONObject o : hits) arr.put(o);
                }
                JSONObject out = new JSONObject();
                out.put("ok", arr.length() > 0 ? 1 : 0);
                out.put("list", arr);
                cb(cbId, out.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 打开原生设置页（播放器 / 解码 / 主题等） */
    @JavascriptInterface
    public void openSetting() {
        main.post(() -> {
            try {
                act.startActivity(new Intent(act, SettingActivity.class));
            } catch (Throwable th) {
                toast("打开设置失败");
            }
        });
    }

    /** 播放器内核选项（给 JS 一个简单的播放入口提示用） */
    @JavascriptInterface
    public void toast(String msg) {
        main.post(() -> {
            try {
                Toast.makeText(act, msg == null ? "" : msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    // ---- 播放偏好：默认全屏 / 默认小窗 ----

    /** 读「片头广告过滤」开关（m3u8 广告段，Exo / IJK 两条链路都生效） */
    @JavascriptInterface
    public void adFilter(String cbId) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", 1);
            o.put("on", Hawk.get(HawkConfig.M3U8_AD_FILTER, true) ? 1 : 0);
            cb(cbId, o.toString());
        } catch (Throwable th) {
            cbErr(cbId, th);
        }
    }

    /** 写「片头广告过滤」开关：value 传 1/0 */
    @JavascriptInterface
    public void setAdFilter(String value, String cbId) {
        try {
            boolean on = "1".equals(value) || "true".equalsIgnoreCase(value);
            Hawk.put(HawkConfig.M3U8_AD_FILTER, on);
            JSONObject o = new JSONObject();
            o.put("ok", 1);
            o.put("on", on ? 1 : 0);
            cb(cbId, o.toString());
        } catch (Throwable th) {
            cbErr(cbId, th);
        }
    }

    /** 读「播放默认全屏」开关 */
    @JavascriptInterface
    public void playPref(String cbId) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", 1);
            o.put("full", Hawk.get(HawkConfig.PHONE_PLAY_FULLSCREEN, false));
            cb(cbId, o.toString());
        } catch (Throwable th) {
            cbErr(cbId, th);
        }
    }

    /** 写「播放默认全屏」开关：value 传 1/0 */
    @JavascriptInterface
    public void setPlayPref(String value, String cbId) {
        try {
            boolean full = "1".equals(value) || "true".equalsIgnoreCase(value);
            Hawk.put(HawkConfig.PHONE_PLAY_FULLSCREEN, full);
            JSONObject o = new JSONObject();
            o.put("ok", 1);
            o.put("full", full);
            cb(cbId, o.toString());
        } catch (Throwable th) {
            cbErr(cbId, th);
        }
    }

    // ==================================================================
    //  设置页（分组参考影视仓：配置 / 设置 / 播放器 / 系统）
    // ==================================================================

    /** 直播源：Box 只存一条地址，存在 Hawk(LIVE_URL)，改完要重载配置才生效 */
    @JavascriptInterface
    public void liveInfo(String cbId) {
        pool.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("ok", 1);
                o.put("url", nz(Hawk.get(HawkConfig.LIVE_URL, "")));
                int groups = 0;
                try {
                    groups = ApiConfig.get().getChannelGroupList().size();
                } catch (Throwable ignored) {
                }
                o.put("groups", groups);
                cb(cbId, o.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 改直播地址。传空串表示清空（回到配置里自带的那条） */
    @JavascriptInterface
    public void setLiveUrl(String url, String cbId) {
        pool.execute(() -> {
            try {
                String u = url == null ? "" : url.trim();
                if (!u.isEmpty()) u = normalizeUrl(u);
                final String save = u;
                Hawk.put(HawkConfig.LIVE_URL, save);
                ApiConfig.get().loadConfig(false, new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void retry() {
                        cbErrMsg(cbId, "已保存，但重新加载配置失败，请退出重进");
                    }

                    @Override
                    public void success() {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("ok", 1);
                            o.put("url", save);
                            cb(cbId, o.toString());
                        } catch (Throwable th) {
                            cbErr(cbId, th);
                        }
                    }

                    @Override
                    public void error(String msg) {
                        cbErrMsg(cbId, "已保存，但重新加载配置失败，请退出重进");
                    }
                }, act);
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /**
     * 深浅色主题：system=跟随系统，dark=强制深色，light=强制浅色。
     * 生效完全在原生侧（系统栏图标、窗口底色、WebView 底色），前端只负责把选择告诉这里，
     * 然后靠原生注入的 data-theme 换 CSS 变量。
     */
    @JavascriptInterface
    public void themeInfo(String cbId) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", 1);
            o.put("mode", themeMode());
            o.put("dark", isDarkNow() ? 1 : 0);
            cb(cbId, o.toString());
        } catch (Throwable th) {
            cbErr(cbId, th);
        }
    }

    @JavascriptInterface
    public void setThemeMode(String mode, String cbId) {
        try {
            String m = nz(mode).trim();
            if (!"dark".equals(m) && !"light".equals(m)) m = "system";
            boolean dark;
            if (act instanceof PhoneActivity) {
                dark = ((PhoneActivity) act).setThemeMode(m);
            } else {
                // 兜底：没有 Activity 也要把选择存下来，下次冷启动生效
                Hawk.put(HawkConfig.PHONE_THEME_MODE, m);
                dark = "dark".equals(m) || ("system".equals(m) && isSystemNight());
            }
            JSONObject o = new JSONObject();
            o.put("ok", 1);
            o.put("mode", m);
            o.put("dark", dark ? 1 : 0);
            cb(cbId, o.toString());
        } catch (Throwable th) {
            cbErr(cbId, th);
        }
    }

    /** 与 PhoneActivity.themeMode() 同义，避免两边判定口径走偏 */
    private String themeMode() {
        try {
            String m = Hawk.get(HawkConfig.PHONE_THEME_MODE, "system");
            if ("dark".equals(m) || "light".equals(m)) return m;
        } catch (Throwable ignored) {
        }
        return "system";
    }

    private boolean isSystemNight() {
        try {
            int m = act.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m != android.content.res.Configuration.UI_MODE_NIGHT_NO;
        } catch (Throwable th) {
            return true;
        }
    }

    private boolean isDarkNow() {
        if (act instanceof PhoneActivity) return ((PhoneActivity) act).isNightModeNow();
        String m = themeMode();
        if ("dark".equals(m)) return true;
        if ("light".equals(m)) return false;
        return isSystemNight();
    }

    /** 搜索站点数（影视仓的「搜索线程」） */
    @JavascriptInterface
    public void searchLimit(String cbId) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", 1);
            o.put("limit", searchSiteLimit());
            cb(cbId, o.toString());
        } catch (Throwable th) {
            cbErr(cbId, th);
        }
    }

    @JavascriptInterface
    public void setSearchLimit(String value, String cbId) {
        try {
            int n;
            try {
                n = Integer.parseInt(nz(value).trim());
            } catch (Throwable th) {
                n = 0;
            }
            // 0 = 全部站点（默认，对齐影视仓的「全站搜索」）
            if (n < 0) n = 0;
            if (n > 200) n = 200;
            Hawk.put(HawkConfig.PHONE_SEARCH_LIMIT, n);
            JSONObject o = new JSONObject();
            o.put("ok", 1);
            o.put("limit", n);
            cb(cbId, o.toString());
        } catch (Throwable th) {
            cbErr(cbId, th);
        }
    }

    /** 安全 DNS（DoH）。0=关闭 1=腾讯 2=阿里 3=360 4=Google 5=AdGuard 6=Quad9 */
    private static final String[] DOH_NAMES = {"关闭", "腾讯", "阿里", "360", "Google", "AdGuard", "Quad9"};

    @JavascriptInterface
    public void dohInfo(String cbId) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", 1);
            o.put("cur", Hawk.get(HawkConfig.DOH_URL, 0));
            JSONArray arr = new JSONArray();
            for (int i = 0; i < DOH_NAMES.length; i++) {
                JSONObject it = new JSONObject();
                it.put("i", i);
                it.put("name", DOH_NAMES[i]);
                String u = "";
                try {
                    u = i == 0 ? "" : OkGoHelper.getDohUrl(i);
                } catch (Throwable ignored) {
                }
                it.put("url", nz(u));
                arr.put(it);
            }
            o.put("list", arr);
            cb(cbId, o.toString());
        } catch (Throwable th) {
            cbErr(cbId, th);
        }
    }

    @JavascriptInterface
    public void setDoh(String index, String cbId) {
        try {
            int i;
            try {
                i = Integer.parseInt(nz(index).trim());
            } catch (Throwable th) {
                i = 0;
            }
            if (i < 0) i = 0;
            if (i >= DOH_NAMES.length) i = DOH_NAMES.length - 1;
            Hawk.put(HawkConfig.DOH_URL, i);
            JSONObject o = new JSONObject();
            o.put("ok", 1);
            o.put("cur", i);
            // DoH 的 OkHttpClient 在 Application 初始化时构建，Ijk 的 setDotPort 也在起服务时设过，
            // 所以改完必须重启进程才真正生效
            o.put("needRestart", 1);
            cb(cbId, o.toString());
        } catch (Throwable th) {
            cbErr(cbId, th);
        }
    }

    /** 缓存占用（cacheDir + 外部 cacheDir），单位字节 */
    @JavascriptInterface
    public void cacheInfo(String cbId) {
        pool.execute(() -> {
            try {
                long size = dirSize(act.getCacheDir()) + dirSize(act.getExternalCacheDir());
                JSONObject o = new JSONObject();
                o.put("ok", 1);
                o.put("bytes", size);
                cb(cbId, o.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 清缓存：只清缓存，收藏和历史（Room 数据库）不动 */
    @JavascriptInterface
    public void clearCache(String cbId) {
        pool.execute(() -> {
            try {
                long before = dirSize(act.getCacheDir()) + dirSize(act.getExternalCacheDir());
                cacheClear();   // 详情/剧集内存缓存
                try {
                    // clearDiskCache 必须在子线程，主线程调用会直接抛异常
                    Glide.get(act).clearDiskCache();
                } catch (Throwable ignored) {
                }
                deleteInside(act.getCacheDir());
                deleteInside(act.getExternalCacheDir());
                main.post(() -> {
                    try {
                        Glide.get(act).clearMemory();
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (web != null) web.clearCache(true);
                    } catch (Throwable ignored) {
                    }
                });
                long after = dirSize(act.getCacheDir()) + dirSize(act.getExternalCacheDir());
                JSONObject o = new JSONObject();
                o.put("ok", 1);
                o.put("before", before);
                o.put("bytes", after);
                cb(cbId, o.toString());
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 重置 App：清配置、收藏、历史、缓存，然后重启进程 */
    @JavascriptInterface
    public void resetApp(String cbId) {
        pool.execute(() -> {
            try {
                try {
                    OkGo.getInstance().cancelAll();
                } catch (Throwable ignored) {
                }
                try {
                    Hawk.deleteAll();
                } catch (Throwable ignored) {
                }
                try {
                    RoomDataManger.deleteVodRecordAll();
                } catch (Throwable ignored) {
                }
                try {
                    RoomDataManger.deleteVodCollectAll();
                } catch (Throwable ignored) {
                }
                cacheClear();
                try {
                    Glide.get(act).clearDiskCache();
                } catch (Throwable ignored) {
                }
                deleteInside(act.getCacheDir());
                deleteInside(act.getExternalCacheDir());

                JSONObject o = new JSONObject();
                o.put("ok", 1);
                cb(cbId, o.toString());
                main.postDelayed(this::restartApp, 700);
            } catch (Throwable th) {
                cbErr(cbId, th);
            }
        });
    }

    /** 关于：版本号 / 站点数 / 当前 DNS */
    @JavascriptInterface
    public void appInfo(String cbId) {
        try {
            String ver = "";
            try {
                ver = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
            } catch (Throwable ignored) {
            }
            int sites = 0;
            try {
                sites = ApiConfig.get().getSourceBeanList().size();
            } catch (Throwable ignored) {
            }
            JSONObject o = new JSONObject();
            o.put("ok", 1);
            o.put("ver", nz(ver));
            o.put("sites", sites);
            o.put("doh", Hawk.get(HawkConfig.DOH_URL, 0));
            o.put("limit", searchSiteLimit());
            cb(cbId, o.toString());
        } catch (Throwable th) {
            cbErr(cbId, th);
        }
    }

    /** 进程自重启：先起新 Activity 再杀自己，系统会用已入队的 Intent 重新拉起 */
    private void restartApp() {
        try {
            Intent i = new Intent(act, PhoneActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            act.startActivity(i);
        } catch (Throwable ignored) {
        }
        try {
            act.finish();
        } catch (Throwable ignored) {
        }
        main.postDelayed(() -> {
            try {
                android.os.Process.killProcess(android.os.Process.myPid());
            } catch (Throwable ignored) {
            }
        }, 450);
    }

    private static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        long total = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File f : kids) total += dirSize(f);
        return total;
    }

    private static void deleteInside(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) deleteRec(f);
    }

    private static void deleteRec(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRec(k);
            }
        }
        try {
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 关掉正在播的小窗（「我的」离开页面时不用管，这里给详情页关闭按钮用） */
    @JavascriptInterface
    public void closeMini() {
        main.post(MiniPlayActivity::dismissIfAny);
    }

    @JavascriptInterface
    public void log(String msg) {
        android.util.Log.i("PhoneBridge", msg == null ? "" : msg);
    }

    // ==================================================================
    //  数据获取
    // ==================================================================

    private MovieSort.SortData findSort(String id) {
        if (id == null) return null;
        synchronized (sorts) {
            for (MovieSort.SortData sd : sorts) {
                if (id.equals(sd.id)) return sd;
            }
        }
        // config 还没跑过：退回第一个分类的 id 也没意义，直接返回 null
        return null;
    }

    /**
     * 搜索站点上限：0 = 全部（默认，对齐影视仓的「全站搜索」），1~200 可手动限。
     * <p>旧版默认 12 —— 配置里实际有一百多个可搜站，12 个搜出来的结果被
     * 「按片名合并」一压就只剩两三条，用户以为就这麼点片。</p>
     */
    private static int searchSiteLimit() {
        int n = 0;
        try {
            Object v = Hawk.get(HawkConfig.PHONE_SEARCH_LIMIT, 0);
            if (v instanceof Number) {
                n = ((Number) v).intValue();
            } else if (v != null) {
                n = Integer.parseInt(String.valueOf(v).trim());
            }
        } catch (Throwable ignored) {
        }
        if (n < 0) n = 0;
        if (n > 200) n = 200;
        return n;
    }

    /**
     * 挑选参与搜索的站点：首页源优先，其余按配置顺序。
     *
     * @param limit 上限，0 = 不限（全部）
     */
    private List<SourceBean> pickSearchSites(int limit) {
        List<SourceBean> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            SourceBean home = ApiConfig.get().getHomeSourceBean();
            if (home != null && home.isSearchable() && home.getApi() != null) {
                out.add(home);
                seen.add(home.getKey());
            }
            List<SourceBean> all = ApiConfig.get().getSourceBeanList();
            if (all != null) {
                for (SourceBean sb : all) {
                    if (limit > 0 && out.size() >= limit) break;
                    if (sb == null || !sb.isSearchable() || sb.getHide() == 1) continue;
                    if (sb.getApi() == null) continue;
                    if (seen.contains(sb.getKey())) continue;
                    seen.add(sb.getKey());
                    out.add(sb);
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return out;
    }

    private List<Movie.Video> searchOne(SourceBean sb, String wd) {
        try {
            int type = sb.getType();
            if (type == 3) {
                /* 取组件 + 首次初始化（有些组件 init 时会拉一次首页，可能几秒）都可能慢，
                   整段都算进单站超时里 —— 超时就放弃这个站，不能让它拖住整轮搜索。 */
                String json = callWithTimeout(() -> {
                    Spider sp = ApiConfig.get().getCSP(sb);
                    return sp.searchContent(wd, false);
                }, SITE_TIMEOUT_S);
                return parseVideoList(json, type, sb.getKey());
            } else if (type == 0 || type == 1 || type == 4) {
                com.lzy.okgo.request.GetRequest<String> req = OkGo.<String>get(sb.getApi())
                        .params("wd", wd)
                        .params("quick", "false")
                        .tag("phone_search");
                if (type == 1 || type == 4) req.params("ac", "detail");
                okhttp3.Response r = req.execute();
                String body = (r != null && r.body() != null) ? r.body().string() : null;
                return parseVideoList(body, type, sb.getKey());
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<>();
    }

    /** 播放兜底用：同步拉一次详情（解析规则与 SourceViewModel.getDetail 保持一致） */
    private AbsXml fetchDetailSync(String sourceKey, String vodId) {
        SourceBean sb = ApiConfig.get().getSource(sourceKey);
        if (sb == null) return null;
        try {
            int type = sb.getType();
            if (type == 3) {
                Spider sp = ApiConfig.get().getCSP(sb);
                // 必须是可变的 ArrayList：不少爬虫会对 ids 做 add/remove，
                // Collections.singletonList 会直接抛 UnsupportedOperationException
                List<String> ids = new ArrayList<>();
                ids.add(vodId);
                String json = callWithTimeout(() -> sp.detailContent(ids), 15);
                return parseAbsXml(json, type, sb.getKey());
            } else if (type == 0 || type == 1 || type == 4) {
                // extend 可能是 http 地址，原版会先抓回本地再用，不能直接透传
                String extend = vm.getFixUrl(sb.getExt());
                com.lzy.okgo.request.GetRequest<String> req = OkGo.<String>get(sb.getApi())
                        .params("ac", type == 0 ? "videolist" : "detail")
                        .params("ids", vodId)
                        .params("extend", extend)
                        .tag("phone_detail");
                okhttp3.Response r = req.execute();
                String body = (r != null && r.body() != null) ? r.body().string() : null;
                return parseAbsXml(body, type, sb.getKey());
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    private List<Movie.Video> parseVideoList(String body, int type, String key) {
        // 搜索列表只用来铺卡片，不需要剧集；这里跳过 beanList 拆分，
        // 否则 12 个站点 × 20 条 × 每条线路都要拆一遍，纯浪费
        AbsXml abs = parseAbsXml(body, type, key, false);
        if (abs == null || abs.movie == null || abs.movie.videoList == null) return new ArrayList<>();
        return abs.movie.videoList;
    }

    private AbsXml parseAbsXml(String body, int type, String key) {
        return parseAbsXml(body, type, key, true);
    }

    private AbsXml parseAbsXml(String body, int type, String key, boolean fillBeans) {
        if (TextUtils.isEmpty(body)) return null;
        try {
            AbsXml abs;
            if (type == 0) {
                XStream xstream = new XStream(new DomDriver());
                xstream.autodetectAnnotations(true);
                xstream.processAnnotations(AbsXml.class);
                xstream.ignoreUnknownElements();
                xstream.allowTypes(new Class[]{AbsXml.class});
                String xml = body;
                if (xml.contains("<year></year>")) xml = xml.replace("<year></year>", "<year>0</year>");
                if (xml.contains("<state></state>")) xml = xml.replace("<state></state>", "<state>0</state>");
                abs = (AbsXml) xstream.fromXML(xml);
            } else {
                AbsJson absJson = gson.fromJson(body, new TypeToken<AbsJson>() {
                }.getType());
                if (absJson == null) return null;
                abs = absJson.toAbsXml();
            }
            if (abs != null && abs.movie != null && abs.movie.videoList != null) {
                for (Movie.Video v : abs.movie.videoList) {
                    if (v == null) continue;
                    v.sourceKey = key;
                    // JSON 源不会自带 beanList，必须在这里拆一次，
                    // 否则 VodInfo.setVideo() 拿不到任何剧集
                    if (fillBeans && v.urlBean != null && v.urlBean.infoList != null) {
                        for (Movie.Video.UrlBean.UrlInfo ui : v.urlBean.infoList) {
                            if (ui != null) ui.ensureBeanList();
                        }
                    }
                }
            }
            return abs;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        }
    }

    private String callWithTimeout(Callable<String> task, int seconds) {
        /* 用共享的旁路池，不每次新建线程池：
           全站搜索一次要调上百次（每个站一次），每次新建一个池太浪费线程。
           超时后任务被放弃，但线程还在跑（网络调用不可中断），由 cached 池自然回收。 */
        Future<String> f = bgPool.submit(task);
        try {
            return f.get(seconds, TimeUnit.SECONDS);
        } catch (Throwable th) {
            try { f.cancel(true); } catch (Throwable ignored) { }
            return null;
        }
    }

    // ==================================================================
    //  JSON 组装
    // ==================================================================

    /**
     * 分类 + 这个分类自带的筛选项。
     * <p>
     * filters 只在 JSON 接口（filters 字段）和 jar / php 爬虫里有值，
     * 老式 XML 接口（ac=videolist）本来就没有筛选概念，这里返回空数组，
     * 前端据此把筛选条整条藏掉，不要留一排点不动的空按钮。
     * <p>
     * values 用二维数组而不是对象：选项顺序必须稳定（LinkedHashMap 的插入序），
     * 对象在部分 WebView 里遍历顺序不可靠，年份、地区会乱序。
     */
    private JSONObject sortJson(MovieSort.SortData sd) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", nz(sd.id));
        o.put("name", nz(sd.name));
        JSONArray filters = new JSONArray();
        if (sd.filters != null) {
            for (MovieSort.SortFilter f : sd.filters) {
                if (f == null || f.values == null || f.values.isEmpty()) continue;
                JSONObject fo = new JSONObject();
                fo.put("key", nz(f.key));
                fo.put("name", TextUtils.isEmpty(f.name) ? nz(f.key) : f.name);
                JSONArray vals = new JSONArray();
                for (Entry<String, String> e : f.values.entrySet()) {
                    JSONArray kv = new JSONArray();
                    kv.put(nz(e.getKey()));     // [0] 显示名
                    kv.put(nz(e.getValue()));   // [1] 提交值
                    vals.put(kv);
                }
                fo.put("values", vals);
                filters.put(fo);
            }
        }
        o.put("filters", filters);
        return o;
    }

    private JSONArray videoArray(List<Movie.Video> list) {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        for (Movie.Video v : list) {
            if (v == null) continue;
            try {
                arr.put(videoJson(v));
            } catch (Throwable ignored) {
            }
        }
        return arr;
    }

    /** 一条影片 → JSON（搜索结果还要额外带站点名，所以单独抽出来复用） */
    private JSONObject videoJson(Movie.Video v) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", nz(v.id));
            o.put("name", nz(v.name));
            o.put("pic", nz(v.pic));
            o.put("note", nz(v.note));
            o.put("type", nz(v.type));
            o.put("year", v.year);
            o.put("area", nz(v.area));
            o.put("lang", nz(v.lang));
            o.put("actor", nz(v.actor));
            o.put("director", nz(v.director));
            o.put("des", nz(v.des));
            o.put("key", nz(v.sourceKey));
        } catch (Throwable ignored) {
        }
        return o;
    }

    private String detailJson(AbsXml abs) throws Exception {
        JSONObject o = new JSONObject();
        if (abs == null || abs.movie == null || abs.movie.videoList == null || abs.movie.videoList.isEmpty()) {
            o.put("ok", 0);
            o.put("msg", abs != null && !TextUtils.isEmpty(abs.msg) ? abs.msg : "没有拿到详情");
            return o.toString();
        }
        Movie.Video v = abs.movie.videoList.get(0);
        o.put("ok", 1);
        o.put("id", nz(v.id));
        o.put("name", nz(v.name));
        o.put("pic", nz(v.pic));
        o.put("type", nz(v.type));
        o.put("year", v.year);
        o.put("area", nz(v.area));
        o.put("lang", nz(v.lang));
        o.put("actor", nz(v.actor));
        o.put("director", nz(v.director));
        o.put("des", nz(v.des));
        o.put("note", nz(v.note));
        o.put("key", nz(v.sourceKey));

        JSONArray flags = new JSONArray();
        try {
            // 和播放共用同一个构造点：顺手把 VodInfo 缓存起来，
            // 用户点「立即播放」时直接用这份，不再重新请求详情
            VodInfo vi = buildVodInfo(v.sourceKey, v.id, v);
            if (vi != null && vi.seriesMap != null) {
                for (String flag : vi.seriesMap.keySet()) {
                    JSONObject f = new JSONObject();
                    f.put("name", nz(flag));
                    JSONArray eps = new JSONArray();
                    List<VodInfo.VodSeries> series = vi.seriesMap.get(flag);
                    if (series != null) {
                        int i = 0;
                        for (VodInfo.VodSeries s : series) {
                            JSONObject e = new JSONObject();
                            e.put("n", nz(s.name));
                            e.put("i", i++);
                            eps.put(e);
                        }
                    }
                    f.put("eps", eps);
                    flags.put(f);
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        o.put("flags", flags);
        return o.toString();
    }

    /** 与 DetailActivity.getSeriesGroupList() 的分组规则保持一致 */
    private static int groupCountFor(int size) {
        return size > 2500 ? 300 : size > 1500 ? 200 : size > 1000 ? 150
                : size > 500 ? 100 : size > 300 ? 50 : size > 100 ? 30 : 20;
    }

    private static String normalizeName(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\s·:：\\-—_（）()\\[\\]【】]", "").toLowerCase();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ==================================================================
    //  回调
    // ==================================================================

    private void cb(String cbId, String json) {
        if (cbId == null) return;
        final String js = "window.__cb(" + JSONObject.quote(cbId) + "," + JSONObject.quote(json == null ? "{}" : json) + ")";
        main.post(() -> {
            try {
                web.evaluateJavascript(js, null);
            } catch (Throwable ignored) {
            }
        });
    }

    private void cbErr(String cbId, Throwable th) {
        String msg = th == null ? "未知错误" : (th.getMessage() == null ? th.getClass().getSimpleName() : th.getMessage());
        cbErrMsg(cbId, msg);
    }

    private void cbErrMsg(String cbId, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", 0);
            // 前端是 textContent，换行不会渲染，统一压成空格，避免错误提示断成两行空白
            o.put("msg", msg == null ? "未知错误" : msg.replace("\r", " ").replace("\n", " ").trim());
            cb(cbId, o.toString());
        } catch (Throwable ignored) {
        }
    }

    public void destroy() {
        try {
            pendingPlay = null;
            detailCb = null;
            // 队列不清理的话，销毁后残留的请求还会往已经 dead 的 WebView 回调
            synchronized (listQ) {
                listQ.clear();
                hotQ.clear();
                flyingReq = null;
                listBusy = false;
                listToken++;   // 在飞那条的超时看门狗会看到新代次，自己作废
            }
            cacheClear();
            OkGo.getInstance().cancelTag("phone_search");
            OkGo.getInstance().cancelTag("phone_detail");
            pool.shutdownNow();
            searchPool.shutdownNow();
            altPool.shutdownNow();
            searchCoord.shutdownNow();
            jarPool.shutdownNow();
            bgPool.shutdownNow();
        } catch (Throwable ignored) {
        }
    }
}

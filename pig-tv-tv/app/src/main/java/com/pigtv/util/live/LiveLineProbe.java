package com.pigtv.util.live;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.pigtv.util.FailBackoff;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 直播线路择优：一个频道挂着好几条线时，决定先试哪条。
 *
 * <p>原来的做法是<b>盲跳</b>：超时就 {@code nextSource()}，一条条轮，轮完换台。
 * 问题是一个频道五条线，四条是死的，用户就要挨四次超时（每条 10 秒）才能看到下一个台，
 * 而且下次进来还是这个顺序 —— 死线永远排在最前面。
 *
 * <p>这里做两件事，都不挡首帧：
 * <ol>
 *   <li><b>排序只读缓存</b>：{@link #rank} 是纯本地计算（{@link FailBackoff} 的失败次数
 *       + 上一次测速的分数），同步返回，不产生任何网络请求；</li>
 *   <li><b>测速在后台攒</b>：{@link #probeAsync} 给这个频道的线路打一次分，并发 3、
 *       总预算 {@link #BUDGET_MS}，测完写缓存，<b>下一次</b>换线就用真实分数。</li>
 * </ol>
 *
 * <p>为什么不在切台时就测：用户按一下台标是要立刻看到画面，多等 2 秒测速是纯粹的伤害。
 * 失效是偶发的，等真出问题再测，代价才落在真正需要它的那次。
 *
 * <p>三条约束：
 * <ul>
 *   <li><b>测不出来不等于坏</b>。拿不到速度分（服务端不吐数据、被限流）只降低排序优先级，
 *       绝不当成死线剔除——宁可让用户多试一条，也不能把能看的线藏起来；</li>
 *   <li><b>手动换线一律不干预</b>。用户按「上一个/下一个线路」就是明确要看那一条，
 *       排序只在自动换线时才参与决策；</li>
 *   <li><b>测速必须收敛</b>。稳定的线 600ms 就出结论，别为一条烂线等满超时——
 *       否则一轮测速比盲跳还慢。</li>
 * </ul>
 */
public final class LiveLineProbe {

    /** 排查用：`adb logcat -s PigLive` */
    private static final String TAG = "PigLive";

    /** 并发：直播线大多挂在同一批 CDN 上，放开了跑会互相拖成超时 */
    private static final int CONCURRENCY = 3;
    /** 一次评测的总预算 */
    private static final long BUDGET_MS = 2_600L;
    /** 单条连接超时 */
    private static final int CONNECT_MS = 2_000;
    /** 单条读超时 */
    private static final int READ_MS = 2_500;
    /** 采样窗口：够了就收工，不等满超时 */
    private static final long SAMPLE_MS = 600L;
    /** 采样字节上限 */
    private static final int SAMPLE_BYTES = 256 * 1024;
    /**
     * 算速度的下限。低于这个量就算出天文数字：一个秒回 200 但立刻断流的源，
     * 分母趋近于零，速度能到几百 MiB/s 直接冲上榜一。
     */
    private static final long MIN_MEASURE_MS = 250L;
    private static final int MIN_MEASURE_BYTES = 24 * 1024;
    /** 分数缓存有效期 */
    private static final long SCORE_TTL_MS = 10 * 60 * 1000L;

    /** 占位源关键字：命中即判「能播但没内容」 */
    private static final String[] AD_KEYS = {
            "no_signal", "nosignal", "no-signal", "signal_offline",
            "no_video", "novideo", "advertisement", "advert",
            "placeholder", "default_video", "cctv_off", "/ad/", "/ads/"
    };
    /** 点播列表（带 ENDLIST）总时长不超过这个值 → 死循环占位 */
    private static final int AD_MAX_LOOP_SECONDS = 90;

    private static final Pattern EXTINF = Pattern.compile("#EXTINF:\\s*([0-9.]+)");

    private static final Map<String, Score> SCORES = new ConcurrentHashMap<>();
    /** 测速用的固定线程池，并发数就是它的大小 */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(CONCURRENCY);
    /**
     * 调度用的单独线程。{@link #runProbe} 自己会阻塞等 latch，
     * 不能和测量任务抢同一个固定池——池子只有 CONCURRENCY 个位置，
     * 被它占一个就少一个工人，5 条线会退化成 2 条串行。
     */
    private static final ExecutorService ORCH = Executors.newSingleThreadExecutor();
    /** 同一批线路同时只跑一轮评测，避免换线连点时堆任务 */
    private static final Map<String, Boolean> RUNNING = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile OkHttpClient client;

    private LiveLineProbe() {
    }

    /** 一条线的评测结果 */
    public static final class Score {
        /** 首字节耗时，ms；-1 = 没连上 */
        public long delay = -1;
        /** MiB/s；-1 = 测不出来（不是 0） */
        public double speed = -1;
        /** 连不上 */
        public boolean dead;
        /** 能连上，但是无信号/广告占位 */
        public boolean placeholder;
        public long at;
    }

    // =====================================================================
    //  排序（同步、纯本地）
    // =====================================================================

    /**
     * 把线路按「该先试哪条」排好返回，长度与入参一致、元素一一对应。
     *
     * <p>用的是<b>一个综合优先级</b>而不是多级比较：
     * {@code 测速分 − 失败次数 × 25}。分成多级（先比失败次数、再比分数）看着更清楚，
     * 实际会把一条「失败过一次但实测 5 MiB/s」的好线，排到一堆「从没测过」的线后面 ——
     * 失败次数只是先验，不能盖过实测证据。
     *
     * <p>失败分不参与比较，实测分相同时退回首字节耗时，最后用原始下标兜底：
     * 不兜底的话，同一批「没测过也没失败过」的线每次调用顺序都可能不同，
     * 用户会看到线路号莫名其妙地跳。
     */
    public static List<String> rank(@Nullable List<String> urls) {
        if (urls == null || urls.size() <= 1) {
            return urls == null ? new ArrayList<String>() : new ArrayList<>(urls);
        }
        final Map<String, Integer> orig = new HashMap<>();
        for (int i = 0; i < urls.size(); i++) {
            // 同一频道里理论上不会重复，真重复了就取第一次出现的位置
            if (!orig.containsKey(urls.get(i))) orig.put(urls.get(i), i);
        }
        List<String> out = new ArrayList<>(urls);
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                double pa = priorityOf(a);
                double pb = priorityOf(b);
                if (Math.abs(pa - pb) > 0.5) return pa > pb ? -1 : 1;
                long da = delayOf(a);
                long db = delayOf(b);
                if (da != db) return da < db ? -1 : 1;
                int oa = orig.containsKey(a) ? orig.get(a) : Integer.MAX_VALUE;
                int ob = orig.containsKey(b) ? orig.get(b) : Integer.MAX_VALUE;
                return oa - ob;
            }
        });
        // 只在顺序真的变了时打——每次切台都打会把日志刷满
        if (!out.equals(urls)) {
            Log.i(TAG, "rank " + brief(urls) + "  ->  " + brief(out));
        }
        return out;
    }

    /**
     * 综合优先级：越大越该先试。
     *
     * <p>失败一次扣 25 分（约等于 0.25 MiB/s 的档次），连续失败三次以上基本就沉底了，
     * 但一次偶发失败不会把一条实测很快的线埋掉。
     */
    private static double priorityOf(String url) {
        return scoreOf(url) - FailBackoff.failsOf(url) * 25.0;
    }

    /** 这个频道有没有值得信任的测速结果（决定要不要在后台再测一轮） */
    public static boolean hasFreshScores(@Nullable List<String> urls) {
        if (urls == null || urls.isEmpty()) return false;
        long now = System.currentTimeMillis();
        for (String u : urls) {
            Score s = SCORES.get(u);
            if (s != null && now - s.at <= SCORE_TTL_MS) return true;
        }
        return false;
    }

    /**
     * 后台给这个频道的线路打一次分。幂等，重复调用会被 {@link #RUNNING} 挡掉。
     *
     * @param onDone 测完回主线程调一次（缓存已写好），可以为 null
     */
    public static void probeAsync(@Nullable final List<String> urls, @Nullable final Runnable onDone) {
        if (urls == null || urls.size() < 2) {
            if (onDone != null) MAIN.post(onDone);
            return;
        }
        final String sig = signature(urls);
        if (RUNNING.put(sig, Boolean.TRUE) != null) return;      // 已经在跑
        Log.i(TAG, "probe start n=" + urls.size() + "  " + brief(urls));

        ORCH.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runProbe(urls);
                } catch (Throwable t) {
                    Log.w(TAG, "probe error", t);
                } finally {
                    RUNNING.remove(sig);
                    if (onDone != null) MAIN.post(onDone);
                }
            }
        });
    }
    /** 清掉缓存（换配置 / 换源后调） */
    public static void clear() {
        SCORES.clear();
    }

    // =====================================================================
    //  测速
    // =====================================================================

    private static void runProbe(List<String> urls) {
        final long deadline = System.currentTimeMillis() + BUDGET_MS;
        final CountDownLatch latch = new CountDownLatch(urls.size());

        for (final String u : urls) {
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (System.currentTimeMillis() >= deadline) return;
                        // 退避期里的线别再打了，浪费预算
                        if (FailBackoff.isFrozen(u)) {
                            Score s = new Score();
                            s.dead = true;
                            s.at = System.currentTimeMillis();
                            SCORES.put(u, s);
                            return;
                        }
                        SCORES.put(u, measure(u));
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            latch.await(BUDGET_MS + 600L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "probe interrupted", e);
        }
        StringBuilder sb = new StringBuilder();
        for (String u : urls) {
            Score s = SCORES.get(u);
            sb.append(' ').append(hostOf(u)).append('=');
            if (s == null) sb.append('?');
            else if (s.dead) sb.append("dead");
            else sb.append(String.format(Locale.US, "%.2fMiB/%dms%s",
                        s.speed, s.delay, s.placeholder ? "/AD" : ""));
        }
        Log.i(TAG, "probe done" + sb);
    }

    private static Score measure(String url) {
        Score s = new Score();
        s.at = System.currentTimeMillis();
        String clean = url == null ? "" : url;
        // 线路后面可能挂着 $标签（接口描述），请求要用去掉标签的地址
        int dollar = clean.indexOf('$');
        if (dollar > 0) clean = clean.substring(0, dollar);

        Response resp = null;
        InputStream in = null;
        long t0 = System.currentTimeMillis();
        long firstByteAt = 0;
        long total = 0;
        try {
            Request req = new Request.Builder()
                    .url(clean)
                    .header("User-Agent", "okhttp/3.15")
                    .header("Accept", "*/*")
                    .get()
                    .build();
            resp = client().newCall(req).execute();
            if (resp.code() < 200 || resp.code() >= 400) {
                s.dead = true;
                return s;
            }
            ResponseBody body = resp.body();
            if (body == null) {
                s.dead = true;
                return s;
            }
            in = body.byteStream();
            byte[] buf = new byte[16 * 1024];
            byte[] head = new byte[64 * 1024];
            int headLen = 0;
            long readStart = 0;
            while (true) {
                int n = in.read(buf);
                if (n <= 0) break;
                if (firstByteAt == 0) {
                    firstByteAt = System.currentTimeMillis();
                    readStart = firstByteAt;
                    s.delay = firstByteAt - t0;
                }
                total += n;
                if (headLen < head.length) {
                    int copy = Math.min(n, head.length - headLen);
                    System.arraycopy(buf, 0, head, headLen, copy);
                    headLen += copy;
                }
                if (total >= SAMPLE_BYTES) break;
                if (System.currentTimeMillis() - readStart >= SAMPLE_MS) break;
            }
            long elapsed = System.currentTimeMillis() - (readStart == 0 ? t0 : readStart);
            if (total <= 0) {
                // 状态 200 但一个字节都没吐：这不是「慢」，是死了。
                // 不判死的话它会拿到「测不出速度」的保底分，排在真能看的线前面。
                s.dead = true;
                return s;
            }
            if (elapsed >= MIN_MEASURE_MS && total >= MIN_MEASURE_BYTES) {
                s.speed = total / (elapsed / 1000.0) / 1024.0 / 1024.0;
            }
            if (headLen > 0) {
                s.placeholder = isPlaceholder(head, headLen);
            }
        } catch (Throwable t) {
            s.dead = true;
            if (s.delay < 0) s.delay = -1;
            Log.w(TAG, "measure fail " + hostOf(clean) + " : " + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (resp != null) resp.close();
        }
        if (!s.dead) {
            // 硬证据：真读到数据了就算活，把历史失败清掉，下次排序不会再压它
            if (!s.placeholder) FailBackoff.markGood(url);
            else FailBackoff.markBad(url);
        } else {
            FailBackoff.markBad(url);
        }
        return s;
    }

    /**
     * 无信号 / 广告占位判定。只看 m3u8 文本，二进制流（ts/flv）直接放过。
     *
     * <p>两条判据（与 Guovin/iptv-api 的实现一致）：
     * <ol>
     *   <li>片段地址或列表头里带 no_signal / advertisement / placeholder 这类关键字；</li>
     *   <li>是点播列表（有 {@code #EXT-X-ENDLIST}）且总时长 ≤ 90 秒 —— 直播不该有 ENDLIST，
     *       有就是一段循环播放的占位视频。</li>
     * </ol>
     * 这类源 HTTP 200、有码率、能解码，只看状态码和速度是永远识别不出来的。
     */
    private static boolean isPlaceholder(byte[] head, int len) {
        String text;
        try {
            text = new String(head, 0, len, Charset.forName("ISO-8859-1"));
        } catch (Throwable t) {
            return false;
        }
        if (!text.contains("#EXTM3U") && !text.contains("#EXTINF")) return false;
        String low = text.toLowerCase();
        for (String k : AD_KEYS) {
            if (low.contains(k)) return true;
        }
        if (!low.contains("#ext-x-endlist")) return false;
        double total = 0;
        Matcher m = EXTINF.matcher(text);
        while (m.find()) {
            try {
                total += Double.parseDouble(m.group(1));
            } catch (Throwable ignored) {
            }
        }
        return total > 0 && total <= AD_MAX_LOOP_SECONDS;
    }

    // =====================================================================
    //  内部工具
    // =====================================================================

    private static double scoreOf(String url) {
        Score s = SCORES.get(url);
        if (s == null) return 0;
        long now = System.currentTimeMillis();
        if (now - s.at > SCORE_TTL_MS) return 0;
        if (s.dead) return -1;
        if (s.placeholder) return -0.5;
        if (s.speed < 0) {
            // 只测到延迟、测不出速度：给个保底分。不能当 0 分处理，
            // 否则一条 30ms 就回应的线会被排到已确认 0.1MiB/s 的线后面。
            return 0.35 * 100;
        }
        double cap = Math.min(s.speed, 8.0);      // 封顶 8 MiB/s，防止一条超快线压死所有备选
        double pen = Math.min(s.delay <= 0 ? 500 : s.delay, 4000) / 4000.0;
        return cap * 100 - pen * 40;
    }

    private static long delayOf(String url) {
        Score s = SCORES.get(url);
        if (s == null || s.at == 0) return Long.MAX_VALUE;
        return s.delay < 0 ? Long.MAX_VALUE - 1 : s.delay;
    }

    /** 日志用：`下标:host`，不打完整地址（线路名后面可能带 token） */
    private static String brief(List<String> urls) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(i).append(':').append(hostOf(urls.get(i)));
        }
        return sb.toString();
    }

    private static String hostOf(String url) {
        if (url == null) return "null";
        int i = url.indexOf("://");
        int st = i >= 0 ? i + 3 : 0;
        int e = url.indexOf('/', st);
        if (e < 0) e = url.length();
        int d = url.indexOf('$');            // 线路标签，不属于地址
        if (d >= 0 && d < e) e = d;
        e = Math.min(e, url.length());
        String h = url.substring(Math.min(st, e), e);
        return h.length() > 40 ? h.substring(0, 40) : h;
    }

    private static String signature(List<String> urls) {
        if (urls == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String u : urls) {
            if (TextUtils.isEmpty(u)) continue;
            sb.append(u).append('\n');
        }
        return Integer.toHexString(sb.toString().hashCode()) + "-" + urls.size();
    }

    private static OkHttpClient client() {
        if (client == null) {
            synchronized (LiveLineProbe.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(CONNECT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(READ_MS, TimeUnit.MILLISECONDS)
                            .retryOnConnectionFailure(false)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build();
                }
            }
        }
        return client;
    }
}
